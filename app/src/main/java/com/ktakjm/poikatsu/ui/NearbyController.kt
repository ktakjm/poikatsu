package com.ktakjm.poikatsu.ui

import android.app.Application
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import com.ktakjm.poikatsu.data.AppSettings
import com.ktakjm.poikatsu.data.LocationProvider
import com.ktakjm.poikatsu.data.Merchant
import com.ktakjm.poikatsu.data.MerchantRule
import com.ktakjm.poikatsu.data.PoikatsuData
import com.ktakjm.poikatsu.data.YolpClient
import com.ktakjm.poikatsu.data.YolpSearchConfig
import com.ktakjm.poikatsu.domain.CampaignJudgment
import com.ktakjm.poikatsu.domain.CampaignType
import com.ktakjm.poikatsu.domain.HiddenReason
import com.ktakjm.poikatsu.domain.JudgmentEngine
import com.ktakjm.poikatsu.domain.JudgmentResult
import com.ktakjm.poikatsu.domain.StoreVisibility
import com.ktakjm.poikatsu.domain.bestBenefitLabel
import com.ktakjm.poikatsu.domain.campaignType
import com.ktakjm.poikatsu.domain.classifyStore
import com.ktakjm.poikatsu.domain.isPrefectureWide
import com.ktakjm.poikatsu.domain.isTimeLimited
import com.ktakjm.poikatsu.domain.municipalCampaignsForLocation
import com.ktakjm.poikatsu.domain.municipalRegionsLabel
import com.ktakjm.poikatsu.ui.MainViewModel.DebugPoi
import com.ktakjm.poikatsu.ui.MainViewModel.DebugPoiStatus
import com.ktakjm.poikatsu.ui.MainViewModel.GeocodedPlace
import com.ktakjm.poikatsu.ui.MainViewModel.MunicipalNotice
import com.ktakjm.poikatsu.ui.MainViewModel.NearbyLens
import com.ktakjm.poikatsu.ui.MainViewModel.NearbyLoadPhase
import com.ktakjm.poikatsu.ui.MainViewModel.NearbyPlace
import com.ktakjm.poikatsu.ui.MainViewModel.NearbyUi
import com.ktakjm.poikatsu.ui.MainViewModel.Selection
import com.ktakjm.poikatsu.ui.MainViewModel.UiState
import com.ktakjm.poikatsu.util.GeoMath
import java.time.LocalDate
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber

/** 「地図」初回・「現在地で検索」時の既定半径(m)。以降の「このエリアを検索」は地図の可視範囲から算出する */
private const val NEARBY_DEFAULT_RADIUS_M = 2000

/** 「地図」初回・「現在地で検索」時の既定ズーム。可視範囲検索では各回の地図ズームを引き継ぐ */
internal const val NEARBY_DEFAULT_ZOOM = 16.0

/** 500m 以内の店舗がこの件数未満なら引きズーム(NEARBY_WIDE_ZOOM)にする */
private const val NEARBY_DENSE_THRESHOLD = 10
private const val NEARBY_DENSE_RADIUS_M = 500
private const val NEARBY_WIDE_ZOOM = 15.0

/**
 * 2段階表示の補正しきい値(m)。キャッシュ位置で先に地図を出した後、新鮮な測位がこれ以上
 * ずれていたら検索し直す。未満なら青ドットだけ直す(検索半径2kmに対し誤差として許容できる範囲)
 */
private const val LOCATION_CORRECTION_M = 100

/** 位置情報を取得できないときのフォールバック地点(新宿駅) */
private val FALLBACK_PLACE = GeocodedPlace(
    name = "新宿駅",
    fullAddress = "東京都新宿区新宿三丁目",
    lat = 35.6896,
    lon = 139.7006,
)

/**
 * 「地図」タブ(近隣検索・位置情報・地名検索)のロジックを束ねる MainViewModel の delegate(#88)。
 * UiState は VM と共有の [state] を直接更新する(専用 ViewModel に分けるとタブ切替・ブリッジ・
 * rebuild 連動で 2 つの StateFlow の同期が要るため採らない)。他タブとの接点は
 * [onFindNearby]/[onFindNearbyByIds](ブリッジ突入)と [onCloseNearby](ブリッジ復帰)、
 * VM 側から呼ぶ [invalidate]/[recompute] のみ。
 *
 * @param engine 判定エンジン(データ・設定変更で作り直されるためアクセサで受ける。以下同)
 * @param selectionFor 判定詳細用 Selection の組み立て(判定は VM の責務のため委譲)。engine 未初期化なら null
 * @param selectTab タブ切替(onCloseNearby の非ブリッジ時にお店タブへ戻る)
 */
class NearbyController(
    private val app: Application,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<UiState>,
    private val engine: () -> JudgmentEngine?,
    private val displayData: () -> PoikatsuData?,
    private val settings: () -> AppSettings,
    private val selectionFor: (
        merchant: Merchant,
        storeNameHint: String,
        displayName: String?,
        bannerId: String?,
    ) -> Selection?,
    private val selectTab: (AppTab) -> Unit,
) {
    private val locationProvider = LocationProvider(app)

    /**
     * 近隣取得の世代。タブ移動(onCloseNearby)や再取得のたびに進め、進行中の取得が
     * 完了しても古い世代なら結果を捨てる。読込中に「地図」タブを離れたのに、取得完了の
     * タイミングで勝手に地図タブへ戻されるのを防ぐ。書込はUIスレッドのみ・IOスレッドは
     * 読むだけなので @Volatile で可視性だけ確保する。
     */
    @Volatile
    private var nearbyGeneration = 0

    /** 進行中の近隣取得を破棄する(結果が返っても反映しない)。タブ離脱時に VM 側から呼ぶ */
    fun invalidate() {
        nearbyGeneration++
    }

    /** 位置情報パーミッション取得済みの前提で呼ぶ(UI側でリクエスト) */
    fun fetchNearby() {
        if (engine() == null) return
        val isInitial = state.value.nearby.search?.centerLat == null
        val gen = ++nearbyGeneration
        // 再取得(📍)中も直前の地図・一覧は残し loading だけ立てる(画面をまっさらにしない)。
        // 初回は prev が無い=NearbyUi() なので center も null となり全画面ローディングになる。
        // origin は位置情報の取得に成功してからクリアする(失敗時に起点表示が変わらないように)。
        state.update {
            val base = it.nearby.search ?: NearbyUi()
            it.copy(
                nearby = it.nearby.copy(
                    search = base.copy(
                        loading = true,
                        loadingPhase = NearbyLoadPhase.LOCATING,
                        error = null,
                        selectedPlace = null,
                    ),
                    geocodeCandidates = emptyList(),
                    isGeocoding = false,
                ),
                selection = null,
                storeCheck = null,
            )
        }
        scope.launch(Dispatchers.IO) {
            if (!locationProvider.hasPermission()) {
                val msg = "位置情報の許可が必要です。端末の設定からこのアプリに位置情報を許可してください"
                if (isInitial) fallbackToDefaultPlace(gen, msg) else failNearby(gen, msg)
                return@launch
            }
            // 2段階表示: FLP のキャッシュ位置(新鮮なときだけ返る)があればまずそれで即座に
            // 地図・検索を出し、並行して取っている新鮮な測位が大きくずれていたら検索し直す。
            // キャッシュが無ければ従来どおり測位を待つ(LOCATING 表示)。
            val freshDeferred = async { locationProvider.currentLocation() }
            val cached = locationProvider.lastLocation()
            if (cached != null) {
                searchAroundLocation(gen, cached)
                val fresh = freshDeferred.await() ?: return@launch
                // 測位待ちの間に別の検索(このエリアを検索・地名検索・タブ移動)が始まっていたら
                // 補正で上書きしない
                if (gen != nearbyGeneration) return@launch
                val movedM = GeoMath.distanceMeters(
                    cached.latitude, cached.longitude, fresh.latitude, fresh.longitude,
                )
                if (movedM >= LOCATION_CORRECTION_M) {
                    // キャッシュ位置がずれていた: 同じ操作の続きとして新鮮な位置で取り直す
                    // (loading・カメラ寄せは searchAroundLocation → showMapAt が立て直す)
                    searchAroundLocation(gen, fresh)
                } else {
                    // ずれが小さければ青ドットだけ実測位置に直す(地図・一覧はそのまま)
                    updateUserLocation(fresh)
                }
            } else {
                val fresh = freshDeferred.await()
                if (fresh != null) {
                    searchAroundLocation(gen, fresh)
                } else {
                    val msg = "現在地を取得できませんでした。位置情報設定を確認してください"
                    if (isInitial) fallbackToDefaultPlace(gen, msg) else failNearby(gen, msg)
                }
            }
        }
    }

    /** 現在地(GPS 起点)を中心に既定半径で検索する。fetchNearby の1段目・2段目補正の共通処理 */
    private fun searchAroundLocation(gen: Int, location: Location) {
        state.update { it.copy(nearby = it.nearby.copy(origin = null)) }
        showMapAt(gen, location.latitude, location.longitude, location.latitude, location.longitude)
        loadNearbyAround(
            gen, location.latitude, location.longitude, location.latitude, location.longitude,
            location.latitude, location.longitude,
            radiusM = NEARBY_DEFAULT_RADIUS_M, zoom = NEARBY_DEFAULT_ZOOM,
            adaptZoom = true,
        )
    }

    /**
     * 検索中心が確定した時点で、YOLP 取得を待たずに先に地図を出す。loading は立てたままにし、
     * 進捗(「周辺の店舗を探しています…」)はボトムシートと地図上のピルが表示する。
     * searchStamp をこの世代に進めてカメラを新しい中心へ寄せる(結果反映時は同値なので再移動しない。
     * adaptZoom による引きズームだけは zoom の変化で反映される)。
     */
    private fun showMapAt(gen: Int, centerLat: Double, centerLon: Double, userLat: Double?, userLon: Double?) {
        state.update {
            if (gen != nearbyGeneration) return@update it
            val base = it.nearby.search ?: NearbyUi()
            it.copy(
                nearby = it.nearby.copy(
                    search = base.copy(
                        loading = true,
                        loadingPhase = NearbyLoadPhase.SEARCHING,
                        error = null,
                        selectedPlace = null,
                        centerLat = centerLat,
                        centerLon = centerLon,
                        userLat = userLat ?: base.userLat,
                        userLon = userLon ?: base.userLon,
                        zoom = NEARBY_DEFAULT_ZOOM,
                        searchStamp = gen,
                    ),
                ),
            )
        }
    }

    /**
     * 青ドット(現在地表示)だけを実測位置に更新する。カメラ・検索結果・距離ラベルには触らない
     * (距離の再計算・YOLP 再検索はしない。再検索は「このエリアを検索」で明示的に行う方針)
     */
    private fun updateUserLocation(location: Location) {
        state.update { st ->
            val search = st.nearby.search ?: return@update st
            st.copy(
                nearby = st.nearby.copy(
                    search = search.copy(userLat = location.latitude, userLon = location.longitude),
                ),
            )
        }
    }

    /**
     * 現在地の継続購読。「地図」タブ表示中のみ UI 側(PoikatsuApp)から lifecycle スコープで呼び、
     * タブ離脱・バックグラウンドで collect ごとキャンセルされて購読解除される。
     * 青ドットを追従させるだけで、カメラ移動・YOLP 再検索は行わない。
     */
    suspend fun observeLocationUpdates() {
        if (!locationProvider.hasPermission()) return
        locationProvider.locationUpdates().collect { updateUserLocation(it) }
    }

    /**
     * 地図の中心を起点に再検索(「このエリアを検索」)。半径は地図の可視範囲から、ズームは現在の
     * 地図ズームを受け取り、結果反映時にカメラを動かさず可視範囲そのままで取り直す。青ドットは維持。
     */
    fun searchHere(lat: Double, lon: Double, radiusM: Int, zoom: Double) {
        if (engine() == null) return
        val prev = state.value.nearby.search
        // 現在地は「実際に測位できた値」だけを引き継ぐ(取れていないときに地図中心で捏造すると
        // 青ドットが偽の場所に出る)。距離の起点だけは 起点指定 → 現在地 → 地図中心 の順で決める
        val userLat = prev?.userLat
        val userLon = prev?.userLon
        val origin = state.value.nearby.origin
        val originLat = origin?.lat ?: userLat ?: lat
        val originLon = origin?.lon ?: userLon ?: lon
        val gen = ++nearbyGeneration
        // 再検索中も直前の地図・一覧を残し loading だけ立てる(画面をまっさらにしない)。
        // center は prev のまま保持して完了までカメラを動かさず、結果反映時に新しい中心へ寄せる。
        state.update {
            val base = it.nearby.search ?: NearbyUi()
            it.copy(
                nearby = it.nearby.copy(
                    search = base.copy(
                        loading = true,
                        loadingPhase = NearbyLoadPhase.SEARCHING,
                        error = null,
                        selectedPlace = null,
                    ),
                ),
            )
        }
        scope.launch(Dispatchers.IO) {
            loadNearbyAround(gen, lat, lon, userLat, userLon, originLat, originLon, radiusM, zoom)
        }
    }

    /**
     * centerLat/centerLon を起点に YOLP で周辺店舗を取得する。
     * リストのソートは地図中心(centerLat/centerLon)からの距離順。
     * 距離表示は起点(originLat/originLon = GPS or 地名検索地点)基準。
     */
    private fun loadNearbyAround(
        gen: Int,
        centerLat: Double,
        centerLon: Double,
        userLat: Double?,
        userLon: Double?,
        originLat: Double,
        originLon: Double,
        radiusM: Int,
        zoom: Double,
        adaptZoom: Boolean = false,
    ) {
        val engine = engine() ?: return
        // 合成 Merchant(カスタムキャンペーンの自由入力店名)も YOLP 検索対象に含めるため統合データを使う
        val data = displayData()
        val settings = settings()
        // チェーン絞り込み中の merchant は、非対象日・開始前でも YOLP 検索対象に加える
        // (施策詳細からのブリッジで「場所の下見」ができるように。判定が無い店は還元率ラベルなしで出す)
        val filterIds = state.value.nearby.merchantFilters.map { it.merchant.id }.toSet()
        val config = data?.yolpConfig?.let { yolpConfig ->
            YolpSearchConfig.build(yolpConfig, data.merchants, engine.activeManagedMerchantIds(LocalDate.now()) + filterIds)
        }
        if (config == null) {
            failNearby(gen, "検索設定を構築できませんでした。データを更新してください")
            return
        }
        val pois = YolpClient.fetchNearby(config, centerLat, centerLon, radiusM = radiusM)
        if (pois == null) {
            failNearby(
                gen,
                "周辺のお店を取得できませんでした。地図サーバが混雑しているか、通信が不安定な可能性があります。少し時間をおいて再度お試しください。",
            )
            return
        }
        val today = LocalDate.now()
        val qrIds = settings.enabledQrPaymentIds
        // ブリッジ中チェーンの網羅リスト対象外で間引いた店(0 件表示の案内の出し分け用。#70)。
        // YOLP の同一店舗の重複登録を二重に数えないよう、重複排除と同じ「チェーン+支店名」で数える
        val ineligibleHidden = mutableSetOf<String>()
        // 開発者モード中だけ生 POI と照合・間引き結果を記録する(#70。設定→開発者向け→
        // 「取得した地図データ」で表示。OFF 時は記録しない=オーバーヘッドなし)
        val debugPois = if (settings.developerMode) mutableListOf<DebugPoi>() else null
        // 薄いピンで残す間引き店(#77)。通常ピンとは別リストに集め、重複排除は同じ基準で掛ける
        val hiddenPlaces = mutableListOf<NearbyPlace>()
        val places = pois
            .mapNotNull { poi ->
                fun record(matchLabel: String?, status: DebugPoiStatus) {
                    debugPois?.add(DebugPoi(poi.name, poi.lat, poi.lon, matchLabel, status))
                }
                val match = engine.matchStore(poi.name)
                if (match == null) {
                    record(null, DebugPoiStatus.NO_MATCH)
                    return@mapNotNull null
                }
                val merchant = match.merchant
                val matchLabel = merchant.name +
                    if (match.bannerId != merchant.id) "(${match.bannerName})" else ""
                if (engine.isFacilityTenant(match.bannerName, poi.name)) {
                    record(matchLabel, DebugPoiStatus.FACILITY_TENANT)
                    return@mapNotNull null
                }
                // POI は具体的な看板(業態)なので看板スコープで判定し、通常ピン / 薄いピン+理由 / 描かない
                // に分類する(基準は domain/StoreVisibility.kt。ユーザー登録の対象外ペア #63・網羅リストの
                // 店舗対象外 #64・公式対象外の店舗単位の間引きと、ブリッジ中の下見残しを含む)
                val visibility = engine.classifyStore(
                    merchant,
                    match.bannerId,
                    poi.name,
                    today,
                    qrIds,
                    settings.activeExcludedStorePairs,
                    settings.pointProgramMemberships,
                    previewMerchantIds = filterIds,
                )
                fun place(visible: List<CampaignJudgment>, result: JudgmentResult?, hiddenReason: HiddenReason?) =
                    NearbyPlace(
                        name = poi.name,
                        distanceMeters = GeoMath.distanceMeters(originLat, originLon, poi.lat, poi.lon),
                        distanceFromCenter = GeoMath.distanceMeters(centerLat, centerLon, poi.lat, poi.lon),
                        merchant = merchant,
                        bannerId = match.bannerId,
                        bestBenefit = result?.bestBenefitLabel(),
                        lat = poi.lat,
                        lon = poi.lon,
                        brandColors = visible.mapNotNull { it.brandColor }.distinct(),
                        hasTimeLimited = visible.any { it.campaign.isTimeLimited },
                        hiddenReason = hiddenReason,
                    )
                // ブリッジ中チェーンの網羅リスト外(0 件表示の案内の出し分け用。#70)
                fun countIneligibleHidden() {
                    if (merchant.id in filterIds) {
                        ineligibleHidden += "${merchant.id}:${engine.normalizedBranch(merchant, poi.name)}"
                    }
                }
                when (visibility) {
                    is StoreVisibility.Shown -> {
                        record(matchLabel, DebugPoiStatus.SHOWN)
                        // 並記枠(提示のみ)しか無い店も「特典あり」としてピンを出す
                        place(visibility.visible, visibility.result, hiddenReason = null)
                    }
                    is StoreVisibility.Hidden -> {
                        if (visibility.reason == HiddenReason.EXHAUSTIVE_INELIGIBLE) countIneligibleHidden()
                        record(matchLabel, visibility.reason.debugStatus())
                        hiddenPlaces += place(emptyList(), result = null, hiddenReason = visibility.reason)
                        null
                    }
                    is StoreVisibility.Dropped -> {
                        if (visibility.exhaustiveIneligible) countIneligibleHidden()
                        record(
                            matchLabel,
                            if (visibility.exhaustiveIneligible) DebugPoiStatus.EXHAUSTIVE_INELIGIBLE
                            else DebugPoiStatus.NO_JUDGMENT,
                        )
                        null
                    }
                }
            }
            .dedupSameStore(engine)
        val effectiveZoom = if (adaptZoom) {
            val nearCount = places.count { it.distanceFromCenter <= NEARBY_DENSE_RADIUS_M }
            if (nearCount < NEARBY_DENSE_THRESHOLD) NEARBY_WIDE_ZOOM else zoom
        } else {
            zoom
        }
        applyNearbyIfCurrent(
            gen,
            NearbyUi(
                places = places,
                hiddenPlaces = hiddenPlaces.dedupSameStore(engine),
                ineligibleHiddenCount = ineligibleHidden.size,
                rawPoiCount = pois.size,
                centerLat = centerLat,
                centerLon = centerLon,
                userLat = userLat,
                userLon = userLon,
                zoom = effectiveZoom,
                searchStamp = gen,
            ),
            debugPois,
        )
        resolveMunicipalNotice(gen, centerLat, centerLon)
    }

    /**
     * 開いている地図の判定由来フィールド(還元ラベル・ピン色・表示可否)を、新しいエンジン・設定で
     * 再計算する(VM の rebuild から呼ぶ)。対象外ペア(#63)の登録・解除や設定変更を、YOLP 再検索なし
     * (取得済みリストのメモリ内再計算)で開いている地図へ即反映するため。
     */
    fun recompute(nearby: MainViewModel.NearbyState, engine: JudgmentEngine, settings: AppSettings): MainViewModel.NearbyState =
        nearby.copy(
            search = nearby.search?.let { recomputeNearbyPlaces(it, engine, settings, nearby.merchantFilters) },
        )

    /**
     * 同一店舗の重複を排除する(YOLP は同じ店を別名・空白違いで複数返すことがある。
     * 例: 「KFC…店」と「ケンタッキーフライドチキン…店」、空白有無違いの同名)。
     * 「チェーン + 支店名」がともに一致するものを同一店舗とみなし、1件だけ残す。
     * 座標基準にしないのは、同一モール内に同チェーンの別店舗(例: レイクタウンの複数スタバ)が
     * 入る場合に誤って1件へ潰さないため(支店名が異なれば別物として残る)。
     * 残す1件は座標の辞書順で選ぶ。「最も近い1件」にすると、同一店舗が座標違いで重複登録
     * されている場合(例: リヴィンオズ大泉のドトール、施設実位置と住所ジオコード点が約44m差)に
     * 検索起点しだいで残る座標が入れ替わり、近接グルーピングの結果が検索のたびに揺れるため。
     * 並び順は地図中心からの距離(一覧のソート)。通常ピン・薄いピンの両リストに同じ基準で掛ける
     */
    private fun List<NearbyPlace>.dedupSameStore(engine: JudgmentEngine): List<NearbyPlace> =
        groupBy { p ->
            val m = p.merchant
            if (m == null) "?:${p.name}" else "${m.id}:${engine.normalizedBranch(m, p.name)}"
        }
            .map { (_, dups) -> dups.minWith(compareBy({ it.lat }, { it.lon }, { it.name })) }
            .sortedBy { it.distanceFromCenter }

    /** 薄いピンの理由を開発者向け一覧の間引き理由に写す */
    private fun HiddenReason.debugStatus(): DebugPoiStatus = when (this) {
        HiddenReason.OFFICIALLY_EXCLUDED -> DebugPoiStatus.OFFICIALLY_EXCLUDED
        HiddenReason.EXHAUSTIVE_INELIGIBLE -> DebugPoiStatus.EXHAUSTIVE_INELIGIBLE
        HiddenReason.USER_EXCLUDED -> DebugPoiStatus.USER_EXCLUDED
    }

    /**
     * 取得済みの店(通常ピン+薄いピン)を新しいエンジン・設定で再分類する(loadNearbyAround と同基準)。
     * 対象外ペア(#63)の登録/解除で通常ピン ⇄ 薄いピンの両方向に動く(薄いピン側も
     * メモリに持っているため、解除しても次の検索まで戻らない、ということが無い)。
     * 除外に関係なく判定が 0 件になった店(Dropped)は従来どおり消える。
     */
    private fun recomputeNearbyPlaces(
        nearby: NearbyUi,
        engine: JudgmentEngine,
        settings: AppSettings,
        filters: Set<NearbyLens>,
    ): NearbyUi {
        if (nearby.places.isEmpty() && nearby.hiddenPlaces.isEmpty()) return nearby
        val today = LocalDate.now()
        val qrIds = settings.enabledQrPaymentIds
        val filterIds = filters.map { it.merchant.id }.toSet()
        // 検索時(loadNearbyAround)に間引いた分に、この再計算で通常ピンから新たに間引いた分だけを積む
        // (薄いピンに留まる店は検索時に数え済み。次の検索でリセットされる)
        var ineligibleHidden = nearby.ineligibleHiddenCount
        val shown = mutableListOf<NearbyPlace>()
        val hidden = mutableListOf<NearbyPlace>()
        (nearby.places + nearby.hiddenPlaces).forEach { place ->
            val merchant = place.merchant ?: run { shown += place; return@forEach }
            val wasShown = place.hiddenReason == null
            fun countIneligibleHidden() {
                if (wasShown && merchant.id in filterIds) ineligibleHidden++
            }
            when (
                val v = engine.classifyStore(
                    merchant,
                    place.bannerId,
                    place.name,
                    today,
                    qrIds,
                    settings.activeExcludedStorePairs,
                    settings.pointProgramMemberships,
                    previewMerchantIds = filterIds,
                )
            ) {
                is StoreVisibility.Shown -> shown += place.copy(
                    bestBenefit = v.result.bestBenefitLabel(),
                    brandColors = v.visible.mapNotNull { it.brandColor }.distinct(),
                    hasTimeLimited = v.visible.any { it.campaign.isTimeLimited },
                    hiddenReason = null,
                )
                is StoreVisibility.Hidden -> {
                    if (v.reason == HiddenReason.EXHAUSTIVE_INELIGIBLE) countIneligibleHidden()
                    hidden += place.copy(
                        bestBenefit = null,
                        brandColors = emptyList(),
                        hasTimeLimited = false,
                        hiddenReason = v.reason,
                    )
                }
                is StoreVisibility.Dropped -> if (v.exhaustiveIneligible) countIneligibleHidden()
            }
        }
        shown.sortBy { it.distanceFromCenter }
        hidden.sortBy { it.distanceFromCenter }
        // プレビュー中の店は再計算後のインスタンスへ差し替え(通常⇄薄いの移動を含む)、消えた店ならプレビューを閉じる
        val selected = nearby.selectedPlace?.let { sp ->
            (shown + hidden).firstOrNull { it.name == sp.name && it.lat == sp.lat && it.lon == sp.lon }
        }
        return nearby.copy(
            places = shown,
            hiddenPlaces = hidden,
            selectedPlace = selected,
            ineligibleHiddenCount = ineligibleHidden,
        )
    }

    /**
     * 検索中心の所在自治体を解決し、開催中の自治体施策があれば地図のお知らせピルに反映する。
     * 検索完了ごとに1回だけリバースジオコーディングする(カメラ追従では呼ばない。境界付近の
     * チラつきと Geocoder 呼び出しの嵩みを避けるため、更新は「このエリアを検索」等の再検索単位)。
     * 参考情報なので、解決失敗・該当なしは黙って何もしない(エラーもスピナーも出さない)。
     */
    private fun resolveMunicipalNotice(gen: Int, lat: Double, lon: Double) {
        val engine = engine() ?: return
        val municipal = engine.activeCampaigns(LocalDate.now())
            .filter { it.campaignType == CampaignType.MUNICIPAL }
        if (municipal.isEmpty()) return // 施策が1件も無ければ Geocoder 自体を呼ばない
        scope.launch(Dispatchers.IO) {
            if (!Geocoder.isPresent()) return@launch
            val addr = try {
                reverseGeocode(Geocoder(app, Locale.JAPAN), lat, lon)
            } catch (e: Exception) {
                Timber.w(e, "検索中心のリバースジオコーディングに失敗")
                null
            } ?: return@launch
            val prefecture = addr.adminArea ?: return@launch
            // 市区町村候補は locality(東京23区・一般市)と subLocality(政令市の行政区)の両方を
            // 渡し、一致した施策をすべて載せる(市の施策と県全域施策の併催もひとつの詳細で見せる)
            val matched = municipalCampaignsForLocation(
                municipal, prefecture, listOfNotNull(addr.locality, addr.subLocality),
            )
            if (matched.isEmpty()) return@launch
            // ピルの文言: 県全域+市区町村の併催は「千葉県・千葉市」と併記(municipalRegionsLabel。
            // 施策詳細のタイトルと共用し、ピル「千葉市」/タイトル「千葉県」の食い違いを防ぐ)。
            // 単独ならより狭い単位を優先(市区町村があればそれ、県全域施策だけなら県名)
            val label = municipalRegionsLabel(matched)
                ?: matched.mapNotNull { it.region }.firstOrNull { !it.isPrefectureWide }?.name
                ?: prefecture
            state.update { st ->
                if (gen != nearbyGeneration) return@update st
                val search = st.nearby.search ?: return@update st
                st.copy(
                    nearby = st.nearby.copy(
                        search = search.copy(municipalNotice = MunicipalNotice(label, matched)),
                    ),
                )
            }
        }
    }

    private suspend fun reverseGeocode(geocoder: Geocoder, lat: Double, lon: Double): Address? {
        return if (Build.VERSION.SDK_INT >= 33) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocation(lat, lon, 1) { addresses ->
                    if (cont.isActive) cont.resume(addresses.firstOrNull())
                }
            }
        } else {
            @Suppress("DEPRECATION")
            geocoder.getFromLocation(lat, lon, 1)?.firstOrNull()
        }
    }

    /** 進行中の近隣取得が最新世代(タブ移動・再取得で破棄されていない)のときだけ結果を反映する */
    /** debugPois は非 null のときだけ更新する(null = 開発者モード OFF で未記録 → 既存を維持) */
    private fun applyNearbyIfCurrent(gen: Int, result: NearbyUi, debugPois: List<DebugPoi>? = null) {
        state.update {
            if (gen != nearbyGeneration) return@update it
            it.copy(
                nearby = it.nearby.copy(search = result),
                nearbyDebugPois = debugPois ?: it.nearbyDebugPois,
            )
        }
    }

    /**
     * 近隣取得の失敗。最新世代のときだけ反映する。既に地図(=結果の中心)が出ているなら内容は残して
     * loading だけ畳み、一時失敗は Snackbar で知らせる(まっさらにしない)。表示すべき内容が無い
     * 初回などは全画面エラー(再試行)にする。
     */
    private fun failNearby(gen: Int, message: String) {
        state.update {
            if (gen != nearbyGeneration) return@update it
            val prev = it.nearby.search
            if (prev?.centerLat != null && prev.centerLon != null) {
                it.copy(nearby = it.nearby.copy(search = prev.copy(loading = false), searchFailed = message))
            } else {
                it.copy(nearby = it.nearby.copy(search = NearbyUi(error = message)))
            }
        }
    }

    /** 近隣再検索失敗の Snackbar を表示し終えたら文言を消費する(同じ失敗を再表示しない) */
    fun onNearbySearchFailedShown() = state.update { it.copy(nearby = it.nearby.copy(searchFailed = null)) }

    fun onLocationDenied() {
        if (engine() == null) return
        val isInitial = state.value.nearby.search?.centerLat == null
        val message = "位置情報の許可が必要です。端末の設定からこのアプリに位置情報を許可してください"
        if (!isInitial) {
            failNearby(nearbyGeneration, message)
            return
        }
        val gen = ++nearbyGeneration
        state.update {
            val base = it.nearby.search ?: NearbyUi()
            it.copy(
                nearby = it.nearby.copy(
                    search = base.copy(
                        loading = true,
                        loadingPhase = NearbyLoadPhase.SEARCHING,
                        error = null,
                        selectedPlace = null,
                    ),
                    origin = null,
                    geocodeCandidates = emptyList(),
                    isGeocoding = false,
                ),
                selection = null,
                storeCheck = null,
            )
        }
        scope.launch(Dispatchers.IO) {
            fallbackToDefaultPlace(gen, message)
        }
    }

    /**
     * 位置情報が取れないとき、デフォルト地点(新宿駅)で地図を表示しつつ Snackbar で通知する。
     * 起点は origin にセットし、距離表示は「新宿駅から○○m」になる。
     */
    private fun fallbackToDefaultPlace(gen: Int, message: String) {
        val place = FALLBACK_PLACE
        state.update {
            if (gen != nearbyGeneration) return@update it
            it.copy(nearby = it.nearby.copy(origin = place, searchFailed = message))
        }
        showMapAt(gen, place.lat, place.lon, userLat = null, userLon = null)
        loadNearbyAround(
            gen, place.lat, place.lon,
            // 現在地は取れていないので捏造しない(青ドット非表示)。距離の起点はフォールバック地点
            userLat = null, userLon = null,
            originLat = place.lat, originLon = place.lon,
            radiusM = NEARBY_DEFAULT_RADIUS_M, zoom = NEARBY_DEFAULT_ZOOM,
            adaptZoom = true,
        )
    }

    fun onCloseNearby() {
        // ブリッジ経由なら、戻る操作でブリッジ元(施策詳細/判定詳細)へ復帰する
        val st = state.value
        if (st.campaignBridgeReturn != null || st.selectionBridgeReturn != null) {
            nearbyGeneration++
            state.update {
                it.copy(
                    selectedTab = if (it.campaignBridgeReturn != null) AppTab.CAMPAIGNS else AppTab.SEARCH,
                    selectedCampaignGroup = it.campaignBridgeReturn,
                    selection = it.selectionBridgeReturn,
                    campaignBridgeReturn = null,
                    selectionBridgeReturn = null,
                    nearby = it.nearby.cleared().copy(merchantFilters = emptySet()),
                )
            }
            return
        }
        selectTab(AppTab.SEARCH)
    }

    /**
     * 一覧の行/地図のピンをタップ → 全画面遷移せず「選択中」にする。
     * 地図はこの店にセンタリングしピンを強調、ボトムシートは店舗プレビューに切り替わる。
     * 判定詳細へはプレビューの導線(onSelectNearby)から進む。
     */
    fun onPreviewNearby(place: NearbyPlace) {
        state.update { st ->
            val search = st.nearby.search ?: return@update st
            var s = st.copy(nearby = st.nearby.copy(search = search.copy(selectedPlace = place)))
            // 詳細サイドシート(#57)表示中のピンタップは、プレビューだけ差し替えるとシートの詳細と
            // 地図の選択が食い違うため、シートの詳細ごとその店に差し替える。縦画面では詳細表示中に
            // ピンは押せない(全画面オーバーレイが地図を覆う)ので、この分岐は横画面のシートでしか効かない
            val detailOpen = st.selection != null || st.storeCheck != null ||
                st.selectedCampaignGroup != null
            val merchant = place.merchant
            if (detailOpen && merchant != null) {
                // 薄いピン(#77)の公式対象外・網羅リスト外は判定詳細でなく店舗判定(根拠)に差し替える
                // (判定詳細は公式対象外を判定に反映しないため、対象のように見えてしまう)
                val check = storeCheckFor(place)
                if (check != null) {
                    s = s.copy(selection = null, storeCheck = check, selectedCampaignGroup = null)
                } else {
                    selectionFor(merchant, place.name, place.name, place.bannerId)?.let { sel ->
                        s = s.withSelection(sel)
                    }
                }
            }
            s
        }
    }

    /**
     * 薄いピン(#77)のプレビュー「根拠を確認」→ 店舗判定画面を単独で開く(公式リストの一致箇所・出典が見える)。
     * 判定詳細を下に積まないのは、判定詳細が公式対象外を反映せず対象のように見えてしまうため。
     * 戻る/✕ で地図(プレビュー)へ戻る。
     */
    fun onOpenNearbyStoreCheck(place: NearbyPlace) {
        val check = storeCheckFor(place) ?: return
        state.update { it.copy(selection = null, storeCheck = check, selectedCampaignGroup = null) }
    }

    /** 公式対象外・網羅リスト外の薄いピンに対する店舗判定の状態。それ以外(通常ピン・登録対象外)は null */
    private fun storeCheckFor(place: NearbyPlace): MainViewModel.StoreCheckState? {
        val merchant = place.merchant ?: return null
        val engine = engine() ?: return null
        val byOfficialList = when (place.hiddenReason) {
            HiddenReason.OFFICIALLY_EXCLUDED, HiddenReason.EXHAUSTIVE_INELIGIBLE -> true
            HiddenReason.USER_EXCLUDED, null -> false
        }
        if (!byOfficialList || !engine.canCheckStore(merchant)) return null
        return MainViewModel.StoreCheckState(
            merchant = merchant,
            input = place.name,
            verdicts = engine.checkStore(merchant, place.name),
        )
    }

    /**
     * 地図タブの詳細サイドシート(#57)を閉じる。クラスタ/複合ピンのタップでグループリストを
     * 右ペインに出すとき、シートが上に残ると隠れて見えないため UI 側から呼ばれる。
     */
    fun onCloseNearbyDetail() {
        state.update {
            it.copy(selection = null, storeCheck = null, selectedCampaignGroup = null)
        }
    }

    /** プレビューを閉じて一覧表示に戻す(× / 戻る)。退避していた選択時ズームも捨てる */
    fun onClearNearbyPreview() {
        state.update { st ->
            val search = st.nearby.search ?: return@update st
            if (search.selectedPlace == null) return@update st
            st.copy(nearby = st.nearby.copy(search = search.copy(selectedPlace = null, selectionZoom = null)))
        }
    }

    /**
     * 選択中(プレビュー)のカメラズームの記録(NearbyMap がカメラ停止時に報告)。
     * 判定詳細(全画面)から戻って地図が作り直されたとき、選択時の見え方に復元するために持つ
     * (NearbyUi.selectionZoom)。選択が無いときの報告(解除との競合)は捨てる。
     */
    fun onSelectionZoomChanged(zoom: Double) {
        state.update { st ->
            val search = st.nearby.search ?: return@update st
            if (search.selectedPlace == null || search.selectionZoom == zoom) return@update st
            st.copy(nearby = st.nearby.copy(search = search.copy(selectionZoom = zoom)))
        }
    }

    /** プレビューから判定詳細へ → POI名を店舗対象判定のプリフィルと画面タイトルに引き継ぐ */
    fun onSelectNearby(place: NearbyPlace) {
        val merchant = place.merchant ?: return
        val selection = selectionFor(merchant, place.name, place.name, place.bannerId) ?: return
        state.update { it.withSelection(selection) }
    }

    /**
     * 「地図」のジャンル絞り込みトグル。表示集合の絞り込みは UI 側で nearby の places に対して行う
     * クライアントフィルタなので、ここでは選択集合を更新するだけ(YOLP 再取得しない)。
     * チップは一覧表示時のみ出る(プレビュー中は出ない)ため selectedPlace は触らない。
     */
    fun onToggleNearbyCategory(category: String) {
        state.update {
            val selected = if (category in it.nearby.selectedCategories) {
                it.nearby.selectedCategories - category
            } else {
                it.nearby.selectedCategories + category
            }
            it.copy(nearby = it.nearby.copy(selectedCategories = selected))
        }
    }

    /**
     * 「地図」のお店絞り込みのトグル(在チェーンのピッカーのチェック/ピルの×=レンズ2段目)。
     * ジャンル選択は残したまま(全ピルを解除すると元のジャンル絞り込みに戻る=ドリルダウンの体験)。
     * 同一系列のグループレンズと業態レンズは重ねない(グループを選んだら業態を外し、
     * 業態を選んだらグループを外す=広げ直し/絞り直しの意図に追従する)。
     */
    fun onToggleNearbyLens(lens: NearbyLens) {
        state.update {
            val current = it.nearby.merchantFilters
            val next = if (current.any { l -> l.sameTarget(lens) }) {
                current.filterNot { l -> l.sameTarget(lens) }.toSet()
            } else {
                val cleaned = if (lens.bannerId == null) {
                    current.filterNot { l -> l.merchant.id == lens.merchant.id }
                } else {
                    current.filterNot { l -> l.merchant.id == lens.merchant.id && l.bannerId == null }
                }
                cleaned.toSet() + lens
            }
            it.copy(nearby = it.nearby.copy(merchantFilters = next))
        }
    }

    /**
     * ブリッジ: 判定詳細(探す由来)の「近くのこのお店を探す」は単一チェーン、おトクタブの施策詳細
     * (「近くの対象店舗を探す」)は 1〜N チェーン。そのチェーン群に絞った状態を作り、
     * タブを NEARBY に切り替えて元の画面を閉じる。実際の「地図」突入(位置情報パーミッション→fetchNearby)は
     * UI 側が続けて行う。ジャンル絞り込みはクリアしてチェーンに集中する(ピル解除で全件に戻る)。
     * 閉じた判定詳細は selectionBridgeReturn に保存し、地図タブの戻る操作で判定詳細へ復帰できるようにする。
     */
    fun onFindNearby(merchant: Merchant) {
        val returnSelection = state.value.selection
        // 業態としての判定詳細(POI・看板ヒット由来)から探すときは、その業態レンズを引き継ぐ
        val bannerId = returnSelection?.takeIf { it.merchant.id == merchant.id }?.bannerId
        onFindNearby(setOf(NearbyLens(merchant, bannerId)))
        state.update { it.copy(selectionBridgeReturn = returnSelection) }
    }

    /**
     * 施策詳細から: merchant_rules の merchant_id 群を解決してブリッジする(解決できた分だけ)。
     * location_hint 持ち(自販機等)は地図で探せないので除く。看板スコープ(banner_ids /
     * ineligible_banner_ids)のあるルールは対象業態のレンズへ展開し、対象外業態のピンに飛ばさない。
     * 閉じる施策詳細を campaignBridgeReturn に保存し、地図タブの戻る操作で施策詳細へ復帰できるようにする。
     */
    fun onFindNearbyByIds(merchantIds: Collection<String>) {
        val idSet = merchantIds.toSet()
        // 合成 Merchant(カスタムキャンペーンの自由入力店名)からもブリッジできるよう統合データで引く
        val merchants = displayData()?.merchants.orEmpty()
            .filter { it.id in idSet && it.locationHint == null }
        if (merchants.isEmpty()) return
        // 表示中の施策詳細(グループ)のルールから看板スコープを引く(ブリッジはこの画面からしか呼ばれない)
        val rulesById = state.value.selectedCampaignGroup.orEmpty()
            .flatMap { it.campaign.merchantRules }
            .groupBy { it.merchantId }
        val lenses = merchants.flatMap { m -> lensesFor(m, rulesById[m.id].orEmpty()) }.toSet()
        val returnGroup = state.value.selectedCampaignGroup
        onFindNearby(lenses)
        state.update { it.copy(campaignBridgeReturn = returnGroup) }
    }

    /**
     * merchant 1 件ぶんのブリッジ用レンズ。看板スコープの無いルールが 1 つでもあれば
     * グループ全体(bannerId = null)。スコープ付きのみなら対象業態のレンズへ展開し、
     * 結果的に全業態が対象ならグループレンズ 1 個に畳む(ピルを増やさない)。
     */
    private fun lensesFor(merchant: Merchant, rules: List<MerchantRule>): List<NearbyLens> {
        if (rules.isEmpty() || rules.any { it.bannerIds.isEmpty() && it.ineligibleBannerIds.isEmpty() }) {
            return listOf(NearbyLens(merchant))
        }
        val allowed = rules.flatMap { rule ->
            if (rule.bannerIds.isNotEmpty()) rule.bannerIds
            else merchant.allBannerIds - rule.ineligibleBannerIds.toSet()
        }.distinct().filter { merchant.bannerName(it) != null }
        return when {
            allowed.isEmpty() -> listOf(NearbyLens(merchant))
            allowed.toSet() == merchant.allBannerIds.toSet() -> listOf(NearbyLens(merchant))
            else -> allowed.map { NearbyLens(merchant, it) }
        }
    }

    private fun onFindNearby(lenses: Set<NearbyLens>) {
        val prev = state.value.selectedTab
        if (prev == AppTab.NEARBY) nearbyGeneration++
        state.update { st ->
            // 地図タブ上でのブリッジ(サイドシートの施策詳細発)は取得済みの地図を破棄して取り直す
            val base = if (prev == AppTab.NEARBY) st.nearby.cleared() else st.nearby
            st.copy(
                selectedTab = AppTab.NEARBY,
                nearby = base.copy(merchantFilters = lenses, selectedCategories = emptySet()),
                selection = null,
                storeCheck = null,
                selectedCampaignGroup = null,
                // 新しいブリッジは古い復元先を無効化する(呼び出し元の public 関数が自分の復元先を上書き保存する)
                campaignBridgeReturn = null,
                selectionBridgeReturn = null,
            )
        }
    }

    // --- 地名検索(起点コントロール) ---

    private suspend fun geocodeQuery(geocoder: Geocoder, query: String): List<Address> {
        return if (Build.VERSION.SDK_INT >= 33) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocationName(query, 5) { addresses ->
                    if (cont.isActive) cont.resume(addresses)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            geocoder.getFromLocationName(query, 5) ?: emptyList()
        }
    }

    fun onGeocode(query: String) {
        if (query.isBlank()) {
            state.update { it.copy(nearby = it.nearby.copy(geocodeCandidates = emptyList(), isGeocoding = false)) }
            return
        }
        state.update { it.copy(nearby = it.nearby.copy(isGeocoding = true, geocodeCandidates = emptyList())) }
        scope.launch(Dispatchers.IO) {
            if (!Geocoder.isPresent()) {
                state.update { it.copy(nearby = it.nearby.copy(isGeocoding = false)) }
                return@launch
            }
            val geocoder = Geocoder(app, Locale.JAPAN)
            val candidates = try {
                val primary = geocodeQuery(geocoder, query)
                val stationSuffix = "駅"
                val extra = if (!query.endsWith(stationSuffix)) {
                    geocodeQuery(geocoder, query + stationSuffix)
                } else {
                    emptyList()
                }
                val seen = mutableSetOf<Pair<Double, Double>>()
                (primary + extra).mapNotNull { addr ->
                    val key = Pair(
                        (addr.latitude * 1e5).toLong() / 1e5,
                        (addr.longitude * 1e5).toLong() / 1e5,
                    )
                    if (!seen.add(key)) return@mapNotNull null
                    val rawName = addr.featureName
                        ?: addr.getAddressLine(0)?.split(",")?.firstOrNull()?.trim()
                        ?: return@mapNotNull null
                    // featureName が番地(数字+ハイフン)や国名なら施設名ではない
                    val useFullAddress = addr.featureName?.all { c ->
                        c in '0'..'9' || c in '０'..'９' || c == '-' || c == '−' || c == 'ー'
                    } == true || addr.featureName == addr.countryName
                    val componentAddress = buildString {
                        addr.adminArea?.let { append(it) }
                        addr.locality?.let { if (it != addr.adminArea) append(it) }
                        addr.subLocality?.let { append(it) }
                        addr.thoroughfare?.let { append(it) }
                        addr.subThoroughfare?.let { append(it) }
                    }
                    // 番地等の場合のみ getAddressLine(0) を優先(施設名付き住所の混入を避ける)
                    val fullAddress = if (useFullAddress) {
                        val addressLine = addr.getAddressLine(0)
                            ?.replace(Regex("^日本[、,]\\s*"), "")
                            ?.replace(Regex("〒[\\S]+\\s*"), "")
                            ?.trim()
                        if (addressLine != null && addressLine.length > componentAddress.length)
                            addressLine
                        else componentAddress
                    } else {
                        componentAddress
                    }
                    val name = if (useFullAddress && fullAddress.isNotBlank()) {
                        fullAddress
                    } else {
                        rawName
                    }
                    GeocodedPlace(name, fullAddress, addr.latitude, addr.longitude)
                }
            } catch (_: Exception) {
                emptyList()
            }
            state.update { it.copy(nearby = it.nearby.copy(geocodeCandidates = candidates, isGeocoding = false)) }
        }
    }

    fun onSelectGeocodedPlace(place: GeocodedPlace) {
        if (engine() == null) return
        val prev = state.value.nearby.search
        val userLat = prev?.userLat
        val userLon = prev?.userLon
        val gen = ++nearbyGeneration
        state.update {
            val base = it.nearby.search ?: NearbyUi()
            it.copy(
                nearby = it.nearby.copy(
                    origin = place,
                    geocodeCandidates = emptyList(),
                    isGeocoding = false,
                    search = base.copy(
                        loading = true,
                        loadingPhase = NearbyLoadPhase.SEARCHING,
                        error = null,
                        selectedPlace = null,
                    ),
                ),
            )
        }
        scope.launch(Dispatchers.IO) {
            loadNearbyAround(
                gen, place.lat, place.lon,
                userLat ?: place.lat, userLon ?: place.lon,
                place.lat, place.lon,
                radiusM = NEARBY_DEFAULT_RADIUS_M, zoom = NEARBY_DEFAULT_ZOOM,
                adaptZoom = true,
            )
        }
    }

    /** 起点を GPS に戻す(検索バーの✕)。カメラは動かさず距離だけ現在地基準で再計算する */
    fun onClearOrigin() {
        state.update { st ->
            val n = st.nearby.search
                ?: return@update st.copy(nearby = st.nearby.copy(origin = null, geocodeCandidates = emptyList()))
            val gpsLat = n.userLat
            val gpsLon = n.userLon
            if (gpsLat == null || gpsLon == null) {
                return@update st.copy(nearby = st.nearby.copy(origin = null, geocodeCandidates = emptyList()))
            }
            val recalculated = n.places.map { p ->
                p.copy(distanceMeters = GeoMath.distanceMeters(gpsLat, gpsLon, p.lat, p.lon))
            }.sortedBy { it.distanceFromCenter }
            st.copy(
                nearby = st.nearby.copy(
                    origin = null,
                    geocodeCandidates = emptyList(),
                    search = n.copy(places = recalculated),
                ),
            )
        }
    }

    fun onDismissGeocoding() {
        state.update { it.copy(nearby = it.nearby.copy(geocodeCandidates = emptyList(), isGeocoding = false)) }
    }
}
