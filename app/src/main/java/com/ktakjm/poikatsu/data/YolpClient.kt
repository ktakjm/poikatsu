package com.ktakjm.poikatsu.data

import com.ktakjm.poikatsu.BuildConfig
import com.ktakjm.poikatsu.util.GeoMath
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * YOLP(Yahoo! ローカルサーチAPI)で周辺の店舗を取得する。現在の既定データ源。
 *
 * 規約遵守(詳細は docs/map-data-stack.md):
 * - 取得結果を**永続キャッシュしない**(メモリ保持のみ)。Room/DataStore/ファイルに書かない。
 * - アプリ下部に **「Web Services by Yahoo! JAPAN」クレジット**を表示する(UI 側で実装)。
 * - 1 アプリ 1 日 5 万リクエストまで無料(全ユーザー合算)。
 * - 地図描画系 API/SDK は 2020 年に廃止済みのため、データ取得のみに使う(描画は Google Maps)。
 *
 * 取りこぼし対策(駅前など密集地):
 * - YOLP は 1 リクエスト最大 100 件。**業種コード(gc)で対象業種に絞り**、さらに **start でページング**して
 *   100 件超を取得することで、半径内の対象チェーンを取りこぼさないようにする。
 * - gc に入らない専門業種(ジム等)は**店名キーワード(query)で個別取得**する。
 *
 * 密度差の偏り対策(ズームアウト時):
 * - 各ソース(gc/キーワード)は近い順(sort=dist)で最大 MAX_PAGES×PER_PAGE 件まで。高密度ソース(グルメ/コンビニ)は
 *   この上限で中心付近に打ち切られ、疎なキーワード(カーブス等)だけが半径いっぱいに広がる → 周縁が疎チェーンばかりになる。
 * - そこで**上限に達したソースの最遠距離の最小値を共通カバー半径**とし、全ソースをその外側で切り捨てて密度を揃える
 *   (mergeAndClip)。打ち切りソースが無ければ切り捨てない。
 *
 * appid は `BuildConfig.YOLP_APP_ID`(local.properties → build.gradle.kts 経由)から読む。
 */
object YolpClient {

    private const val ENDPOINT = "https://map.yahooapis.jp/search/local/V1/localSearch"

    private const val PER_PAGE = 100 // YOLP の results 上限(1 リクエストの最大件数)
    // キーワードソースの既定最大ページ数。gc_group は個別の maxPages を持つ。
    private const val MAX_PAGES = 5

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class YolpResponse(
        @SerialName("Feature") val features: List<Feature> = emptyList(),
    )

    @Serializable
    private data class Feature(
        @SerialName("Name") val name: String? = null,
        @SerialName("Geometry") val geometry: Geometry? = null,
    )

    @Serializable
    private data class Geometry(
        // "経度,緯度"(lon,lat)の順。例: "139.701,35.658"
        @SerialName("Coordinates") val coordinates: String? = null,
    )

    /**
     * 中心(lat/lon)から半径 radiusM 内の店舗を近い順に取得する。失敗時は null(部分成功は成功扱い)。
     * 検索対象(gc/keyword)は [config] でデータ駆動的に決定する。
     */
    fun fetchNearby(config: YolpSearchConfig, lat: Double, lon: Double, radiusM: Int): List<Poi>? {
        val appId = BuildConfig.YOLP_APP_ID
        if (appId.isBlank()) {
            Timber.w("YOLP_APP_ID 未設定。local.properties に YOLP_APP_ID を記入してください")
            return null
        }
        val distKm = (radiusM / 1000.0).coerceIn(0.1, 20.0)

        val sources = ArrayList<SourceResult>()
        var anySuccess = false

        // gc グループごと + キーワードごとに(それぞれページングして)取得する。
        fun collect(res: SourceResult?) {
            if (res == null) return
            anySuccess = true
            sources.add(res)
        }
        for (group in config.gcGroups) {
            collect(fetchPaged(appId, lat, lon, distKm, gc = group.gc, query = null, maxPages = group.maxPages))
        }
        for (kw in config.keywordQueries) {
            collect(fetchPaged(appId, lat, lon, distKm, gc = null, query = kw))
        }

        // 全リクエストが失敗(1 件も成功しない)のときだけ失敗扱い。部分成功は返す。
        // 座標+名前で重複排除し、密度差で生じる周縁の偏りを共通カバー半径で抑える(mergeAndClip)。
        return if (anySuccess) mergeAndClip(lat, lon, sources) else null
    }

    /** 1 ソース(gc/キーワード)の取得結果。truncated=true は上限に達し最遠点より外を取りこぼしていることを示す。 */
    internal data class SourceResult(val pois: List<Poi>, val truncated: Boolean)

    /**
     * 各ソースの結果を座標+名前で重複排除しつつマージする。上限(MAX_PAGES×PER_PAGE)に達したソースは
     * 最遠点より外を取りこぼしているので、**打ち切りソースの最遠距離の最小値**を共通カバー半径とし、
     * その外側の POI を全ソースから切り捨てて密度を揃える(高密度ソースが中心で打ち切られ、疎チェーンだけ
     * 周縁に残る偏りを防ぐ)。打ち切りソースが無ければ切り捨てない。純粋関数(テスト用に internal 公開)。
     */
    internal fun mergeAndClip(lat: Double, lon: Double, sources: List<SourceResult>): List<Poi> {
        val clipRadius = sources
            .filter { it.truncated }
            .mapNotNull { src -> src.pois.maxOfOrNull { GeoMath.distanceMeters(lat, lon, it.lat, it.lon) } }
            .minOrNull()

        val all = ArrayList<Poi>()
        val seen = HashSet<String>()
        for (src in sources) {
            for (p in src.pois) {
                if (clipRadius != null && GeoMath.distanceMeters(lat, lon, p.lat, p.lon) > clipRadius) continue
                if (seen.add("${p.lat},${p.lon},${p.name}")) all.add(p)
            }
        }
        return all
    }

    /** 1 ソース(gc または query)を start ページングで取得。1 ページ目が失敗したら null、以降の失敗は取得済みを返す。 */
    private fun fetchPaged(
        appId: String,
        lat: Double,
        lon: Double,
        distKm: Double,
        gc: String?,
        query: String?,
        maxPages: Int = MAX_PAGES,
    ): SourceResult? {
        val label = gc?.let { "gc=$it" } ?: "q=$query"
        val out = ArrayList<Poi>()
        var anyPage = false
        var truncated = false
        for (page in 0 until maxPages) {
            val start = 1 + page * PER_PAGE // YOLP の start は 1 始まり
            val pagePois = runCatching { requestPage(appId, lat, lon, distKm, gc, query, start) }
                .getOrElse { e ->
                    Timber.w(e, "YOLP request error ($label start=$start)")
                    null
                }
            if (pagePois == null) return if (anyPage) SourceResult(out, truncated) else null
            anyPage = true
            out.addAll(pagePois)
            if (pagePois.size < PER_PAGE) break // これ以上ページが無い
            if (page == maxPages - 1) {
                truncated = true
                Timber.i("YOLP $label が上限 ${maxPages * PER_PAGE} 件に到達。さらに遠方は取りこぼしの可能性")
            }
        }
        return SourceResult(out, truncated)
    }

    /** 1 ページ分を問い合わせる。非 2xx / 空ボディは null、成功は List(空もあり得る) */
    private fun requestPage(
        appId: String,
        lat: Double,
        lon: Double,
        distKm: Double,
        gc: String?,
        query: String?,
        start: Int,
    ): List<Poi>? {
        val url = ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("appid", appId)
            .addQueryParameter("lat", lat.toString())
            .addQueryParameter("lon", lon.toString())
            .addQueryParameter("dist", distKm.toString())
            .addQueryParameter("results", PER_PAGE.toString())
            .addQueryParameter("start", start.toString())
            .addQueryParameter("sort", "dist")
            .addQueryParameter("output", "json")
            .apply {
                if (gc != null) addQueryParameter("gc", gc)
                if (query != null) addQueryParameter("query", query)
            }
            .build()
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.w("YOLP HTTP %d", response.code)
                return null
            }
            val body = response.body?.string() ?: return null
            return parse(body)
        }
    }

    /** YOLP localSearch の JSON を Poi に変換。Name と Coordinates(lon,lat)が揃う Feature のみ採用 */
    fun parse(body: String): List<Poi> =
        json.decodeFromString<YolpResponse>(body).features.mapNotNull { f ->
            val name = f.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val coords = f.geometry?.coordinates?.split(",") ?: return@mapNotNull null
            if (coords.size < 2) return@mapNotNull null
            val lon = coords[0].trim().toDoubleOrNull() ?: return@mapNotNull null
            val lat = coords[1].trim().toDoubleOrNull() ?: return@mapNotNull null
            Poi(name = name, lat = lat, lon = lon)
        }
}
