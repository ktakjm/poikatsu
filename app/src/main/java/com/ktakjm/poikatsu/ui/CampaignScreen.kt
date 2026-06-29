package com.ktakjm.poikatsu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.domain.BenefitType
import com.ktakjm.poikatsu.domain.CampaignStatus
import com.ktakjm.poikatsu.ui.theme.warningColor
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CampaignPane(
    activeCampaigns: List<Campaign>,
    upcomingCampaigns: List<Campaign>,
    filter: CampaignFilter,
    onFilterChange: (CampaignFilter) -> Unit,
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
                CampaignCard(group, CampaignStatus.ACTIVE)
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
                CampaignCard(group, CampaignStatus.UPCOMING)
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
    CampaignFilter.CARD -> "カード"
    CampaignFilter.QR -> "QR"
}

@Composable
private fun CampaignCard(campaigns: List<Campaign>, status: CampaignStatus) {
    val today = LocalDate.now()
    val first = campaigns.first()

    val title = if (first.type == "municipal") {
        first.region?.name?.let { "$it キャッシュレス還元" } ?: first.name
    } else {
        first.name
    }

    val allStarts = campaigns.mapNotNull { c -> c.periodStart?.let { LocalDate.parse(it) } }
    val allEnds = campaigns.mapNotNull { c -> c.periodEnd?.let { LocalDate.parse(it) } }
    val earliestStart = allStarts.minOrNull()
    val latestEnd = allEnds.maxOrNull()
    val periodLabel = buildString {
        if (earliestStart != null) append("${earliestStart.monthValue}/${earliestStart.dayOfMonth}")
        append("〜")
        if (latestEnd != null) append("${latestEnd.monthValue}/${latestEnd.dayOfMonth}")
    }

    val daysInfo = when (status) {
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
    val daysLabel = daysInfo?.first
    val isUrgent = daysInfo?.second == true

    val fallback = MaterialTheme.colorScheme.primary
    val stripeColors = campaigns.mapNotNull { it.brandColor }.distinct()
        .mapNotNull { parseBrandColor(it) }
        .ifEmpty { listOf(fallback) }
    val separatorColor = MaterialTheme.colorScheme.surfaceContainerHigh

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .drawBehind {
                        if (stripeColors.size == 1) {
                            drawRect(stripeColors[0])
                        } else {
                            val gap = 1.dp.toPx()
                            val n = stripeColors.size
                            val segH = (size.height - gap * (n - 1)) / n
                            val skew = size.width * 0.5f
                            drawRect(separatorColor)
                            stripeColors.forEachIndexed { i, c ->
                                val segTop = i * (segH + gap)
                                val segBot = segTop + segH
                                val path = Path().apply {
                                    if (i == 0) {
                                        moveTo(0f, 0f); lineTo(size.width, 0f)
                                    } else {
                                        moveTo(0f, segTop + skew); lineTo(size.width, segTop - skew)
                                    }
                                    if (i == n - 1) {
                                        lineTo(size.width, size.height); lineTo(0f, size.height)
                                    } else {
                                        lineTo(size.width, segBot - skew); lineTo(0f, segBot + skew)
                                    }
                                    close()
                                }
                                drawPath(path, c)
                            }
                        }
                    },
            )
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(periodLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    if (daysLabel != null) {
                        Text(
                            daysLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isUrgent) warningColor() else MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                campaigns.forEach { campaign ->
                    CampaignBenefitLine(campaign)
                    campaign.capNote?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    campaign.usageLimitNote?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
                if (first.storeScope == "external") {
                    val uriHandler = LocalUriHandler.current
                    campaigns.forEach { campaign ->
                        val url = campaign.storeSearchUrl ?: campaign.campaignUrl
                        if (url != null) {
                            val label = if (campaigns.size > 1) "${campaign.issuer}で対象店舗を確認 →" else "対象店舗を確認 →"
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { uriHandler.openUri(url) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CampaignBenefitLine(campaign: Campaign) {
    val brandColor = parseBrandColor(campaign.brandColor) ?: MaterialTheme.colorScheme.primary
    val benefitText = campaignBenefitText(campaign)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(10.dp).background(brandColor, CircleShape))
        Text("${campaign.issuer} $benefitText", style = MaterialTheme.typography.bodyMedium)
    }
}

private fun campaignBenefitText(campaign: Campaign): String = benefitText(campaign)
