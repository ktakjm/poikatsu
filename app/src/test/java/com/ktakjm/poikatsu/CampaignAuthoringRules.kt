package com.ktakjm.poikatsu

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

// campaigns.json の記述規約(#89)の検証ヘルパー。実データ(JudgmentEngineRealDataTest)と
// テストデータ(TestDataIntegrityTest)の両方から同じ規約を通す。

/** JSON 文字列中の明示 null を再帰的に探す(#89 の「明示 null を書かない」規約の検証用) */
private fun findJsonNulls(element: kotlinx.serialization.json.JsonElement, path: String, out: MutableList<String>) {
    when (element) {
        is kotlinx.serialization.json.JsonNull -> out.add(path)
        is kotlinx.serialization.json.JsonObject -> element.forEach { (k, v) -> findJsonNulls(v, "$path.$k", out) }
        is kotlinx.serialization.json.JsonArray -> element.forEachIndexed { i, v -> findJsonNulls(v, "$path[$i]", out) }
        else -> Unit
    }
}

private fun jsonNullPaths(raw: String): List<String> =
    mutableListOf<String>().also { findJsonNulls(kotlinx.serialization.json.Json.parseToJsonElement(raw), "$", it) }

/** campaigns.json の生の施策オブジェクト(展開前の記述形。payment_variants / operator 省略の検証用) */
private fun rawCampaignObjects(campaignsRaw: String): List<kotlinx.serialization.json.JsonObject> =
    kotlinx.serialization.json.Json.parseToJsonElement(campaignsRaw).jsonObject.getValue("campaigns").jsonArray
        .map { it.jsonObject }

private fun kotlinx.serialization.json.JsonObject.str(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * #89 の記述形の規約(実データ・テストデータ共通):
 * - municipal は payment_variants(1 件以上)で帰属を持ち、施策直下に手段固有フィールドを書かない
 * - variant の payment_method_id はキャンペーン内で一意・カタログに存在し、detail_url / verified_date を持つ
 * - municipal 以外は payment_variants を持たない
 * - operator は導出値(帰属先のカタログ名)と違うときだけ明示する
 * - 明示 null を書かない(省略 = null)
 * - 展開後の id は一意で、operator / payment_instruction は全件解決されている
 */
internal fun assertCampaignAuthoringRules(label: String, fixture: RealDataSet) {
    val data = fixture.data
    val campaignsRaw = fixture.campaignsRaw
    val qrIds = data.qrPayments.map { it.id }.toSet()
    val variantOnlyKeys = listOf("payment_method_id", "detail_url", "store_search_url", "verified_date", "point_currency_id")
    rawCampaignObjects(campaignsRaw).forEach { c ->
        val id = c.str("id")
        val variants = c["payment_variants"]?.jsonArray
        if (c.str("type") == "municipal") {
            assertTrue("$label $id: municipal は payment_variants を 1 件以上持つ", !variants.isNullOrEmpty())
            variantOnlyKeys.forEach { k ->
                assertFalse("$label $id: municipal の施策直下に $k を書かない(payment_variants 側に持つ)", c.containsKey(k))
            }
            val pms = variants!!.map { v ->
                val vo = v.jsonObject
                val pm = vo.str("payment_method_id")
                assertTrue("$label $id: variant の payment_method_id がカタログに無い($pm)", pm in qrIds)
                assertTrue("$label $id/$pm: variant は detail_url を持つ", !vo.str("detail_url").isNullOrBlank())
                assertTrue("$label $id/$pm: variant は verified_date を持つ", !vo.str("verified_date").isNullOrBlank())
                pm
            }
            assertEquals("$label $id: payment_variants の決済手段が重複", pms.size, pms.distinct().size)
        } else {
            assertFalse("$label $id: municipal 以外は payment_variants を持たない", c.containsKey("payment_variants"))
        }
        c.str("operator")?.let { explicit ->
            val resolved = data.campaigns.first { it.id == id || it.id.startsWith("${id}_") }
            val derived = com.ktakjm.poikatsu.domain.deriveOperator(
                resolved.attribution, data.cards, data.qrPayments, data.pointCurrencies,
            )
            assertTrue("$label $id: operator「$explicit」は導出値と同じなので書かない(例外だけ明示する)", explicit != derived)
        }
    }
    listOf("campaigns.json" to campaignsRaw, "payment_methods.json" to fixture.paymentMethodsRaw, "merchants.json" to fixture.merchantsRaw)
        .forEach { (name, raw) ->
            assertEquals("$label $name: 明示 null を書かない(省略 = null)", emptyList<String>(), jsonNullPaths(raw))
        }
    val ids = data.campaigns.map { it.id }
    assertEquals("$label: 展開後の施策 id が重複", ids.size, ids.distinct().size)
    data.campaigns.forEach { c ->
        assertTrue("$label ${c.id}: operator が解決されていない", c.operator.isNotBlank())
        assertTrue("$label ${c.id}: 展開後に payment_variants が残っている", c.paymentVariants.isEmpty())
    }
}
