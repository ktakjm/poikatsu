package com.ktakjm.poikatsu.domain

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

/** 「終了間近」として通知に含める残り日数(この日数以下)。UI の警告表示「残り3日」と基準を揃える */
const val ENDS_SOON_DAYS = 3

/**
 * 開始通知が拾う開始からの経過日数。日次ジョブは省電力制約や圏外でスキップされ得るため、
 * 開始当日を逃しても翌日までは開始として通知する(dedupKey により二重通知はしない)
 */
const val STARTED_LOOKBACK_DAYS = 1

enum class NotificationKind { STARTED, ENDS_SOON }

data class CampaignNotification(
    val campaign: Campaign,
    val kind: NotificationKind,
    /** STARTED: 開始からの経過日数(0=今日開始)。ENDS_SOON: 残り日数(0=今日で終了) */
    val days: Int,
) {
    /**
     * 再通知抑止キー(通知済みとして DataStore に記録する)。期間の日付を含めることで、
     * 施策が延長・改定されたら別キーになり改めて通知される。
     */
    val dedupKey: String
        get() = when (kind) {
            NotificationKind.STARTED -> "start:${campaign.id}:${campaign.periodStart}"
            NotificationKind.ENDS_SOON -> "end:${campaign.id}:${campaign.periodEnd}"
        }
}

/**
 * 通知対象の施策を「自分に関係するもの」へ絞る。期間限定タブ(全件表示あり)より狭く、
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
): List<Campaign> {
    val timeLimited = campaigns.filter { it.campaignType != CampaignType.CARD_PROGRAM }
    val municipal = municipalCampaignsForAreas(timeLimited, registeredAreas, master)
    val promotions = timeLimited.filter { campaign ->
        campaign.campaignType == CampaignType.PROMOTION &&
            (campaign.isCustom || backedByUserPayments(campaign, ownedCards, enabledQrIds))
    }
    return (municipal + promotions).distinctBy { it.id }
}

/** 施策の紐付け先決済手段をユーザーが持っているか(resolveCard / judgeQr のフィルタと同じ基準) */
private fun backedByUserPayments(
    campaign: Campaign,
    ownedCards: List<PaymentCard>,
    enabledQrIds: Set<String>,
): Boolean = when {
    campaign.paymentMethodId != null -> campaign.paymentMethodId in enabledQrIds
    campaign.cardId != null -> ownedCards.any { it.id == campaign.cardId }
    campaign.cardBrand != null ->
        ownedCards.any { it.brand.equals(campaign.cardBrand, ignoreCase = true) }
    else -> true // 決済手段の紐付けが無い施策は決済側の条件では絞らない
}

/**
 * targets のうち今日通知すべきものを返す。開始と終了間近が同時に該当する短期施策は
 * 開始のみ(1施策1行)。period_start の無い施策は「開始済み扱い」なので開始通知は出ない。
 * may_end_early で period_end 未定の施策は終了日が分からないため終了間近も出ない(#6 追記)。
 */
fun planCampaignNotifications(targets: List<Campaign>, today: LocalDate): List<CampaignNotification> =
    targets.mapNotNull { campaign ->
        val start = campaign.periodStart?.let { JudgmentEngine.parseDate(it) }
        val end = campaign.periodEnd?.let { JudgmentEngine.parseDate(it) }
        if (start != null && today < start) return@mapNotNull null // 開始前
        if (end != null && today > end) return@mapNotNull null // 終了後
        val sinceStart = start?.let { ChronoUnit.DAYS.between(it, today).toInt() }
        val remaining = end?.let { ChronoUnit.DAYS.between(today, it).toInt() }
        when {
            sinceStart != null && sinceStart <= STARTED_LOOKBACK_DAYS ->
                CampaignNotification(campaign, NotificationKind.STARTED, sinceStart)
            remaining != null && remaining <= ENDS_SOON_DAYS ->
                CampaignNotification(campaign, NotificationKind.ENDS_SOON, remaining)
            else -> null
        }
    }

/** 通知本文の1行分。タイトルは期間限定タブと同じ略記優先(display_name → name) */
fun notificationLine(notification: CampaignNotification): String {
    val title = notification.campaign.displayName ?: notification.campaign.name
    return when (notification.kind) {
        NotificationKind.STARTED ->
            if (notification.days == 0) "「$title」が今日から開始" else "「$title」が開始しました"
        NotificationKind.ENDS_SOON ->
            if (notification.days == 0) "「$title」は今日で終了" else "「$title」は残り${notification.days}日"
    }
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
