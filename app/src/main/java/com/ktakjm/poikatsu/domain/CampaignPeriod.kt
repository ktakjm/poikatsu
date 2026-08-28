package com.ktakjm.poikatsu.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// 施策の期間判定と期間文言の共有ロジック(#90)。JudgmentEngine(施策単位)・NotificationPlanner(通知)・
// おトクタブの一覧カード(グループの最早開始/最遅終了)が同じ境界・同じ文言を使うよう、
// 日付ベースの純関数としてここに 1 本化する。recurrence(対象日)は含まない期間の外枠だけの判定。

/**
 * 「終了間近」のしきい値(残り日数がこの値以下)。判定カード・おトクタブの「残りN日」警告色と
 * キャンペーン通知(ENDS_SOON)の基準を揃える
 */
const val ENDS_SOON_DAYS = 3

enum class CampaignStatus { ACTIVE, UPCOMING, EXPIRED }

/** 終了日の翌日から EXPIRED、開始日の前日まで UPCOMING、開始日・終了日当日は ACTIVE。期間なしは常設=ACTIVE */
fun campaignStatus(start: LocalDate?, end: LocalDate?, today: LocalDate): CampaignStatus = when {
    end != null && today > end -> CampaignStatus.EXPIRED
    start != null && today < start -> CampaignStatus.UPCOMING
    else -> CampaignStatus.ACTIVE
}

/** 終了日までの残り日数(終了日当日=0)。終了日なし・終了後は null */
fun daysRemaining(end: LocalDate?, today: LocalDate): Int? {
    val days = ChronoUnit.DAYS.between(today, end ?: return null).toInt()
    return if (days >= 0) days else null
}

/** 開始日までの日数(前日=1)。開始日なし・開始当日以降は null */
fun daysUntilStart(start: LocalDate?, today: LocalDate): Int? {
    val days = ChronoUnit.DAYS.between(today, start ?: return null).toInt()
    return if (days > 0) days else null
}

/** [daysRemaining] が [ENDS_SOON_DAYS] 以下か(null=終了日なし/終了後は false) */
fun isEndingSoon(daysRemaining: Int?): Boolean = daysRemaining != null && daysRemaining <= ENDS_SOON_DAYS

private val periodDateFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")

/** 期間表示用の日付("2026/07/01")。年を省くと年跨ぎ期間が読めなくなるため常に年付きで出す */
fun formatPeriodDate(date: LocalDate): String = date.format(periodDateFormatter)

/**
 * 期間テキスト("2026/07/01〜2026/07/31"。片側だけなら「〜2026/07/31」「2026/07/01〜」)。
 * 開始日・終了日とも無いときは、早期終了があり得る(may_end_early。期限未発表)なら「終了日未定」、
 * そうでなければ「常設」(空欄・「〜」だけの表示にしない)。
 */
fun formatPeriodLabel(start: LocalDate?, end: LocalDate?, openEnded: Boolean): String {
    if (start == null && end == null) return if (openEnded) "終了日未定" else "常設"
    return buildString {
        start?.let { append(formatPeriodDate(it)) }
        append("〜")
        end?.let { append(formatPeriodDate(it)) }
    }
}
