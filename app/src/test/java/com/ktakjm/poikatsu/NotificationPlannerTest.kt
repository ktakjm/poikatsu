package com.ktakjm.poikatsu

import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.Municipality
import com.ktakjm.poikatsu.data.MunicipalityMaster
import com.ktakjm.poikatsu.data.PaymentCard
import com.ktakjm.poikatsu.data.Prefecture
import com.ktakjm.poikatsu.data.Region
import com.ktakjm.poikatsu.data.RegisteredArea
import com.ktakjm.poikatsu.data.RegisteredAreaType
import com.ktakjm.poikatsu.domain.CampaignNotification
import com.ktakjm.poikatsu.domain.NotificationKind
import com.ktakjm.poikatsu.domain.delayUntilNextNotifyTime
import com.ktakjm.poikatsu.domain.notificationLine
import com.ktakjm.poikatsu.domain.notificationTargets
import com.ktakjm.poikatsu.domain.notificationTitle
import com.ktakjm.poikatsu.domain.planCampaignNotifications
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * キャンペーン通知(#6)の対象絞り込み・通知判定・文言の検証。
 * 実データには依存しないフィクスチャで判定条件を網羅する。
 */
class NotificationPlannerTest {

    private val today: LocalDate = LocalDate.of(2026, 7, 26)

    private fun promotion(
        id: String,
        cardId: String? = null,
        cardBrand: String? = null,
        paymentMethodId: String? = null,
        start: String? = null,
        end: String? = null,
        displayName: String? = null,
    ) = Campaign(
        id = id,
        operator = "テスト",
        name = "$id の公式表記",
        displayName = displayName,
        type = "promotion",
        cardId = cardId,
        cardBrand = cardBrand,
        paymentMethodId = paymentMethodId,
        periodStart = start,
        periodEnd = end,
    )

    private fun municipal(id: String, prefecture: String, name: String, end: String? = null) = Campaign(
        id = id,
        operator = "テスト",
        name = "$name のテスト施策",
        type = "municipal",
        region = Region(name = name, prefecture = prefecture),
        periodEnd = end,
    )

    private val ownedCards = listOf(
        PaymentCard(id = "smcc", cardName = "三井住友", brand = "Visa"),
        PaymentCard(id = "owned_brand_amex", cardName = "Amexカード", brand = "Amex"),
    )

    private val master = MunicipalityMaster(
        prefectures = listOf(
            Prefecture(
                code = "13",
                name = "東京都",
                municipalities = listOf(Municipality(code = "13115", name = "杉並区")),
            ),
        ),
    )

    private val suginamiArea = RegisteredArea(
        type = RegisteredAreaType.MUNICIPALITY,
        code = "13115",
        name = "杉並区",
        prefecture = "東京都",
    )

    // ---- notificationTargets(自分に関係する施策への絞り込み) ----

    @Test
    fun `対象-所有カードとブランドと利用中QRの施策だけ通る`() {
        val campaigns = listOf(
            promotion("owned_card", cardId = "smcc"),
            promotion("unowned_card", cardId = "other"),
            promotion("owned_brand", cardBrand = "Amex"),
            promotion("unowned_brand", cardBrand = "JCB"),
            promotion("enabled_qr", paymentMethodId = "paypay"),
            promotion("disabled_qr", paymentMethodId = "dbarai"),
        )
        val targets = notificationTargets(campaigns, ownedCards, setOf("paypay"), emptyList(), master)
        assertEquals(
            listOf("owned_card", "owned_brand", "enabled_qr"),
            targets.map { it.id },
        )
    }

    @Test
    fun `対象-常設のcard_programは通知対象外`() {
        val campaigns = listOf(
            Campaign(id = "everyday", operator = "テスト", name = "常設", cardId = "smcc", type = "card_program"),
        )
        assertTrue(notificationTargets(campaigns, ownedCards, emptySet(), emptyList(), master).isEmpty())
    }

    @Test
    fun `対象-自治体施策は登録エリア一致のみ(決済手段は問わない)`() {
        val campaigns = listOf(
            municipal("suginami", "東京都", "杉並区"),
            municipal("elsewhere", "秋田県", "湯沢市"),
            // 自治体施策は専用QRが未登録でも、エリアが一致すれば通知する
            municipal("suginami_qr", "東京都", "杉並区").copy(paymentMethodId = "suginami_pay"),
        )
        val targets = notificationTargets(campaigns, ownedCards, emptySet(), listOf(suginamiArea), master)
        assertEquals(listOf("suginami", "suginami_qr"), targets.map { it.id })
    }

    @Test
    fun `対象-エリア未登録なら自治体施策は通知しない`() {
        val campaigns = listOf(municipal("suginami", "東京都", "杉並区"))
        assertTrue(notificationTargets(campaigns, ownedCards, emptySet(), emptyList(), master).isEmpty())
    }

    @Test
    fun `対象-カスタムキャンペーンは決済手段の所有に関わらず通る`() {
        val campaigns = listOf(promotion("custom:abc", cardId = "custom:card-x"))
        val targets = notificationTargets(campaigns, ownedCards, emptySet(), emptyList(), master)
        assertEquals(listOf("custom:abc"), targets.map { it.id })
    }

    // ---- planCampaignNotifications(今日通知すべきもの) ----

    private fun plan(vararg campaigns: Campaign) = planCampaignNotifications(campaigns.toList(), today)

    @Test
    fun `判定-開始日当日はSTARTED`() {
        val items = plan(promotion("p", start = "2026-07-26", end = "2026-08-31"))
        assertEquals(NotificationKind.STARTED, items.single().kind)
        assertEquals(0, items.single().days)
    }

    @Test
    fun `判定-前日の開始も翌日までは拾う(ジョブのスキップ耐性)`() {
        val items = plan(promotion("p", start = "2026-07-25", end = "2026-08-31"))
        assertEquals(NotificationKind.STARTED, items.single().kind)
        assertEquals(1, items.single().days)
    }

    @Test
    fun `判定-開始から2日過ぎたら開始通知はしない`() {
        assertTrue(plan(promotion("p", start = "2026-07-24", end = "2026-08-31")).isEmpty())
    }

    @Test
    fun `判定-終了3日前からENDS_SOON`() {
        val items = plan(promotion("p", start = "2026-07-01", end = "2026-07-29"))
        assertEquals(NotificationKind.ENDS_SOON, items.single().kind)
        assertEquals(3, items.single().days)
    }

    @Test
    fun `判定-終了日当日はENDS_SOONの残り0日`() {
        val items = plan(promotion("p", end = "2026-07-26"))
        assertEquals(NotificationKind.ENDS_SOON, items.single().kind)
        assertEquals(0, items.single().days)
    }

    @Test
    fun `判定-終了4日前はまだ通知しない`() {
        assertTrue(plan(promotion("p", start = "2026-07-01", end = "2026-07-30")).isEmpty())
    }

    @Test
    fun `判定-開始前と終了後は通知しない`() {
        assertTrue(plan(promotion("upcoming", start = "2026-07-27")).isEmpty())
        assertTrue(plan(promotion("expired", end = "2026-07-25")).isEmpty())
    }

    @Test
    fun `判定-開始と終了間近が重なる短期施策は開始のみ(1施策1行)`() {
        val items = plan(promotion("p", start = "2026-07-26", end = "2026-07-28"))
        assertEquals(NotificationKind.STARTED, items.single().kind)
    }

    @Test
    fun `判定-期間未定(may_end_early等)の施策は通知されない`() {
        assertTrue(plan(promotion("p").copy(mayEndEarly = true)).isEmpty())
    }

    @Test
    fun `判定-dedupKeyは期間の日付込み(延長・改定で再通知される)`() {
        val started = plan(promotion("p", start = "2026-07-26", end = "2026-08-31")).single()
        assertEquals("start:p:2026-07-26", started.dedupKey)
        val ending = plan(promotion("q", end = "2026-07-27")).single()
        assertEquals("end:q:2026-07-27", ending.dedupKey)
    }

    // ---- 文言 ----

    @Test
    fun `文言-1行分はdisplay_name優先で状況別`() {
        val c = promotion("p", displayName = "サイゼ10%", start = "2026-07-26", end = "2026-07-31")
        assertEquals(
            "「サイゼ10%」が今日から開始",
            notificationLine(CampaignNotification(c, NotificationKind.STARTED, 0)),
        )
        assertEquals(
            "「サイゼ10%」が開始しました",
            notificationLine(CampaignNotification(c, NotificationKind.STARTED, 1)),
        )
        assertEquals(
            "「サイゼ10%」は残り2日",
            notificationLine(CampaignNotification(c, NotificationKind.ENDS_SOON, 2)),
        )
        assertEquals(
            "「サイゼ10%」は今日で終了",
            notificationLine(CampaignNotification(c, NotificationKind.ENDS_SOON, 0)),
        )
    }

    @Test
    fun `文言-タイトルは1件なら内容そのまま複数なら件数まとめ`() {
        val c = promotion("p", displayName = "サイゼ10%")
        val started = CampaignNotification(c, NotificationKind.STARTED, 0)
        val ending = CampaignNotification(c, NotificationKind.ENDS_SOON, 2)
        assertEquals("「サイゼ10%」が今日から開始", notificationTitle(listOf(started)))
        assertEquals("キャンペーン 開始1件・まもなく終了2件", notificationTitle(listOf(started, ending, ending)))
        assertEquals("キャンペーン まもなく終了2件", notificationTitle(listOf(ending, ending)))
    }

    // ---- スケジュール ----

    @Test
    fun `スケジュール-次に設定時刻を迎えるまでの遅延`() {
        assertEquals(
            Duration.ofHours(1),
            delayUntilNextNotifyTime(LocalDateTime.of(2026, 7, 26, 7, 0), notifyTimeMinutes = 8 * 60),
        )
        // ちょうど設定時刻なら翌日(直後の二重実行を避ける)
        assertEquals(
            Duration.ofHours(24),
            delayUntilNextNotifyTime(LocalDateTime.of(2026, 7, 26, 8, 0), notifyTimeMinutes = 8 * 60),
        )
        assertEquals(
            Duration.ofHours(22).plusMinutes(30),
            delayUntilNextNotifyTime(LocalDateTime.of(2026, 7, 26, 9, 30), notifyTimeMinutes = 8 * 60),
        )
        // 15分刻みの設定値(7:45)もそのまま扱える
        assertEquals(
            Duration.ofMinutes(15),
            delayUntilNextNotifyTime(LocalDateTime.of(2026, 7, 26, 7, 30), notifyTimeMinutes = 7 * 60 + 45),
        )
    }
}
