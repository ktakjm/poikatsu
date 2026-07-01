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
import com.ktakjm.poikatsu.ui.theme.warningColor
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// ==================== 一覧画面 ====================

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CampaignPane(
    activeCampaigns: List<Campaign>,
    upcomingCampaigns: List<Campaign>,
    merchantNames: Map<String, String>,
    filter: CampaignFilter,
    onFilterChange: (CampaignFilter) -> Unit,
    onSelectGroup: (List<Campaign>) -> Unit,
) {
    if (activeCampaigns.isEmpty() && upcomingCampaigns.isEmpty()) {
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
        CampaignFilter.MUNICIPAL -> { c -> c.type == "municipal" }
        CampaignFilter.NON_MUNICIPAL -> { c -> c.type != "municipal" }
        CampaignFilter.CARD -> { c -> c.type == "card_promotion" && c.paymentMethodId == null }
        CampaignFilter.QR -> { c -> c.paymentMethodId != null && c.type != "municipal" }
    }
    val activeGroups = remember(activeCampaigns, filter) {
        groupCampaignsForDisplay(activeCampaigns.filter(filterFn))
    }
    val upcomingGroups = remember(upcomingCampaigns, filter) {
        groupCampaignsForDisplay(upcomingCampaigns.filter(filterFn))
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
                CampaignSummaryCard(group, CampaignStatus.ACTIVE, merchantNames, onClick = { onSelectGroup(group) })
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
                CampaignSummaryCard(group, CampaignStatus.UPCOMING, merchantNames, onClick = { onSelectGroup(group) })
            }
        }
        if (activeGroups.isEmpty() && upcomingGroups.isEmpty()) {
            item {
                Text(
                    "このフィルタに一致するキャンペーンはありません。",
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
    onClick: () -> Unit,
) {
    val today = LocalDate.now()
    val first = campaigns.first()
    val title = campaignGroupDisplayTitle(first, merchantNames)
    val hasTimeLimited = campaigns.any { it.periodEnd != null }
    val maxBenefit = campaignGroupMaxBenefit(campaigns)

    val allEnds = campaigns.mapNotNull { c -> c.periodEnd?.let { LocalDate.parse(it) } }
    val allStarts = campaigns.mapNotNull { c -> c.periodStart?.let { LocalDate.parse(it) } }
    val earliestStart = allStarts.minOrNull()
    val latestEnd = allEnds.maxOrNull()
    val periodLabel = buildPeriodLabel(earliestStart, latestEnd)
    val daysInfo = daysInfo(status, today, earliestStart, latestEnd)

    val fallback = MaterialTheme.colorScheme.primary
    val stripeColors = campaigns.mapNotNull { it.brandColor }.distinct()
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

/** グループの最大還元率/特典テキスト(サマリーカード右側用) */
private fun campaignGroupMaxBenefit(campaigns: List<Campaign>): String? {
    val type = BenefitType.fromString(campaigns.first().benefitType)
    val maxRate = campaigns.mapNotNull { it.rateBase }.maxOrNull()
    val maxDiscount = campaigns.mapNotNull { it.discountAmount }.maxOrNull()
    return when {
        type == BenefitType.COUPON_FIXED && maxDiscount != null -> "${maxDiscount}円引き"
        type == BenefitType.COUPON_PERCENT && maxRate != null -> "${trimRate(maxRate)}% OFF"
        maxRate != null -> "${trimRate(maxRate)}%"
        maxDiscount != null -> "${maxDiscount}円"
        else -> null
    }
}

private fun buildPeriodLabel(earliestStart: LocalDate?, latestEnd: LocalDate?): String = buildString {
    if (earliestStart != null) append("${earliestStart.monthValue}/${earliestStart.dayOfMonth}")
    append("〜")
    if (latestEnd != null) append("${latestEnd.monthValue}/${latestEnd.dayOfMonth}")
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
    val (municipal, others) = campaigns.partition { it.type == "municipal" }
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
    CampaignFilter.CARD -> "カード"
    CampaignFilter.QR -> "QR"
}
