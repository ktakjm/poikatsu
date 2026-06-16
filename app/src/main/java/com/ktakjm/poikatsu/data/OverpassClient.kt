package com.ktakjm.poikatsu.data

import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/** OpenStreetMap の POI(店舗) */
data class Poi(
    val name: String,
    val branch: String?,
    val brand: String?,
    val lat: Double,
    val lon: Double,
) {
    /** 日本のOSMは支店名を branch タグに分ける慣習のため、表示用に結合する */
    val displayName: String
        get() = if (branch.isNullOrBlank()) name else "$name $branch"
}

/** Overpass API(OSM)で周辺の飲食店・コンビニ・スーパーを検索する。無料・APIキー不要 */
object OverpassClient {

    private const val TAG = "OverpassClient"
    private const val USER_AGENT = "poikatsu/1.0 (personal use; https://github.com/ktakjm/poikatsu)"
    private const val MAX_RESULTS = 800

    // overpass-api.de は混雑時に 429/504/タイムアウトを返すことが多いため、
    // 失敗したらミラーへフォールバックする(上から順に試す)。
    private val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class OverpassResponse(val elements: List<Element> = emptyList())

    @Serializable
    private data class Element(
        val lat: Double? = null,
        val lon: Double? = null,
        val center: Center? = null,
        val tags: Map<String, String> = emptyMap(),
    )

    @Serializable
    private data class Center(val lat: Double, val lon: Double)

    /**
     * 周辺の飲食店・コンビニ・スーパーをカテゴリタグで取得する(チェーン判定はクライアント側)。
     * 注: Overpass の日本語名regex検索は遅すぎて使えないため、サーバ側の名前絞り込みはしない。
     * 広域(>1km)は速度優先で node のみ取得(建物ポリゴンとして登録された店は落ちる)。
     * 都心部の広域検索は件数上限により取りこぼす場合がある。失敗時は null。
     */
    fun fetchNearby(lat: Double, lon: Double, radiusM: Int): List<Poi>? {
        val query = buildQuery(lat, lon, radiusM)
        for (endpoint in ENDPOINTS) {
            val result = runCatching { requestPois(endpoint, query) }
                .getOrElse { e ->
                    Log.w(TAG, "Overpass request error: $endpoint", e)
                    null
                }
            if (result != null) return result // 0件成功(emptyList)はここで返る。null は次のエンドポイントへ
        }
        return null
    }

    private fun buildQuery(lat: Double, lon: Double, radiusM: Int): String {
        val kinds = if (radiusM > 1000) listOf("node") else listOf("node", "way")
        val filters = kinds.flatMap { kind ->
            listOf(
                """$kind(around:$radiusM,$lat,$lon)["amenity"~"fast_food|restaurant|cafe|food_court"];""",
                """$kind(around:$radiusM,$lat,$lon)["shop"~"convenience|supermarket"];""",
            )
        }.joinToString("\n  ")
        return """
            [out:json][timeout:25];
            (
              $filters
            );
            out center qt $MAX_RESULTS;
        """.trimIndent()
    }

    /** 1エンドポイントに問い合わせる。非2xx/空ボディは null(=フォールバック対象)、成功は List(空もあり得る) */
    private fun requestPois(endpoint: String, query: String): List<Poi>? {
        val request = Request.Builder()
            .url(endpoint)
            .header("User-Agent", USER_AGENT)
            .post(FormBody.Builder().add("data", query).build())
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "Overpass HTTP ${response.code} from $endpoint")
                return null
            }
            val body = response.body?.string() ?: return null
            return parse(body)
        }
    }

    /** Overpass の JSON レスポンスを Poi に変換(node は lat/lon、way は center を持つ) */
    fun parse(body: String): List<Poi> =
        json.decodeFromString<OverpassResponse>(body).elements.mapNotNull { element ->
            val name = element.tags["name"] ?: return@mapNotNull null
            val lat = element.lat ?: element.center?.lat ?: return@mapNotNull null
            val lon = element.lon ?: element.center?.lon ?: return@mapNotNull null
            Poi(
                name = name,
                branch = element.tags["branch"],
                brand = element.tags["brand"],
                lat = lat,
                lon = lon,
            )
        }
}
