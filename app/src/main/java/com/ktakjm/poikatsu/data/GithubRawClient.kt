package com.ktakjm.poikatsu.data

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/** GitHub リポジトリの data/ 配下を raw 配信で取得する */
object GithubRawClient {

    private const val BASE_URL_PREFIX = "https://raw.githubusercontent.com/ktakjm/poikatsu/"
    private const val BASE_URL_SUFFIX = "/data/"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** オフライン・HTTPエラー等はすべて null(呼び出し側でローカルにフォールバック) */
    fun fetch(fileName: String, ref: String = "main"): String? = runCatching {
        val url = BASE_URL_PREFIX + ref + BASE_URL_SUFFIX + fileName
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    }.getOrNull()
}
