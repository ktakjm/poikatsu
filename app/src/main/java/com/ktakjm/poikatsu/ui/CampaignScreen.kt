package com.ktakjm.poikatsu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.domain.BenefitType
import com.ktakjm.poikatsu.domain.CampaignJudgment
import com.ktakjm.poikatsu.domain.CampaignStatus
import com.ktakjm.poikatsu.domain.CampaignType
import com.ktakjm.poikatsu.domain.campaignType
import com.ktakjm.poikatsu.domain.formatBenefit
import com.ktakjm.poikatsu.domain.isTargetDay
import com.ktakjm.poikatsu.domain.isTimeLimited
import com.ktakjm.poikatsu.domain.nextTargetDay
import com.ktakjm.poikatsu.domain.recurrenceLabel
import com.ktakjm.poikatsu.ui.theme.onWarningContainerColor
import com.ktakjm.poikatsu.ui.theme.warningColor
import com.ktakjm.poikatsu.ui.theme.warningContainerColor
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// ==================== 一覧画面 ====================

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CampaignPane(
    activeCampaigns: List<Campaign>,
    upcomingCampaigns: List<Campaign>,
    merchantNames: Map<String, String>,
    campaignColors: Map<String, String>,
    filter: CampaignFilter,
    onFilterChange: (CampaignFilter) -> Unit,
    /** 登録エリアによる絞り込みチップを出すか(自治体登録あり かつ マスタ読込済みのとき) */
    showRegionChip: Boolean,
    /** 「登録地域のみ」絞り込み中か(既定 ON。OFF=すべて表示) */
    regionFilterOn: Boolean,
    onToggleRegionFilter: () -> Unit,
    onSelectGroup: (List<Campaign>) -> Unit,
) {
    // 地域絞り込みで 0 件のときは全画面の空表示にせず、チップ行と件数メッセージを出す
    // (「すべて」へ切り替える導線を残すため)
    if (activeCampaigns.isEmpty() && upcomingCampaigns.isEmpty() && !(showRegionChip && regionFilterOn)) {
        Centered {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 32.dp),
            ) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(16.dp))
                Text("期間限定キャンペーンはありません", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "カード会社の期間限定キャンペーンや自治体のキャッシュレス還元施策が登録されると、ここに表示されます。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        return
    }

    val filterFn: (Campaign) -> Boolean = when (filter) {
        CampaignFilter.ALL -> { _ -> true }
        CampaignFilter.MUNICIPAL -> { c -> c.campaignType == CampaignType.MUNICIPAL }
        CampaignFilter.NON_MUNICIPAL -> { c -> c.campaignType != CampaignType.MUNICIPAL }
    }
    val allActiveGroups = remember(activeCampaigns, filter) {
        groupCampaignsForDisplay(activeCampaigns.filter(filterFn))
    }
    val upcomingGroups = remember(upcomingCampaigns, filter) {
        groupCampaignsForDisplay(upcomingCampaigns.filter(filterFn))
    }
    // recurrence 施策で今日が対象日でないグループは「開催中」と混ぜず別セクションに出す
    // (期間内=開催中だが今日は使えないため。カード内で「次の対象日」を案内する)
    val today = LocalDate.now()
    val (activeGroups, offDayGroups) = allActiveGroups.partition { group ->
        group.any { isTargetDay(it, today) }
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CampaignFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { onFilterChange(f) },
                        label = { Text(campaignFilterLabel(f)) },
                    )
                }
                if (showRegionChip) {
                    FilterChip(
                        selected = regionFilterOn,
                        onClick = onToggleRegionFilter,
                        label = { Text("登録地域のみ") },
                    )
                }
            }
        }
        if (activeGroups.isNotEmpty()) {
            item {
                Text(
                    "開催中",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(activeGroups, key = { it.first().id }) { group ->
                CampaignSummaryCard(group, CampaignStatus.ACTIVE, merchantNames, campaignColors, onClick = { onSelectGroup(group) })
            }
        }
        if (offDayGroups.isNotEmpty()) {
            item {
                Text(
                    "開催中（本日対象外）",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            items(offDayGroups, key = { "offday_${it.first().id}" }) { group ->
                CampaignSummaryCard(group, CampaignStatus.ACTIVE, merchantNames, campaignColors, onClick = { onSelectGroup(group) })
            }
        }
        if (upcomingGroups.isNotEmpty()) {
            item {
                Text(
                    "もうすぐ開始",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            items(upcomingGroups, key = { "upcoming_${it.first().id}" }) { group ->
                CampaignSummaryCard(group, CampaignStatus.UPCOMING, merchantNames, campaignColors, onClick = { onSelectGroup(group) })
            }
        }
        if (allActiveGroups.isEmpty() && upcomingGroups.isEmpty()) {
            item {
                Text(
                    if (showRegionChip && regionFilterOn) {
                        "登録地域に該当するキャンペーンはありません。「登録地域のみ」を外すと全て表示されます。"
                    } else {
                        "このフィルタに一致するキャンペーンはありません。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}

/**
 * サマリーカード: SearchResultCard と同じレイアウト。
 * 左: 名前 + 期間限定バッジ / 期間
 * 右: 最大還元率
 */
@Composable
private fun CampaignSummaryCard(
    campaigns: List<Campaign>,
    status: CampaignStatus,
    merchantNames: Map<String, String>,
    campaignColors: Map<String, String>,
    onClick: () -> Unit,
) {
    val today = LocalDate.now()
    val first = campaigns.first()
    val title = campaignGroupDisplayTitle(first, merchantNames)
    val hasTimeLimited = campaigns.any { it.isTimeLimited }
    val maxBenefit = campaignGroupMaxBenefit(campaigns)

    val allEnds = campaigns.mapNotNull { c -> c.periodEnd?.let { LocalDate.parse(it) } }
    val allStarts = campaigns.mapNotNull { c -> c.periodStart?.let { LocalDate.parse(it) } }
    val earliestStart = allStarts.minOrNull()
    val latestEnd = allEnds.maxOrNull()
    val periodLabel = buildPeriodLabel(earliestStart, latestEnd)
    val daysInfo = daysInfo(status, today, earliestStart, latestEnd)

    val fallback = MaterialTheme.colorScheme.primary
    val stripeColors = campaigns.mapNotNull { campaignColors[it.id] }.distinct()
        .mapNotNull { parseBrandColor(it) }
        .ifEmpty { listOf(fallback) }
    val separatorColor = MaterialTheme.colorScheme.surfaceContainerHigh

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            StripeBar(stripeColors, separatorColor)
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(title, style = MaterialTheme.typography.bodyLarge)
                        if (hasTimeLimited) {
                            TimeLimitedBadge()
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(periodLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        daysInfo?.let { (label, urgent) ->
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (urgent) warningColor() else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // 早期終了があり得る施策では「残り○日」が断定に見えないよう注記を添える
                    if (campaigns.any { it.mayEndEarly }) {
                        Surface(
                            color = warningContainerColor(),
                            contentColor = onWarningContainerColor(),
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                "※早期終了の可能性あり",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    recurrenceInfo(campaigns, status, today)?.let { (label, isToday) ->
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isToday) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (maxBenefit != null) {
                    Text(
                        maxBenefit,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

// ==================== 詳細画面 ====================

@Composable
internal fun CampaignDetail(
    judgments: List<CampaignJudgment>,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        items(judgments, key = { it.campaign.id }) { judgment ->
            CampaignJudgmentCard(judgment)
        }
    }
}

// ==================== 共通ヘルパー ====================

/**
 * グループの最大還元率/特典テキスト(サマリーカード右側用)。抽選は比較に載せず「抽選」と表示する。
 * 表示する数字が「変動する率の最大値」のとき(店舗別 rate_override・条件別 rate_rules・
 * グループ内で率の異なる複数施策)は「最大」を冠し、一律の率と誤認されないようにする。
 */
private fun campaignGroupMaxBenefit(campaigns: List<Campaign>): String? {
    val comparable = campaigns.filter { BenefitType.fromString(it.benefitType) != BenefitType.LOTTERY }
    if (comparable.isEmpty()) return "抽選"
    val type = BenefitType.fromString(comparable.first().benefitType)
    val allRates = comparable.flatMap { c ->
        c.merchantRules.mapNotNull { it.rateOverride } +
            c.rateRules.map { it.rate } +
            listOfNotNull(c.rateBase)
    }
    val maxRate = allRates.maxOrNull()
    val maxDiscount = comparable.mapNotNull { it.discountAmount }.maxOrNull()
    val label = formatBenefit(type, maxRate, maxDiscount)?.toString() ?: return null
    val ratesVary = allRates.distinct().size > 1 || comparable.any { it.rateRules.isNotEmpty() }
    return if (maxRate != null && ratesVary) "最大$label" else label
}

private fun buildPeriodLabel(earliestStart: LocalDate?, latestEnd: LocalDate?): String = buildString {
    if (earliestStart != null) append(formatPeriodDate(earliestStart))
    append("〜")
    if (latestEnd != null) append(formatPeriodDate(latestEnd))
}

/**
 * recurrence 施策のサマリー表示(「対象日: 毎週金・土曜 | 今日は対象日」等)。
 * グループ内に recurrence 施策が無い、または開催前なら null。Boolean は「今日が対象日」か。
 */
private fun recurrenceInfo(
    campaigns: List<Campaign>,
    status: CampaignStatus,
    today: LocalDate,
): Pair<String, Boolean>? {
    if (status != CampaignStatus.ACTIVE) return null
    val campaign = campaigns.firstOrNull { it.recurrence != null } ?: return null
    val pattern = recurrenceLabel(campaign.recurrence ?: return null)
    return if (isTargetDay(campaign, today)) {
        "対象日: $pattern | 今日は対象日" to true
    } else {
        val next = nextTargetDay(campaign, today)
        val nextLabel = next?.let { " | 次の対象日: ${it.monthValue}/${it.dayOfMonth}" }.orEmpty()
        "対象日: $pattern$nextLabel" to false
    }
}

private fun daysInfo(status: CampaignStatus, today: LocalDate, earliestStart: LocalDate?, latestEnd: LocalDate?): Pair<String, Boolean>? =
    when (status) {
        CampaignStatus.ACTIVE -> latestEnd?.let { end ->
            val days = ChronoUnit.DAYS.between(today, end).toInt()
            if (days >= 0) "残り${days}日" to (days <= 3) else null
        }
        CampaignStatus.UPCOMING -> earliestStart?.let { start ->
            val days = ChronoUnit.DAYS.between(today, start).toInt()
            if (days > 0) "あと${days}日で開始" to false else null
        }
        else -> null
    }

private fun groupCampaignsForDisplay(campaigns: List<Campaign>): List<List<Campaign>> {
    val (municipal, others) = campaigns.partition { it.campaignType == CampaignType.MUNICIPAL }
    val municipalGroups = municipal
        .groupBy { it.region?.name ?: it.id }
        .values.toList()
    val otherGroups = others.map { listOf(it) }
    return municipalGroups + otherGroups
}

private fun campaignFilterLabel(filter: CampaignFilter): String = when (filter) {
    CampaignFilter.ALL -> "全て"
    CampaignFilter.MUNICIPAL -> "自治体"
    CampaignFilter.NON_MUNICIPAL -> "自治体以外"
}
