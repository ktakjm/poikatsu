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
        DataRepository.MERCHANTS to File("../data/merchants.json").readText(),
        DataRepository.CAMPAIGNS to File("../data/campaigns.json").readText(),
        DataRepository.PROFILE to File("../data/profile.json").readText(),
    )

    private fun repository(fetchRemote: (String) -> String?) = DataRepository(
        readAsset = { name -> assetTexts.getValue(name) },
        cacheDir = File(tempFolder.root, "remote_data"),
        fetchRemote = { name, _ -> fetchRemote(name) },
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
    fun `壊れたキャッシュは無視して同梱データにフォールバックする`() {
        val cacheDir = File(tempFolder.root, "remote_data").apply { mkdirs() }
        File(cacheDir, DataRepository.MERCHANTS).writeText("{ broken")
        File(cacheDir, DataRepository.CAMPAIGNS).writeText("{ broken")
        val loaded = repository { null }.loadLocal()
        assertEquals(DataSource.BUNDLED, loaded.source)
    }
}
