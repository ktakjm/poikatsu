package com.ktakjm.poikatsu

import com.ktakjm.poikatsu.data.DataRepository
import com.ktakjm.poikatsu.data.DataSource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val assetTexts = mapOf(
        DataRepository.MERCHANTS to """
            {
                "schema_version": 1,
                "updated_at": "2026-07-03",
                "merchants": [
                    {"id": "test_store", "name": "テスト店", "reading": "てすとてん", "category": "その他"}
                ]
            }
        """.trimIndent(),
        DataRepository.CAMPAIGNS to """
            {
                "schema_version": 1,
                "updated_at": "2026-07-03",
                "campaigns": [
                    {
                        "id": "test_campaign",
                        "operator": "test",
                        "card_id": "test_card",
                        "name": "テスト施策",
                        "rate_base": 5.0,
                        "verified_date": "2026-06-01",
                        "merchant_rules": [{"merchant_id": "test_store"}]
                    }
                ]
            }
        """.trimIndent(),
        DataRepository.PAYMENT_METHODS to """
            {
                "schema_version": 1,
                "updated_at": "2026-07-03",
                "cards": [
                    {"id": "test_card", "card_name": "テストカード"}
                ]
            }
        """.trimIndent(),
    )

    // readAsset は "data/merchants.json" のような assets 内パスを受ける。
    // data-test/ 側は updated_at を変えた別内容にして読み分けを検証できるようにする
    private fun repository(fetchRemote: (String) -> String?) = DataRepository(
        readAsset = { path ->
            val (dir, name) = path.split("/", limit = 2)
            val text = assetTexts.getValue(name)
            if (dir == "data-test") text.replace("\"2026-07-03\"", "\"2026-12-31\"") else text
        },
        cacheDir = File(tempFolder.root, "remote_data"),
        fetchRemote = { name, _, _ -> fetchRemote(name) },
    )

    @Test
    fun `キャッシュがなければ同梱データを使う`() {
        val loaded = repository { null }.loadLocal()
        assertEquals(DataSource.BUNDLED, loaded.source)
        assertTrue(loaded.data.merchants.isNotEmpty())
    }

    @Test
    fun `リモート取得に成功するとキャッシュされ次回起動で使われる`() {
        // updated_at を変えたリモートデータを返す
        val repo = repository { name ->
            assetTexts.getValue(name).replace("\"2026-07-03\"", "\"2099-01-01\"")
        }
        val refreshed = repo.refresh()!!
        assertEquals(DataSource.REMOTE, refreshed.source)
        assertEquals("2099-01-01", refreshed.data.updatedAt)

        // 次回起動相当: リモートに繋がらなくてもキャッシュから読める
        val nextLaunch = repository { null }.loadLocal()
        assertEquals(DataSource.CACHE, nextLaunch.source)
        assertEquals("2099-01-01", nextLaunch.data.updatedAt)
    }

    @Test
    fun `リモート取得失敗時はnullを返す`() {
        assertNull(repository { null }.refresh())
    }

    @Test
    fun `壊れたリモートデータはキャッシュを汚さない`() {
        val repo = repository { "{ broken json" }
        assertNull(repo.refresh())
        assertFalse(File(tempFolder.root, "remote_data/${DataRepository.CAMPAIGNS}").exists())
        // ローカルロードは引き続き同梱データで動く
        assertEquals(DataSource.BUNDLED, repo.loadLocal().source)
    }

    @Test
    fun `同梱直読はキャッシュがあっても assets を読む`() {
        // リモート取得成功でキャッシュを作る(updated_at = 2099-01-01)
        val repo = repository { name ->
            assetTexts.getValue(name).replace("\"2026-07-03\"", "\"2099-01-01\"")
        }
        repo.refresh()
        // loadLocal はキャッシュ優先だが、loadBundled はバイパスして assets を返す
        assertEquals(DataSource.CACHE, repo.loadLocal().source)
        val bundled = repo.loadBundled()
        assertEquals(DataSource.BUNDLED, bundled.source)
        assertEquals("2026-07-03", bundled.data.updatedAt)
    }

    @Test
    fun `同梱直読は dataDir 指定で data-test 側の assets を読む`() {
        val bundled = repository { null }.loadBundled(dataDir = "data-test")
        assertEquals(DataSource.BUNDLED, bundled.source)
        assertEquals("2026-12-31", bundled.data.updatedAt)
    }

    @Test
    fun `キャッシュなしの loadLocal も dataDir 指定の assets へフォールバックする`() {
        val loaded = repository { null }.loadLocal(dataDir = "data-test")
        assertEquals(DataSource.BUNDLED, loaded.source)
        assertEquals("2026-12-31", loaded.data.updatedAt)
    }

    @Test
    fun `壊れたキャッシュは無視して同梱データにフォールバックする`() {
        val cacheDir = File(tempFolder.root, "remote_data").apply { mkdirs() }
        File(cacheDir, DataRepository.MERCHANTS).writeText("{ broken")
        File(cacheDir, DataRepository.CAMPAIGNS).writeText("{ broken")
        File(cacheDir, DataRepository.PAYMENT_METHODS).writeText("{ broken")
        val loaded = repository { null }.loadLocal()
        assertEquals(DataSource.BUNDLED, loaded.source)
    }
}
