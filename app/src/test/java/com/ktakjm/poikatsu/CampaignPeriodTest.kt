package com.ktakjm.poikatsu

import com.ktakjm.poikatsu.domain.CampaignStatus
import com.ktakjm.poikatsu.domain.ENDS_SOON_DAYS
import com.ktakjm.poikatsu.domain.campaignStatus
import com.ktakjm.poikatsu.domain.daysRemaining
import com.ktakjm.poikatsu.domain.daysUntilStart
import com.ktakjm.poikatsu.domain.formatPeriodLabel
import com.ktakjm.poikatsu.domain.isEndingSoon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 期間判定・期間文言の共有ロジック(domain/CampaignPeriod.kt。#90)。
 * JudgmentEngine(施策単位)・NotificationPlanner(通知)・おトクタブ(グループの最早開始/最遅終了)が
 * 同じ関数を使うので、境界(開始日当日・終了日当日・翌日)をここで固定する。
 */
class CampaignPeriodTest {

    private val today = LocalDate.of(2026, 8, 29)
    private val start = LocalDate.of(2026, 8, 20)
    private val end = LocalDate.of(2026, 8, 31)

    @Test
    fun `ステータス-終了日の翌日から EXPIRED、開始日の前日まで UPCOMING、当日は ACTIVE`() {
        assertEquals(CampaignStatus.ACTIVE, campaignStatus(start, end, today))
        assertEquals(CampaignStatus.ACTIVE, campaignStatus(start, end, end))
        assertEquals(CampaignStatus.EXPIRED, campaignStatus(start, end, end.plusDays(1)))
        assertEquals(CampaignStatus.ACTIVE, campaignStatus(start, end, start))
        assertEquals(CampaignStatus.UPCOMING, campaignStatus(start, end, start.minusDays(1)))
        // 期間なし=常設
        assertEquals(CampaignStatus.ACTIVE, campaignStatus(null, null, today))
    }

    @Test
    fun `残り日数-終了日当日は 0、終了後は null、終了日なしは null`() {
        assertEquals(2, daysRemaining(end, today))
        assertEquals(0, daysRemaining(end, end))
        assertNull(daysRemaining(end, end.plusDays(1)))
        assertNull(daysRemaining(null, today))
    }

    @Test
    fun `開始まで-開始前日は 1、開始当日以降は null`() {
        assertEquals(1, daysUntilStart(start, start.minusDays(1)))
        assertNull(daysUntilStart(start, start))
        assertNull(daysUntilStart(start, today))
        assertNull(daysUntilStart(null, today))
    }

    @Test
    fun `終了間近-しきい値以下だけ true`() {
        assertTrue(isEndingSoon(ENDS_SOON_DAYS))
        assertTrue(isEndingSoon(0))
        assertFalse(isEndingSoon(ENDS_SOON_DAYS + 1))
        assertFalse(isEndingSoon(null))
    }

    @Test
    fun `期間ラベル-両端・片側・両方なし`() {
        assertEquals("2026/08/20〜2026/08/31", formatPeriodLabel(start, end, openEnded = false))
        assertEquals("〜2026/08/31", formatPeriodLabel(null, end, openEnded = false))
        assertEquals("2026/08/20〜", formatPeriodLabel(start, null, openEnded = true))
        // 開始・終了とも無い: 期限未発表(may_end_early)なら「終了日未定」、そうでなければ「常設」
        assertEquals("終了日未定", formatPeriodLabel(null, null, openEnded = true))
        assertEquals("常設", formatPeriodLabel(null, null, openEnded = false))
    }
}
