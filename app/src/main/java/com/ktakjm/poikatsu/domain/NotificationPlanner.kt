package com.ktakjm.poikatsu.domain

import com.ktakjm.poikatsu.data.Attribution
import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.MunicipalityMaster
import com.ktakjm.poikatsu.data.PaymentCard
import com.ktakjm.poikatsu.data.RegisteredArea
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

// キャンペーン通知(#6)の判定・文言。Worker(notification/ の Android 層)から呼ぶが、
// 判定自体は純 Kotlin に保ちユニットテストを維持する。通知タイミングは「開始日」と
// 「終了間近」のみ(recurrence の対象日通知は毎週発火して高頻度になるため出さない)。

/** 通知時刻(0時からの分)の既定値 8:00。AppSettings / バックアップ / UiState の既定を揃える */
const val DEFAULT_NOTIFY_TIME_MINUTES = 8 * 60

/** 通知時刻を 0:00〜23:59 に丸める(設定 API とバックアップ復元の両経路で同じ範囲にする) */
fun clampNotifyTimeMinutes(minutesOfDay: Int): Int = minutesOfDay.coerceIn(0, 24 * 60 - 1)

/**
 * 開始通知が拾う開始からの経過日数。日次ジョブは省電力制約や圏外でスキップされ得るため、
 * 開始当日を逃しても翌日までは開始として通知する(dedupKey により二重通知はしない)
 */
const val STARTED_LOOKBACK_DAYS = 1

enum class NotificationKind { STARTED, ENDS_SOON }

data class CampaignNotification(
    /**
     * 1キャンペーン分の施策。campaigns.json は (施策 × 決済手段) 単位なので、決済手段ごとに
     * 分かれたエントリがここに束ねて入る([notificationGroupKey])。名前と期間はグループ内で
     * 揃っている前提で、先頭を代表として扱う。
     */
    val campaigns: List<Campaign>,
    val kind: NotificationKind,
    /** STARTED: 開始からの経過日数(0=今日開始)。ENDS_SOON: 残り日数(0=今日で終了) */
    val days: Int,
) {
    /** 名前・期間の取り出しに使う代表の施策 */
    val campaign: Campaign get() = campaigns.first()

    /**
     * 再通知抑止キー(通知済みとして DataStore に記録する)。期間の日付を含めることで、
     * 施策が延長・改定されたら別キーになり改めて通知される。
     *
     * 施策 id でなくグループキーで持つ(#67)。決済手段ごとに分かれたエントリで別々に記録すると、
     * 後から決済手段が1つ増えただけでキャンペーン全体が再通知されてしまうため。期間はグループの
     * 全メンバーで揃っている([notificationGroupKey])ので代表の日付をそのまま使える。
     */
    val dedupKey: String
        get() = when (kind) {
            NotificationKind.STARTED -> "start:${campaignGroupKey(campaign)}:${campaign.periodStart}"
            NotificationKind.ENDS_SOON -> "end:${campaignGroupKey(campaign)}:${campaign.periodEnd}"
        }
}

/**
 * 通知対象の施策を「自分に関係するもの」へ絞る。おトクタブ(全件表示あり)より狭く、
 * 能動的に知らせる通知なので誤配を避けて「出さない」側に倒す:
 * - 自治体施策: 登録エリアに厳密一致するもののみ([municipalCampaignsForAreas] と同じ基準)
 * - promotion: カスタムキャンペーン(本人登録)と、所有カード/ブランド/利用中コード決済に紐づくもの
 * - card_program(常設)は通知対象外
 */
fun notificationTargets(
    campaigns: List<Campaign>,
    ownedCards: List<PaymentCard>,
    enabledQrIds: Set<String>,
    registeredAreas: List<RegisteredArea>,
    master: MunicipalityMaster,
    memberships: Set<String> = emptySet(),
): List<Campaign> {
    val timeLimited = campaigns.filter { it.campaignType != CampaignType.CARD_PROGRAM }
    val municipal = municipalCampaignsForAreas(timeLimited, registeredAreas, master)
    val promotions = timeLimited.filter { campaign ->
        campaign.campaignType == CampaignType.PROMOTION &&
            (campaign.isCustom || backedByUserPayments(campaign, ownedCards, enabledQrIds, memberships))
    }
    return (municipal + promotions).distinctBy { it.id }
}

/** 施策の紐付け先(決済手段・プログラム会員 #39)をユーザーが持っているか(resolveCard / judgeQr / judgePrograms のフィルタと同じ基準) */
private fun backedByUserPayments(
    campaign: Campaign,
    ownedCards: List<PaymentCard>,
    enabledQrIds: Set<String>,
    memberships: Set<String>,
): Boolean = when (val a = campaign.attribution) {
    is Attribution.Qr -> a.id in enabledQrIds
    is Attribution.Card -> ownedCards.any { it.id == a.id }
    is Attribution.Brand -> ownedCards.any { it.brand.equals(a.name, ignoreCase = true) }
    is Attribution.Program -> a.id in memberships
    null -> true // 決済手段の紐付けが無い施策は決済側の条件では絞らない
}

/**
 * 通知を1件に畳む単位。表示グループ([campaignGroupKey])に期間を足したもの——決済手段によって
 * 期間が違うキャンペーンをグループの最早/最遅へ丸めると、後から始まる決済手段の開始を
 * 取りこぼすため、期間が違えば別の通知にする(現データは全グループで期間が揃っており実質1件)。
 */
private fun notificationGroupKey(campaign: Campaign): String =
    "${campaignGroupKey(campaign)}|${campaign.periodStart}|${campaign.periodEnd}"

/**
 * targets のうち今日通知すべきものを返す。まず [notificationGroupKey] でキャンペーン単位に
 * 畳んでから判定するので、決済手段ごとに分かれた施策も1行になる(#67)。開始と終了間近が
 * 同時に該当する短期施策は開始のみ(1キャンペーン1行)。period_start の無い施策は「開始済み扱い」
 * なので開始通知は出ない。may_end_early で period_end 未定の施策は終了日が分からないため
 * 終了間近も出ない(#6 追記)。
 */
fun planCampaignNotifications(targets: List<Campaign>, today: LocalDate): List<CampaignNotification> =
    targets.groupBy(::notificationGroupKey).values.mapNotNull { group ->
        val campaign = group.first()
        val start = campaign.periodStart?.let { JudgmentEngine.parseDate(it) }
        val end = campaign.periodEnd?.let { JudgmentEngine.parseDate(it) }
        if (campaignStatus(start, end, today) != CampaignStatus.ACTIVE) return@mapNotNull null // 開始前・終了後
        val sinceStart = start?.let { ChronoUnit.DAYS.between(it, today).toInt() }
        val remaining = daysRemaining(end, today)
        when {
            sinceStart != null && sinceStart <= STARTED_LOOKBACK_DAYS ->
                CampaignNotification(group, NotificationKind.STARTED, sinceStart)
            isEndingSoon(remaining) ->
                CampaignNotification(group, NotificationKind.ENDS_SOON, remaining!!)
            else -> null
        }
    }

/**
 * 通知本文の1行分(サマリ通知の InboxStyle 用)。タイトルはおトクタブと同じ略記優先
 * (display_name → name)で、状況文言は個別通知の本文([notificationItemText])と共通。
 */
fun notificationLine(notification: CampaignNotification): String {
    val title = notification.campaign.displayName ?: notification.campaign.name
    val connective = when (notification.kind) {
        NotificationKind.STARTED -> "が"
        NotificationKind.ENDS_SOON -> "は"
    }
    return "「$title」$connective${notificationItemText(notification)}" +
        paymentsSuffix(notification.campaigns)
}

/**
 * 個別通知(1キャンペーン=1通知。#82)のタイトル。おトクタブと同じ略記優先の名前に、
 * 決済手段の添え書き([paymentsSuffix])を付ける。状況(開始/終了間近)は本文
 * ([notificationItemText])側に出す。
 */
fun notificationItemTitle(notification: CampaignNotification): String {
    val title = notification.campaign.displayName ?: notification.campaign.name
    return title + paymentsSuffix(notification.campaigns)
}

/**
 * 個別通知の本文(状況)。開始通知には終了日を併記する——開始と終了間近が同日に重なる短期施策は
 * 開始通知しか出ない(1キャンペーン1通知で開始優先)ため、これが無いと終了時期が通知から
 * 読めない。年は省略しない(年跨ぎ期間が読めなくなるため。[notificationLine] と同じ方針)。
 */
fun notificationItemText(notification: CampaignNotification): String = when (notification.kind) {
    NotificationKind.STARTED -> {
        val until = notification.campaign.periodEnd?.let { "(〜${it.replace('-', '/')})" } ?: ""
        if (notification.days == 0) "今日から開始$until" else "開始しました$until"
    }
    NotificationKind.ENDS_SOON ->
        if (notification.days == 0) "今日で終了" else "残り${notification.days}日"
}

/**
 * まとめた決済手段の添え書き(「 PayPay ほか4件」)。おトクタブの一覧カードが決済手段を見せて
 * いるのに対し、通知は1行に畳むため代表+件数で示す。1つだけのときは何も足さない。
 * 名前は施策の operator(自治体施策は決済事業者名、カスタムキャンペーンは登録した決済手段名)。
 */
private fun paymentsSuffix(campaigns: List<Campaign>): String {
    val operators = campaigns.map { it.operator }.filter { it.isNotBlank() }.distinct()
    return if (operators.size < 2) "" else " ${operators.first()} ほか${operators.size - 1}件"
}

/** 通知タイトル。1件ならその内容をそのまま、複数なら種別ごとの件数のまとめ */
fun notificationTitle(items: List<CampaignNotification>): String {
    items.singleOrNull()?.let { return notificationLine(it) }
    val started = items.count { it.kind == NotificationKind.STARTED }
    val ending = items.count { it.kind == NotificationKind.ENDS_SOON }
    val parts = buildList {
        if (started > 0) add("開始${started}件")
        if (ending > 0) add("まもなく終了${ending}件")
    }
    return "キャンペーン ${parts.joinToString("・")}"
}

/**
 * 次の通知時刻(次に notifyTimeMinutes=0時からの分 を迎える時点)までの遅延。
 * 日次ジョブのスケジュール・毎回の再アンカー(CampaignNotifications.schedule)に使う。
 */
fun delayUntilNextNotifyTime(now: LocalDateTime, notifyTimeMinutes: Int): Duration {
    val todayRun = now.toLocalDate().atTime(LocalTime.of(notifyTimeMinutes / 60, notifyTimeMinutes % 60))
    val next = if (now < todayRun) todayRun else todayRun.plusDays(1)
    return Duration.between(now, next)
}
