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
import com.ktakjm.poikatsu.domain.notificationItemText
import com.ktakjm.poikatsu.domain.notificationItemTitle
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

    private fun municipal(
        id: String,
        prefecture: String,
        name: String,
        start: String? = null,
        end: String? = null,
        operator: String = "テスト",
        displayName: String? = null,
    ) = Campaign(
        id = id,
        operator = operator,
        name = "$name のテスト施策",
        displayName = displayName,
        type = "municipal",
        region = Region(name = name, prefecture = prefecture),
        periodStart = start,
        periodEnd = end,
    )

    /** 同じ自治体・同じ期間を決済手段ごとに分けて収録した実データ相当のフィクスチャ */
    private fun chibaByPayment(vararg operators: String) = operators.map { op ->
        municipal(
            id = "chiba_pref_$op",
            prefecture = "千葉県",
            name = "千葉県",
            start = "2026-07-26",
            end = "2026-08-30",
            operator = op,
            displayName = "千葉県キャッシュレス決済キャンペーン",
        )
    }

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
    fun `判定-自治体施策の決済手段違いは1件にまとまる`() {
        // campaigns.json は (施策 × 決済手段) 単位。同じ自治体・同じ期間の5件は通知1件にする
        val items = planCampaignNotifications(
            chibaByPayment("PayPay", "au PAY", "d払い", "楽天ペイ", "AEON Pay"),
            today,
        )
        assertEquals(1, items.size)
        assertEquals(NotificationKind.STARTED, items.single().kind)
    }

    @Test
    fun `判定-同じキャンペーンでも期間が違えば別グループ`() {
        // 決済手段ごとに開始日が違うとき、グループの最早/最遅へ丸めると後から始まる方の開始を
        // 取りこぼす。期間もグループキーに含めてそれぞれの開始日に通知する
        val items = planCampaignNotifications(
            listOf(
                municipal("chiba_aupay", "千葉県", "千葉県", start = "2026-08-15", end = "2026-08-30"),
                municipal("chiba_paypay", "千葉県", "千葉県", start = "2026-07-26", end = "2026-08-30"),
            ),
            today,
        )
        assertEquals(1, items.size)
        assertEquals(NotificationKind.STARTED, items.single().kind)
        assertEquals(0, items.single().days)
    }

    @Test
    fun `判定-カスタムキャンペーンの複数決済展開は1件にまとまる`() {
        // 1登録を決済手段ごとの Campaign(id は「<登録id>:p<N>」)へ展開しているので、
        // 展開前の登録単位に畳む
        val items = planCampaignNotifications(
            listOf(
                promotion("custom:abc:p0", cardId = "custom:card-x", start = "2026-07-26", end = "2026-08-30"),
                promotion("custom:abc:p1", paymentMethodId = "paypay", start = "2026-07-26", end = "2026-08-30"),
            ),
            today,
        )
        assertEquals(1, items.size)
    }

    @Test
    fun `判定-dedupKeyは自治体施策ではグループ単位(決済手段で分かれない)`() {
        val items = planCampaignNotifications(chibaByPayment("PayPay", "au PAY"), today)
        assertEquals("start:municipal:千葉県:2026-07-26", items.single().dedupKey)
    }

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
        // 開始通知には終了日を併記する(開始と終了間近が重なる短期施策で終了時期が読めるように)
        assertEquals(
            "「サイゼ10%」が今日から開始(〜2026/07/31)",
            notificationLine(CampaignNotification(listOf(c), NotificationKind.STARTED, 0)),
        )
        assertEquals(
            "「サイゼ10%」が開始しました(〜2026/07/31)",
            notificationLine(CampaignNotification(listOf(c), NotificationKind.STARTED, 1)),
        )
        // 終了日の無い施策は併記なし
        val noEnd = promotion("q", displayName = "サイゼ10%", start = "2026-07-26")
        assertEquals(
            "「サイゼ10%」が今日から開始",
            notificationLine(CampaignNotification(listOf(noEnd), NotificationKind.STARTED, 0)),
        )
        assertEquals(
            "「サイゼ10%」は残り2日",
            notificationLine(CampaignNotification(listOf(c), NotificationKind.ENDS_SOON, 2)),
        )
        assertEquals(
            "「サイゼ10%」は今日で終了",
            notificationLine(CampaignNotification(listOf(c), NotificationKind.ENDS_SOON, 0)),
        )
    }

    @Test
    fun `文言-複数決済手段は代表とほか件数を添える`() {
        val items = planCampaignNotifications(
            chibaByPayment("PayPay", "au PAY", "d払い", "楽天ペイ", "AEON Pay"),
            today,
        )
        assertEquals(
            "「千葉県キャッシュレス決済キャンペーン」が今日から開始(〜2026/08/30) PayPay ほか4件",
            notificationLine(items.single()),
        )
    }

    @Test
    fun `文言-グループ内で名前が揺れていても1件(代表は先頭)`() {
        // 大田市の実データ相当: 同じ自治体・同じ期間だが display_name が決済手段ごとに揺れている。
        // 名前はグループキーに含めないので畳んだうえで、名前は先頭(campaigns.json の並び順)を採る
        val items = planCampaignNotifications(
            listOf(
                municipal(
                    "oda_paypay", "島根県", "大田市", start = "2026-07-26", end = "2026-08-10",
                    operator = "PayPay", displayName = "おおだキャッシュレスキャンペーン 第5弾",
                ),
                municipal(
                    "oda_aupay", "島根県", "大田市", start = "2026-07-26", end = "2026-08-10",
                    operator = "au PAY", displayName = "おおだキャッシュレスキャンペーン（最大20%戻ってくる）",
                ),
            ),
            today,
        )
        assertEquals(1, items.size)
        assertEquals(
            "「おおだキャッシュレスキャンペーン 第5弾」が今日から開始(〜2026/08/10) PayPay ほか1件",
            notificationLine(items.single()),
        )
    }

    @Test
    fun `文言-決済手段が1つなら件数は添えない`() {
        val items = planCampaignNotifications(chibaByPayment("PayPay"), today)
        assertEquals(
            "「千葉県キャッシュレス決済キャンペーン」が今日から開始(〜2026/08/30)",
            notificationLine(items.single()),
        )
    }

    @Test
    fun `文言-個別通知のタイトルは名前と決済の添え書き`() {
        // 通知の分割(#82): 1キャンペーン=1通知のタイトル。名前はおトクタブと同じ略記優先で、
        // 複数決済にまたがるグループは代表+件数を添える(notificationLine と同じ添え書き)
        val multi = planCampaignNotifications(
            chibaByPayment("PayPay", "au PAY", "d払い", "楽天ペイ", "AEON Pay"),
            today,
        )
        assertEquals(
            "千葉県キャッシュレス決済キャンペーン PayPay ほか4件",
            notificationItemTitle(multi.single()),
        )
        val single = planCampaignNotifications(chibaByPayment("PayPay"), today)
        assertEquals("千葉県キャッシュレス決済キャンペーン", notificationItemTitle(single.single()))
    }

    @Test
    fun `文言-個別通知の本文は状況別で開始には終了日を併記`() {
        val c = promotion("p", displayName = "サイゼ10%", start = "2026-07-26", end = "2026-07-31")
        assertEquals(
            "今日から開始(〜2026/07/31)",
            notificationItemText(CampaignNotification(listOf(c), NotificationKind.STARTED, 0)),
        )
        assertEquals(
            "開始しました(〜2026/07/31)",
            notificationItemText(CampaignNotification(listOf(c), NotificationKind.STARTED, 1)),
        )
        // 終了日の無い施策は併記なし
        val noEnd = promotion("q", displayName = "サイゼ10%", start = "2026-07-26")
        assertEquals(
            "今日から開始",
            notificationItemText(CampaignNotification(listOf(noEnd), NotificationKind.STARTED, 0)),
        )
        assertEquals(
            "残り2日",
            notificationItemText(CampaignNotification(listOf(c), NotificationKind.ENDS_SOON, 2)),
        )
        assertEquals(
            "今日で終了",
            notificationItemText(CampaignNotification(listOf(c), NotificationKind.ENDS_SOON, 0)),
        )
    }

    @Test
    fun `文言-タイトルは1件なら内容そのまま複数なら件数まとめ`() {
        val c = promotion("p", displayName = "サイゼ10%")
        val started = CampaignNotification(listOf(c), NotificationKind.STARTED, 0)
        val ending = CampaignNotification(listOf(c), NotificationKind.ENDS_SOON, 2)
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
        // 分単位の設定値(7:45)もそのまま扱える
        assertEquals(
            Duration.ofMinutes(15),
            delayUntilNextNotifyTime(LocalDateTime.of(2026, 7, 26, 7, 30), notifyTimeMinutes = 7 * 60 + 45),
        )
    }
}
