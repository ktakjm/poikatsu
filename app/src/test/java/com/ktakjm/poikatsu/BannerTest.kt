package com.ktakjm.poikatsu

import com.ktakjm.poikatsu.data.Banner
import com.ktakjm.poikatsu.data.BannerSelection
import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.CustomCampaign
import com.ktakjm.poikatsu.data.CustomPayment
import com.ktakjm.poikatsu.data.Merchant
import com.ktakjm.poikatsu.data.MerchantRule
import com.ktakjm.poikatsu.data.PoikatsuData
import com.ktakjm.poikatsu.data.PoikatsuJson
import com.ktakjm.poikatsu.data.QrPayment
import com.ktakjm.poikatsu.domain.JudgmentEngine
import com.ktakjm.poikatsu.domain.toCampaigns
import com.ktakjm.poikatsu.ui.campaignTargetLabels
import com.ktakjm.poikatsu.util.JapaneseText
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 系列(merchant)と看板(banner)の2階層(#60)のテスト。
// - BannerMatchTest: フィクスチャで照合・判定スコープ・検索のロジックを検証
// - BannerRealDataTest: data/ の実データで登録の有効性(取りこぼし・誤爆・一意性)を検証
// - BannerTestDataTest: data-test/ のショーケースデータの同検証

/** 照合キー(name/reading/aliases の正規化)が merchant×看板を跨いで一意なことを検証する共通処理 */
private fun assertBannerKeysUnique(merchants: List<Merchant>) {
    // 同じキーが2つの(merchant, banner)に載ると matchStore の勝者が不定になる(最長一致は長さしか見ない)
    val owner = mutableMapOf<String, Pair<String, String>>()
    merchants.forEach { m ->
        val entries = listOf(Triple(m.id, m.name to m.reading, m.aliases)) +
            m.banners.map { Triple(it.id, it.name to it.reading, it.aliases) }
        entries.forEach { (bannerId, nameReading, aliases) ->
            val (name, reading) = nameReading
            val keys = buildSet {
                add(JapaneseText.normalize(name))
                if (reading.isNotBlank()) add(JapaneseText.normalize(reading))
                aliases.forEach { add(JapaneseText.normalize(it)) }
            }
            keys.forEach { key ->
                val prev = owner.put(key, m.id to bannerId)
                assertTrue(
                    "照合キー '$key' が ${prev} と ${m.id to bannerId} で重複(勝者不定)",
                    prev == null || prev == m.id to bannerId,
                )
            }
        }
    }
}

/** banner_ids / ineligible_banner_ids の排他・参照切れ・看板 id の一意性を検証する共通処理 */
private fun assertBannerScopeIntegrity(data: PoikatsuData) {
    data.merchants.forEach { m ->
        assertEquals("${m.id}: 看板 id が merchant 内で重複", m.allBannerIds.size, m.allBannerIds.toSet().size)
    }
    val merchantsById = data.merchants.associateBy { it.id }
    data.campaigns.forEach { c ->
        c.merchantRules.forEach { rule ->
            assertTrue(
                "${c.id}/${rule.merchantId}: banner_ids と ineligible_banner_ids は排他",
                rule.bannerIds.isEmpty() || rule.ineligibleBannerIds.isEmpty(),
            )
            val merchant = merchantsById[rule.merchantId] ?: return@forEach
            (rule.bannerIds + rule.ineligibleBannerIds).forEach { bannerId ->
                assertTrue(
                    "${c.id}/${rule.merchantId}: banner id '$bannerId' が merchants.json に無い",
                    bannerId in merchant.allBannerIds,
                )
            }
        }
    }
}

/**
 * フィクスチャデータで看板(業態)の照合・判定スコープ・検索を検証する。
 */
class BannerMatchTest {

    private val tsuruha = Merchant(
        id = "tsuruha",
        name = "ツルハドラッグ",
        reading = "つるはどらっぐ",
        aliases = listOf("ツルハ"),
        category = "ドラッグストア",
        groupLabel = "ツルハグループ",
        banners = listOf(
            Banner(id = "kyorindo", name = "杏林堂薬局", reading = "きょうりんどうやっきょく", aliases = listOf("杏林堂")),
            Banner(id = "fukutaro", name = "くすりの福太郎", reading = "くすりのふくたろう"),
        ),
    )

    private fun qrCampaign(id: String, rule: MerchantRule) = Campaign(
        id = id,
        operator = "PayPay",
        name = id,
        type = "promotion",
        storeScope = "managed",
        paymentMethodId = "paypay",
        paymentInstruction = "PayPayで支払う",
        rateBase = 10.0,
        merchantRules = listOf(rule),
    )

    private val data = PoikatsuData(
        merchants = listOf(tsuruha),
        campaigns = listOf(
            qrCampaign("group_wide", MerchantRule(merchantId = "tsuruha")),
            qrCampaign("kyorindo_excluded", MerchantRule(merchantId = "tsuruha", ineligibleBannerIds = listOf("kyorindo"))),
            qrCampaign("fukutaro_only", MerchantRule(merchantId = "tsuruha", bannerIds = listOf("fukutaro"))),
        ),
        qrPayments = listOf(QrPayment(id = "paypay", name = "PayPay", brandColor = "#FF0033")),
        updatedAt = "2026-08-01",
    )
    private val engine = JudgmentEngine(data)
    private val today = LocalDate.of(2026, 8, 15)
    private val qrIds = setOf("paypay")

    @Test
    fun `POI名から一致した看板を特定できる`() {
        val match = engine.matchStore("杏林堂薬局 浜松中央店")!!
        assertEquals("tsuruha", match.merchant.id)
        assertEquals("kyorindo", match.bannerId)
        assertEquals("杏林堂薬局", match.bannerName)
    }

    @Test
    fun `看板のaliasでも一致し看板に帰属する`() {
        assertEquals("kyorindo", engine.matchStore("杏林堂 浜松店")?.bannerId)
    }

    @Test
    fun `代表看板への一致は bannerId が merchant id になる`() {
        assertEquals("tsuruha", engine.matchStore("ツルハドラッグ旭川店")?.bannerId)
    }

    @Test
    fun `判定は看板スコープに従う`() {
        // 除外看板(kyorindo): グループ全体の施策だけが残る
        assertEquals(
            listOf("group_wide"),
            engine.judgeQr(tsuruha, today, qrIds, bannerId = "kyorindo").map { it.campaign.id },
        )
        // 許可リストの看板(fukutaro): 3施策すべて対象
        assertEquals(
            setOf("group_wide", "kyorindo_excluded", "fukutaro_only"),
            engine.judgeQr(tsuruha, today, qrIds, bannerId = "fukutaro").map { it.campaign.id }.toSet(),
        )
        // 代表看板: 許可リスト(fukutaro_only)の対象外
        assertEquals(
            setOf("group_wide", "kyorindo_excluded"),
            engine.judgeQr(tsuruha, today, qrIds, bannerId = tsuruha.id).map { it.campaign.id }.toSet(),
        )
    }

    @Test
    fun `グループ視点(bannerId null)はスコープ付き施策も注記付きで全部出す`() {
        val judgments = engine.judgeAll(tsuruha, today, qrIds).judgments
        assertEquals(3, judgments.size)
        val excluded = judgments.first { it.campaign.id == "kyorindo_excluded" }
        assertTrue(excluded.ineligibleNotes.contains("杏林堂薬局は対象外"))
        val only = judgments.first { it.campaign.id == "fukutaro_only" }
        assertTrue(only.ineligibleNotes.contains("対象はくすりの福太郎のみ"))
        // スコープの無い施策には注記が付かない
        assertTrue(judgments.first { it.campaign.id == "group_wide" }.ineligibleNotes.isEmpty())
    }

    @Test
    fun `検索は看板名でヒットし看板情報が付く`() {
        val hit = engine.search("杏林堂").single()
        assertEquals("tsuruha", hit.merchant.id)
        assertEquals("kyorindo", hit.bannerId)
        assertEquals("杏林堂薬局", hit.bannerName)
    }

    @Test
    fun `代表看板名の検索はグループとしてのヒット(banner無し)になる`() {
        val hit = engine.search("ツルハ").single()
        assertEquals("tsuruha", hit.merchant.id)
        assertNull(hit.bannerId)
    }

    @Test
    fun `カテゴリのみの絞り込みは系列単位で1行になる`() {
        val hits = engine.search("", setOf("ドラッグストア"))
        assertEquals(1, hits.size)
        assertNull(hits.single().bannerId)
    }

    @Test
    fun `看板名そのものの入力は完全一致と判定される`() {
        assertTrue(engine.isExactNameMatch(tsuruha, "杏林堂薬局"))
        assertTrue(engine.isExactNameMatch(tsuruha, "くすりの福太郎"))
    }

    @Test
    fun `重複排除キーは看板名の別表記も剥がす`() {
        // 同じ店が「杏林堂薬局」「杏林堂」の別表記で重複登録されても同じ支店キーになる
        assertEquals(
            engine.normalizedBranch(tsuruha, "杏林堂薬局浜松店"),
            engine.normalizedBranch(tsuruha, "杏林堂浜松店"),
        )
    }

    @Test
    fun `カスタムキャンペーンの業態選択はbanner_ids付きルールへ変換される`() {
        val custom = CustomCampaign(
            id = "custom:test",
            name = "杏林堂だけのクーポン",
            payments = listOf(CustomPayment(qrPaymentId = "paypay")),
            bannerSelections = listOf(BannerSelection("tsuruha", "kyorindo")),
            rate = 5.0,
        )
        val rule = custom.toCampaigns { "PayPay" }.single().merchantRules.single()
        assertEquals("tsuruha", rule.merchantId)
        assertEquals(listOf("kyorindo"), rule.bannerIds)
    }

    @Test
    fun `施策詳細の対象ラベルはbanner_idsのルールを業態名で出す`() {
        val campaigns = listOf(
            qrCampaign("fukutaro_only", MerchantRule(merchantId = "tsuruha", bannerIds = listOf("fukutaro"))),
        )
        assertEquals(
            listOf("くすりの福太郎"),
            campaignTargetLabels(campaigns, mapOf(tsuruha.id to tsuruha)),
        )
    }

    @Test
    fun `施策詳細の対象ラベルは業態を持つ系列の全業態ルールでグループ名になる`() {
        val campaigns = listOf(
            qrCampaign("group_wide", MerchantRule(merchantId = "tsuruha")),
            qrCampaign("kyorindo_excluded", MerchantRule(merchantId = "tsuruha", ineligibleBannerIds = listOf("kyorindo"))),
        )
        // 「ツルハドラッグ」だと業態名かグループか区別できないためグループ名で出す。
        // 同じ merchant は重複排除され、一部除外の内訳は判定カードの注記側が示す
        assertEquals(
            listOf("ツルハグループ"),
            campaignTargetLabels(campaigns, mapOf(tsuruha.id to tsuruha)),
        )
    }

    @Test
    fun `施策詳細の対象ラベルは業態を持たないmerchantでmerchant名のまま`() {
        val sundrug = Merchant(id = "sundrug", name = "サンドラッグ", reading = "さんどらっぐ")
        val campaigns = listOf(qrCampaign("plain", MerchantRule(merchantId = "sundrug")))
        assertEquals(
            listOf("サンドラッグ"),
            campaignTargetLabels(campaigns, mapOf(sundrug.id to sundrug)),
        )
    }

    @Test
    fun `カスタムキャンペーンで系列まるごと選択があれば同系列の業態選択は畳まれる`() {
        val custom = CustomCampaign(
            id = "custom:test",
            name = "ツルハ全部のクーポン",
            payments = listOf(CustomPayment(qrPaymentId = "paypay")),
            merchantIds = listOf("tsuruha"),
            bannerSelections = listOf(BannerSelection("tsuruha", "kyorindo")),
            rate = 5.0,
        )
        val rule = custom.toCampaigns { "PayPay" }.single().merchantRules.single()
        assertEquals("tsuruha", rule.merchantId)
        assertTrue(rule.bannerIds.isEmpty())
    }
}

/**
 * data/ の実データで看板登録の有効性を検証する。
 * 「系列の取りこぼし検知」の実体: 看板を登録しても isMatchableKey(3文字未満)や
 * containsAsWord の境界判定に弾かれて照合できない、という登録ミスを CI で捕まえる。
 */
class BannerRealDataTest {

    private val data = PoikatsuJson.parse(
        merchantsJson = File("../data/merchants.json").readText(),
        campaignsJson = File("../data/campaigns.json").readText(),
        paymentMethodsJson = File("../data/payment_methods.json").readText(),
    )
    private val engine = JudgmentEngine(data)

    @Test
    fun `実データ_全看板がPOI照合で正しいmerchantと看板に解決できる`() {
        data.merchants.forEach { m ->
            m.banners.forEach { b ->
                val match = engine.matchStore("${b.name}中央店")
                assertEquals("看板「${b.name}」が照合できない(キー長・境界判定を確認)", m.id, match?.merchant?.id)
                assertEquals("看板「${b.name}」の帰属が不正", b.id, match?.bannerId)
            }
        }
    }

    @Test
    fun `実データ_照合キーは全看板で一意`() {
        assertBannerKeysUnique(data.merchants)
    }

    @Test
    fun `実データ_banner_idsの排他と参照が正しい`() {
        assertBannerScopeIntegrity(data)
    }

    @Test
    fun `実データ_ランドリン施策の46看板すべてが地図経路で施策に到達する`() {
        // 施策の期限切れ削除に追随できるよう、施策が消えたらこのテストも静かに終わる
        val campaign = data.campaigns.firstOrNull { it.id == "paypay_landrin_drug_2026_08" } ?: return
        val date = JudgmentEngine.parseDate(campaign.periodStart!!)
        // 公式ページ掲載の46看板。POI 実名に合わせて3つだけ読み替える:
        // シミズ→シミズ薬品 / B&D→B&Dドラッグストア / ダルマ→ダルマ薬局(かな3文字の誤爆回避で正式名登録)
        val officialBanners = mapOf(
            "welcia" to listOf(
                "ウエルシア", "ウエルシア薬局", "金光薬品", "コクミンドラッグ", "シミズ薬品",
                "スーパードラッグひまわり", "ダックス", "とをしや薬局", "ハックドラッグ", "ハックエクスプレス",
                "ハッピー・ドラッグ", "ふく薬品", "マルエドラッグ", "よどやドラッグ", "Ｂ.Ｂ.ＯＮ",
            ),
            "sugi_pharmacy" to listOf("ジャパン", "スギドラッグ", "スギ薬局", "ドラッグスギ"),
            "tsuruha" to listOf(
                "杏林堂薬局", "くすりの福太郎", "クスリのツルハ", "ツルハドラッグ", "B&Dドラッグストア",
                "ウォンツ", "ドラッグストアウェルネス", "ドラッグイレブン", "くすりのレデイ", "メディコ21",
            ),
            "matsukiyo" to listOf(
                "マツモトキヨシ", "matsukiyo LAB", "petit madoca", "どらっぐぱぱす", "ダルマ薬局",
                "ミドリ薬品", "ファミリードラッグ", "シメノドラッグ", "ヘルスバンク",
            ),
            "cocokara_fine" to listOf(
                "ココカラファイン", "ココカラファインプラスイズミヤ", "セイジョー", "ドラッグセガミ",
                "ジップドラッグ", "ライフォート", "クスリのコダマ", "くすりのラブ",
            ),
        )
        assertEquals(46, officialBanners.values.sumOf { it.size })
        officialBanners.forEach { (merchantId, banners) ->
            banners.forEach { banner ->
                val match = engine.matchStore("$banner 中央店")
                assertEquals("「$banner」が照合できない", merchantId, match?.merchant?.id)
                val ids = engine.judgeQr(match!!.merchant, date, setOf("paypay"), match.bannerId)
                    .map { it.campaign.id }
                assertTrue("「$banner」にランドリン施策が出ない", campaign.id in ids)
            }
        }
    }

    @Test
    fun `実データ_看板追加で誤爆しない`() {
        // スギ薬局の看板「ジャパン」はジャパンミートを奪わない(長いキーの最長一致+同字種境界)
        assertEquals("japan_meat", engine.matchStore("ジャパンミート卸売市場八王子店")?.merchant?.id)
        // ダルマは「ダルマ薬局」でのみ登録(素の「ダルマ」を alias にすると「だるま食堂」
        // 「だるま寿司」のような漢字後続の別業種に誤爆するため見送り。素の「ダルマ◯◯店」POI は
        // 「マツモトキヨシ ダルマ◯◯店」の重複 POI が対で存在し、そちらで拾える。2026-08 実測)
        assertNull(engine.matchStore("だるま食堂"))
        assertNull(engine.matchStore("屋台酒場やきだるま"))
        // ウォンツ(かな4文字)は直後が同字種かなだと別単語とみなして弾く
        assertNull(engine.matchStore("ウォンツカフェ"))
        // ぱぱす(かな3文字 alias)も同様。カフェは実際に取得される gc なので誤爆すると実害がある
        assertNull(engine.matchStore("パパスカフェ丸の内店"))
    }

    @Test
    fun `実データ_YOLPの短縮形POI名でも看板に照合できる`() {
        // YOLP の実データには公式看板名の短縮形が混在する(2026-08 に実測)。
        // alias で補完している: ぱぱす / ダルマ / くすりセイジョー
        assertEquals("papasu", engine.matchStore("ぱぱす月島店")?.bannerId)
        assertEquals("papasu", engine.matchStore("ぱぱす薬局 住吉店")?.bannerId)
        assertEquals("seijo", engine.matchStore("くすりセイジョー 浜田山店")?.bannerId)
        // 「マツモトキヨシ ダルマ薬局柳生店」型は最長一致で代表看板(マツモトキヨシ)に寄るが、
        // merchant は同じなので判定・ピンは正しい
        assertEquals("matsukiyo", engine.matchStore("マツモトキヨシ ダルマ薬局柳生店")?.merchant?.id)
        // 副作用の記録: アパレルの PAPAS 店舗名にも一致するようになるが、ファッション系の
        // ジャンルは YOLP 取得対象外のため地図には流入しない(「ぱぱす○○店」を拾う方を優先)
        assertEquals("papasu", engine.matchStore("パパス青山店")?.bannerId)
    }

    @Test
    fun `実データ_クスリのツルハは看板登録で照合できる(境界判定の回避)`() {
        // alias「ツルハ」だけでは「クスリの|ツルハ」の前方かな境界で弾かれていた(#60)
        val match = engine.matchStore("クスリのツルハ旭川店")
        assertEquals("tsuruha", match?.merchant?.id)
        assertEquals("kusuri_no_tsuruha", match?.bannerId)
    }

    @Test
    fun `実データ_matsukiyoLABはaliasのmatsukiyoより長い一致で看板に帰属する`() {
        val match = engine.matchStore("matsukiyo LAB 新宿店")
        assertEquals("matsukiyo", match?.merchant?.id)
        assertEquals("matsukiyo_lab", match?.bannerId)
    }
}

/**
 * data-test/ のショーケースデータの看板検証(実データと同じ整合性+スコープ動作の実例)。
 */
class BannerTestDataTest {

    private val data = PoikatsuJson.parse(
        merchantsJson = File("../data-test/merchants.json").readText(),
        campaignsJson = File("../data-test/campaigns.json").readText(),
        paymentMethodsJson = File("../data-test/payment_methods.json").readText(),
    )
    private val engine = JudgmentEngine(data)
    private val today = LocalDate.of(2026, 7, 20)

    @Test
    fun `テストデータ_全看板がPOI照合で解決でき照合キーが一意`() {
        data.merchants.forEach { m ->
            m.banners.forEach { b ->
                assertEquals("看板「${b.name}」が照合できない", m.id, engine.matchStore("${b.name}中央店")?.merchant?.id)
            }
        }
        assertBannerKeysUnique(data.merchants)
    }

    @Test
    fun `テストデータ_banner_idsの排他と参照が正しい`() {
        assertBannerScopeIntegrity(data)
    }

    @Test
    fun `テストデータ_ineligible_banner_idsの看板は判定から消える`() {
        // test_product_scope はテストドラッグ対象・サンドラッグ(test_sundrug)除外のショーケース
        val sundrug = engine.matchStore("サンドラッグ品川店")!!
        assertEquals("test_drugstore", sundrug.merchant.id)
        assertEquals("test_sundrug", sundrug.bannerId)
        val sundrugIds = engine.judgeQr(sundrug.merchant, today, setOf("test_paypay"), sundrug.bannerId)
            .map { it.campaign.id }
        assertTrue("test_product_scope" !in sundrugIds)
        // 除外されていない看板(ウエルシア)には出る
        val welcia = engine.matchStore("ウエルシア品川店")!!
        val welciaIds = engine.judgeQr(welcia.merchant, today, setOf("test_paypay"), welcia.bannerId)
            .map { it.campaign.id }
        assertTrue("test_product_scope" in welciaIds)
    }
}
