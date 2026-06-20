package com.ktakjm.poikatsu.data

import android.util.Log
import com.ktakjm.poikatsu.BuildConfig
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

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
 * appid は `BuildConfig.YOLP_APP_ID`(local.properties → build.gradle.kts 経由)から読む。
 */
object YolpClient {

    private const val TAG = "YolpClient"
    private const val ENDPOINT = "https://map.yahooapis.jp/search/local/V1/localSearch"

    private const val PER_PAGE = 100 // YOLP の results 上限(1 リクエストの最大件数)
    // 1 ソース(業種/キーワード)あたりの最大ページ数。密集地でこれを超えると遠方を取りこぼすため、到達時はログに出す。
    private const val MAX_PAGES = 5

    // 業種コード(gc)。上位コードを指定すると配下の中・小分類も含まれる(実機+実 API で確認済み)。
    //   01   = グルメ全般(レストラン/カフェ/ファストフード/ファミレス/寿司)
    //   0205 = スーパー(0205002)+ コンビニ(0205001)。02 全体は広すぎて 100 件にスーパーが埋もれるため、
    //          スーパー/コンビニはこの精密コードで取る(02 を使うと 100 円ショップ等も混ざり誤マッチの原因にもなる)。
    private val GENRE_CODES = listOf("01", "0205")

    // gc(01/0205)で確実に取れないチェーンは店名キーワードで個別取得する(疎なので 1 リクエストで足りる)。
    //   カーブス        : フィットネス(0405003)で 01/0205 に入らない
    //   アカチャンホンポ: 店舗ごとにジャンルコードがバラバラ(衣料品店/ベビー用品/…)で gc 絞りが不安定
    //   オーケー        : YOLP 上でジャンルコードが空のため、どの gc でも返らない
    // matchStore で最終的に対象チェーンへ絞るので、キーワードが多少余分を拾っても害は小さい。
    private val KEYWORD_QUERIES = listOf("カーブス", "アカチャンホンポ", "オーケー")

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
     * OverpassClient.fetchNearby と同一シグネチャで、呼び出し側(ViewModel)は無変更で差し替え可能。
     */
    fun fetchNearby(lat: Double, lon: Double, radiusM: Int): List<Poi>? {
        val appId = BuildConfig.YOLP_APP_ID
        if (appId.isBlank()) {
            Log.w(TAG, "YOLP_APP_ID 未設定。local.properties に YOLP_APP_ID を記入してください")
            return null
        }
        // YOLP の dist はキロメートル(最大 20、0 不可)。半径は 500m〜3km なので 0.5〜3.0。
        val distKm = (radiusM / 1000.0).coerceIn(0.1, 20.0)

        val all = ArrayList<Poi>()
        val seen = HashSet<String>()
        var anySuccess = false

        // 業種コードごと + キーワードごとに(それぞれページングして)取得し、座標+名前で重複排除してマージする。
        fun collect(pois: List<Poi>?) {
            if (pois == null) return
            anySuccess = true
            for (p in pois) if (seen.add("${p.lat},${p.lon},${p.name}")) all.add(p)
        }
        for (gc in GENRE_CODES) collect(fetchPaged(appId, lat, lon, distKm, gc = gc, query = null))
        for (kw in KEYWORD_QUERIES) collect(fetchPaged(appId, lat, lon, distKm, gc = null, query = kw))

        // 全リクエストが失敗(1 件も成功しない)のときだけ失敗扱い。部分成功は返す。
        return if (anySuccess) all else null
    }

    /** 1 ソース(gc または query)を start ページングで取得。1 ページ目が失敗したら null、以降の失敗は取得済みを返す。 */
    private fun fetchPaged(
        appId: String,
        lat: Double,
        lon: Double,
        distKm: Double,
        gc: String?,
        query: String?,
    ): List<Poi>? {
        val label = gc?.let { "gc=$it" } ?: "q=$query"
        val out = ArrayList<Poi>()
        var anyPage = false
        for (page in 0 until MAX_PAGES) {
            val start = 1 + page * PER_PAGE // YOLP の start は 1 始まり
            val pagePois = runCatching { requestPage(appId, lat, lon, distKm, gc, query, start) }
                .getOrElse { e ->
                    Log.w(TAG, "YOLP request error ($label start=$start)", e)
                    null
                }
            if (pagePois == null) return if (anyPage) out else null
            anyPage = true
            out.addAll(pagePois)
            if (pagePois.size < PER_PAGE) break // これ以上ページが無い
            if (page == MAX_PAGES - 1) {
                Log.i(TAG, "YOLP $label が上限 ${MAX_PAGES * PER_PAGE} 件に到達。さらに遠方は取りこぼしの可能性")
            }
        }
        return out
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
                Log.w(TAG, "YOLP HTTP ${response.code}")
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
            // YOLP は支店名を Name に内包する(branch を分けない)ので branch/brand は null
            Poi(name = name, branch = null, brand = null, lat = lat, lon = lon)
        }
}
