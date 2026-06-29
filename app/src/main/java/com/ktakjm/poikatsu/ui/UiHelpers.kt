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
import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.DataSource
import com.ktakjm.poikatsu.domain.BenefitType
import java.time.LocalDate

internal fun trimRate(rate: Double): String =
    if (rate == rate.toLong().toDouble()) rate.toLong().toString() else rate.toString()

/** "#RRGGBB" を Color に変換。形式が不正なら null */
internal fun parseBrandColor(hex: String?): Color? {
    val digits = hex?.removePrefix("#") ?: return null
    if (digits.length != 6) return null
    return digits.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
}

/** 背景色に対して読めるコンテンツ色(黒/白)を輝度から選ぶ */
internal fun onColorFor(background: Color): Color =
    if (background.luminance() > 0.5f) Color.Black else Color.White

internal fun dataStatusLabel(updatedAt: String, source: DataSource?): String {
    val sourceLabel = when (source) {
        DataSource.REMOTE -> "最新データ取得済み"
        DataSource.CACHE -> "前回取得データ(オフライン?)"
        DataSource.BUNDLED -> "同梱データ(オフライン?)"
        null -> ""
    }
    return "データ更新日: $updatedAt $sourceLabel"
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
internal fun benefitText(campaign: Campaign): String {
    val type = BenefitType.fromString(campaign.benefitType)
    return when {
        type == BenefitType.COUPON_FIXED && campaign.discountAmount != null ->
            "${campaign.discountAmount}円引き"
        type == BenefitType.COUPON_PERCENT && campaign.rateBase != null ->
            "${trimRate(campaign.rateBase)}% OFF"
        type == BenefitType.REBATE && campaign.discountAmount != null ->
            "${campaign.discountAmount}円相当 還元"
        campaign.rateBase != null ->
            "${trimRate(campaign.rateBase)}% 還元"
        else -> ""
    }
}

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
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
