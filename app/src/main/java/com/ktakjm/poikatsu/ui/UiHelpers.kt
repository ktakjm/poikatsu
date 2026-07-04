package com.ktakjm.poikatsu.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Path
import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.DataSource
import com.ktakjm.poikatsu.domain.BenefitType
import com.ktakjm.poikatsu.domain.CampaignType
import com.ktakjm.poikatsu.domain.campaignType
import com.ktakjm.poikatsu.domain.formatBenefit
import com.ktakjm.poikatsu.domain.trimRate
import java.time.LocalDate

/** "#RRGGBB" を Color に変換。形式が不正なら null */
internal fun parseBrandColor(hex: String?): Color? {
    val digits = hex?.removePrefix("#") ?: return null
    if (digits.length != 6) return null
    return digits.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
}

/** 背景色に対して読めるコンテンツ色(黒/白)を輝度から選ぶ */
internal fun onColorFor(background: Color): Color =
    if (background.luminance() > 0.5f) Color.Black else Color.White

internal fun dataStatusLabel(updatedAt: String, source: DataSource?, useTestData: Boolean = false): String {
    val sourceLabel = when (source) {
        DataSource.REMOTE -> "最新データ取得済み"
        DataSource.CACHE -> "前回取得データ(オフライン?)"
        DataSource.BUNDLED -> "同梱データ(オフライン?)"
        null -> ""
    }
    val testLabel = if (useTestData) " [テストデータ]" else ""
    return "データ更新日：$updatedAt $sourceLabel$testLabel"
}

/**
 * 警告・注意のトーナル面表示(アイコン + 文)。container/content の対で error(致命) / warning(注意) を出し分ける。
 * グレーのカード地に色文字を直接乗せるとコントラストが不足するため、専用の淡い面の上に濃い文字で出す。
 * アイコン/文字の色は Surface の contentColor から自動で引き継ぐ。
 */
@Composable
internal fun NoticeRow(text: String, containerColor: Color, contentColor: Color) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(18.dp).padding(top = 2.dp),
            )
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** 非インタラクティブなカテゴリ表示。押せる見た目(Chip)を持たせない静的タグ */
@Composable
internal fun CategoryTag(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
internal fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/** 地図以外の画面共通の縦並びコンテナ。従来ルートにあった横16dpパディングをここに移譲 */
@Composable
internal fun PaddedColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        content = content,
    )
}

/** Campaign の特典テキスト(rate% / 円引き / % OFF / 円還元)。検索結果カード・判定詳細で共用 */
internal fun benefitText(campaign: Campaign): String =
    formatBenefit(BenefitType.fromString(campaign.benefitType), campaign.rateBase, campaign.discountAmount)
        ?.toString() ?: ""

/** 期間テキスト("7/1〜7/31")。常設(period null)なら null を返す */
internal fun formatPeriod(campaign: Campaign): String? {
    if (campaign.periodStart == null && campaign.periodEnd == null) return null
    return buildString {
        campaign.periodStart?.let {
            val d = LocalDate.parse(it)
            append("${d.monthValue}/${d.dayOfMonth}")
        }
        append("〜")
        campaign.periodEnd?.let {
            val d = LocalDate.parse(it)
            append("${d.monthValue}/${d.dayOfMonth}")
        }
    }
}

/** 上限額のフォーマット(1万 / 2千 / 500円) */
internal fun formatCap(yen: Int): String = when {
    yen >= 10000 && yen % 10000 == 0 -> "${yen / 10000}万"
    yen >= 1000 && yen % 1000 == 0 -> "${yen / 1000}千"
    else -> "${yen}円"
}

/** 期間限定バッジ */
@Composable
internal fun TimeLimitedBadge(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(4.dp),
        modifier = modifier,
    ) {
        Text(
            "期間限定",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

// ---- 共通カード部品 ----

/** カード左端のブランドカラーストライプ(単色 or 斜め分割マルチカラー) */
@Composable
internal fun StripeBar(stripeColors: List<Color>, separatorColor: Color) {
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
}

/** ブランドカラー背景の決済手段バッジ */
@Composable
internal fun BrandBadge(label: String, brandColor: Color) {
    Surface(color = brandColor, shape = RoundedCornerShape(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = onColorFor(brandColor),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

// ---- 共通: 期間・上限・条件 (CampaignScreen / JudgmentScreen 共用) ----

@Composable
internal fun PeriodRow(campaign: Campaign) {
    val period = formatPeriod(campaign) ?: return
    PeriodRow(period)
}

@Composable
internal fun PeriodRow(periodText: String) {
    Text("期間: $periodText", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
}

@Composable
internal fun CapRow(perTransaction: Int?, periodTotal: Int?, capNote: String?) {
    val capText = buildCapText(perTransaction, periodTotal)
    val text = when {
        capText != null && capNote != null -> "$capText ($capNote)"
        capText != null -> capText
        capNote != null -> capNote
        else -> return
    }
    Text("上限: $text", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
}

internal fun buildCapText(perTransaction: Int?, periodTotal: Int?): String? {
    if (perTransaction == null && periodTotal == null) return null
    return buildString {
        perTransaction?.let { append("1回あたり${formatCap(it)}") }
        if (perTransaction != null && periodTotal != null) append("、")
        periodTotal?.let { append("期間合計${formatCap(it)}") }
    }
}

@Composable
internal fun MinPurchaseRow(minPurchase: Int?) {
    if (minPurchase == null) return
    Text("${minPurchase}円(税込)以上の決済で適用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
}

@Composable
internal fun ConditionsList(conditions: List<String>, minPurchase: Int?) {
    if (conditions.isEmpty()) return
    val minPurchaseStr = minPurchase?.let { "${it}円" }
    conditions
        .filter { cond -> minPurchaseStr == null || !cond.contains(minPurchaseStr) }
        .forEach { condition ->
            Text("・$condition", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
}

/**
 * キャンペーングループの表示タイトル。
 * 自治体: "都道府県名 自治体名"、それ以外: merchant_rules の先頭 merchant 名(なければ campaign.name)
 */
internal fun campaignGroupDisplayTitle(first: Campaign, merchantNames: Map<String, String>): String =
    if (first.campaignType == CampaignType.MUNICIPAL) {
        val prefecture = first.region?.prefecture ?: ""
        val name = first.region?.name ?: first.name
        if (prefecture.isNotBlank()) "$prefecture $name" else name
    } else {
        first.merchantRules.firstOrNull()?.merchantId
            ?.let { merchantNames[it] }
            ?: first.name
    }

@Composable
internal fun VerifiedDateRow(verifiedDate: String) {
    if (verifiedDate.isBlank()) return
    Text(
        "情報確認日：$verifiedDate / 最新の条件は公式サイトで確認してください",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
    )
}
