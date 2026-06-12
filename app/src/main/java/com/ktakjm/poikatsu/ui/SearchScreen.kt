package com.ktakjm.poikatsu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ktakjm.poikatsu.data.DataSource
import com.ktakjm.poikatsu.data.Merchant
import com.ktakjm.poikatsu.domain.Judgment

@Composable
fun PoikatsuApp(modifier: Modifier = Modifier, viewModel: MainViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        when {
            state.loading -> Centered { CircularProgressIndicator() }
            state.error != null -> Centered { Text(state.error!!, color = MaterialTheme.colorScheme.error) }
            state.selection != null -> JudgmentDetail(
                selection = state.selection!!,
                onBack = viewModel::onBack,
            )
            else -> SearchPane(
                query = state.query,
                categories = state.categories,
                selectedCategories = state.selectedCategories,
                results = state.results,
                dataStatus = dataStatusLabel(state.dataUpdatedAt, state.dataSource),
                onQueryChange = viewModel::onQueryChange,
                onToggleCategory = viewModel::onToggleCategory,
                onSelect = viewModel::onSelect,
            )
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchPane(
    query: String,
    categories: List<String>,
    selectedCategories: Set<String>,
    results: List<Merchant>,
    dataStatus: String,
    onQueryChange: (String) -> Unit,
    onToggleCategory: (String) -> Unit,
    onSelect: (Merchant) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        placeholder = { Text("店名を入力(例: マック、サイゼ)") },
        singleLine = true,
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        categories.forEach { category ->
            FilterChip(
                selected = category in selectedCategories,
                onClick = { onToggleCategory(category) },
                label = { Text(category) },
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    when {
        query.isBlank() && selectedCategories.isEmpty() -> Text(
            "チェーン名を入力するか、カテゴリを選択すると、どのカードで払うのが得かを表示します。\n$dataStatus",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        results.isEmpty() -> Text(
            if (query.isBlank()) "選択中のカテゴリに店舗がありません。"
            else "「$query」に一致する店舗が見つかりませんでした。登録済みの高還元施策の対象外の可能性があります。",
            style = MaterialTheme.typography.bodyMedium,
        )
        else -> LazyColumn {
            items(results, key = { it.id }) { merchant ->
                ListItem(
                    headlineContent = { Text(merchant.name) },
                    supportingContent = { Text(merchant.category) },
                    trailingContent = { Text("判定 >") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(merchant) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun JudgmentDetail(
    selection: MainViewModel.Selection,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
        }
        Text(selection.merchant.name, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.width(8.dp))
        AssistChip(onClick = {}, label = { Text(selection.merchant.category) })
    }
    Spacer(Modifier.height(8.dp))

    if (selection.judgments.isEmpty()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                "登録済みの高還元施策の対象外です。通常還元率のカード・QR決済を利用してください。",
                modifier = Modifier.padding(16.dp),
            )
        }
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(selection.judgments, key = { it.campaign.id }) { judgment ->
            JudgmentCard(judgment)
        }
    }
}

@Composable
private fun JudgmentCard(judgment: Judgment) {
    val brandColor = parseBrandColor(judgment.campaign.brandColor) ?: MaterialTheme.colorScheme.primary
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(8.dp)
                    .fillMaxHeight()
                    .background(brandColor)
            )
            JudgmentCardBody(judgment, brandColor)
        }
    }
}

@Composable
private fun JudgmentCardBody(judgment: Judgment, brandColor: Color) {
    val campaign = judgment.campaign
    val rule = judgment.rule
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${trimRate(judgment.effectiveRate)}%",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Surface(
                        color = brandColor,
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            judgment.card?.cardName ?: campaign.issuer,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                    Text(
                        campaign.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            if (campaign.rateMax > judgment.effectiveRate) {
                Text(
                    "条件達成で最大${trimRate(campaign.rateMax)}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Text("支払い方法: ${campaign.paymentInstruction}", style = MaterialTheme.typography.bodyMedium)
            rule.note?.let {
                Text("この店の条件: $it", style = MaterialTheme.typography.bodyMedium)
            }
            judgment.warnings.forEach {
                Text("⚠ $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            rule.exclusionNote?.let {
                Text("⚠ $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
            }
            campaign.monthlyCapNote?.let {
                Text("上限: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            Text(
                "情報確認日: ${campaign.verifiedDate} / 最新の条件は公式サイトで確認してください",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
    }
}

private fun trimRate(rate: Double): String =
    if (rate == rate.toLong().toDouble()) rate.toLong().toString() else rate.toString()

private fun dataStatusLabel(updatedAt: String, source: DataSource?): String {
    val sourceLabel = when (source) {
        DataSource.REMOTE -> "最新データ取得済み"
        DataSource.CACHE -> "前回取得データ(オフライン?)"
        DataSource.BUNDLED -> "同梱データ(オフライン?)"
        null -> ""
    }
    return "データ更新日: $updatedAt $sourceLabel"
}

/** "#RRGGBB" を Color に変換。形式が不正なら null */
private fun parseBrandColor(hex: String?): Color? {
    val digits = hex?.removePrefix("#") ?: return null
    if (digits.length != 6) return null
    return digits.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
}
