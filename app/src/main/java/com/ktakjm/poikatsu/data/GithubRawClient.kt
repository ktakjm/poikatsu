package com.ktakjm.poikatsu.data

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/** GitHub リポジトリの data/ 配下を raw 配信で取得する */
object GithubRawClient {

    private const val BASE_URL = "https://raw.githubusercontent.com/ktakjm/poikatsu/main/data/"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** オフライン・HTTPエラー等はすべて null(呼び出し側でローカルにフォールバック) */
    fun fetch(fileName: String): String? = runCatching {
        val request = Request.Builder().url(BASE_URL + fileName).build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    }.getOrNull()
}
