package com.ktakjm.poikatsu

import com.ktakjm.poikatsu.data.CardOverride
import com.ktakjm.poikatsu.data.migratePointValueMaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 旧カード単位の 1pt 価値(CardOverride.pointValue)→通貨単位(pointCurrencyValues)への
 * 移行ロジック([migratePointValueMaps])を検証する(#13)。
 *
 * レビュー指摘: カタログが不完全(cardToCurrency に対応が無い)な状態で移行を走らせると、
 * 移行できなかったカードの pointValue まで一緒に消えてしまい、値を永久に失う不具合があった
 * (applyData のたびに移行が発火するため、リモート取得前のキャッシュ・古いバックアップ由来の
 * カタログ不一致で実際に起こり得る)。修正: cardToCurrency に実在する cardId だけを消し、
 * 未マッピングの override はそのまま残して後続パスで再移行できるようにする。
 */
class PointValueMigrationTest {

    @Test
    fun `対応がある通貨へ移行しpointValueは消える`() {
        val overrides = mapOf("jcb_original" to CardOverride(pointValue = 0.7))
        val (updatedOverrides, updatedValues) = migratePointValueMaps(
            overrides = overrides,
            values = emptyMap(),
            cardToCurrency = mapOf("jcb_original" to "j_point"),
        )
        assertEquals(0.7, updatedValues["j_point"]!!, 0.0)
        assertNull(updatedOverrides.getValue("jcb_original").pointValue)
    }

    // カタログ不完全(cardToCurrency にこのカードの対応が無い)なときは pointValue を消さない。
    // 消してしまうと通貨側にも移らず値が永久に失われる(レビュー指摘の再現ケース)
    @Test
    fun `対応が無いカードのpointValueは消さずに残す`() {
        val overrides = mapOf("jcb_original" to CardOverride(pointValue = 0.7))
        val (updatedOverrides, updatedValues) = migratePointValueMaps(
            overrides = overrides,
            values = emptyMap(),
            cardToCurrency = emptyMap(),
        )
        assertEquals(emptyMap<String, Double>(), updatedValues)
        assertEquals(0.7, updatedOverrides.getValue("jcb_original").pointValue!!, 0.0)
    }

    // 既に通貨側に値がある場合はそちらを優先する(putIfAbsent)。カード側の古い値で上書きしない
    @Test
    fun `通貨側に既に値がある場合はそちらを優先する`() {
        val overrides = mapOf("jcb_original" to CardOverride(pointValue = 0.7))
        val (_, updatedValues) = migratePointValueMaps(
            overrides = overrides,
            values = mapOf("j_point" to 1.0),
            cardToCurrency = mapOf("jcb_original" to "j_point"),
        )
        assertEquals(1.0, updatedValues["j_point"]!!, 0.0)
    }

    // 移行後にもう一度呼んでも結果が変わらない(何度呼んでも安全)
    @Test
    fun `移行済みの状態で再度呼んでも変化しない`() {
        val overrides = mapOf("jcb_original" to CardOverride(pointValue = 0.7))
        val (firstOverrides, firstValues) = migratePointValueMaps(
            overrides = overrides,
            values = emptyMap(),
            cardToCurrency = mapOf("jcb_original" to "j_point"),
        )
        val (secondOverrides, secondValues) = migratePointValueMaps(
            overrides = firstOverrides,
            values = firstValues,
            cardToCurrency = mapOf("jcb_original" to "j_point"),
        )
        assertEquals(firstOverrides, secondOverrides)
        assertEquals(firstValues, secondValues)
    }

    // pointValue を持たないカードは何も変わらない(対象外)
    @Test
    fun `pointValueが無いカードは対象外`() {
        val overrides = mapOf("olive" to CardOverride(owned = false))
        val (updatedOverrides, updatedValues) = migratePointValueMaps(
            overrides = overrides,
            values = emptyMap(),
            cardToCurrency = emptyMap(),
        )
        assertEquals(overrides, updatedOverrides)
        assertEquals(emptyMap<String, Double>(), updatedValues)
    }
}
