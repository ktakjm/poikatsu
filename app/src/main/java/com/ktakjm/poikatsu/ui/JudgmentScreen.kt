package com.ktakjm.poikatsu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.ktakjm.poikatsu.data.LocationHint
import com.ktakjm.poikatsu.domain.BenefitType
import com.ktakjm.poikatsu.domain.BestPaymentOption
import com.ktakjm.poikatsu.domain.CampaignJudgment
import com.ktakjm.poikatsu.domain.StoreEligibility
import com.ktakjm.poikatsu.domain.StoreVerdict
import com.ktakjm.poikatsu.domain.formatBenefit
import com.ktakjm.poikatsu.domain.recurrenceLabel
import com.ktakjm.poikatsu.ui.theme.onWarningContainerColor
import com.ktakjm.poikatsu.ui.theme.warningColor
import com.ktakjm.poikatsu.ui.theme.warningContainerColor

@Composable
internal fun JudgmentDetail(
    selection: MainViewModel.Selection,
    onBack: () -> Unit,
    onOpenStoreCheck: () -> Unit,
    onFindNearby: () -> Unit,
) {
    BackHandler(onBack = onBack)
    CategoryTag(selection.merchant.category)
    Spacer(Modifier.height(12.dp))
    if (selection.displayName != null) {
        Text(
            "店舗情報: Web Services by Yahoo! JAPAN",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
    if (selection.displayName == null) {
        val locationHint = selection.merchant.locationHint
        if (locationHint == null) {
            FilledTonalButton(onClick = onFindNearby, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("近くのこの店を探す")
            }
        } else {
            LocationHintNote(locationHint)
        }
        Spacer(Modifier.height(8.dp))
    }
    if (selection.canCheckStore) {
        Button(onClick = onOpenStoreCheck, modifier = Modifier.fillMaxWidth()) {
            val storeCheckLabel =
                if (selection.displayName != null) "この店舗が対象か調べる" else "対象店舗を調べる"
            Text(storeCheckLabel)
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
    }

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
        if (selection.bestOption != null && selection.judgments.size >= 2) {
            item(key = "__best") { BestOptionBanner(selection.bestOption) }
        }
        items(selection.judgments, key = { it.campaign.id }) { judgment ->
            CampaignJudgmentCard(judgment)
        }
    }
}

@Composable
private fun BestOptionBanner(best: BestPaymentOption) {
    val label = formatBenefit(best.benefitType, best.rate, best.discountAmount) ?: return
    val benefitLabel = "${best.method} $label"
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(20.dp))
            Text("最大おトク率：$benefitLabel", style = MaterialTheme.typography.titleSmall)
            if (best.isTimeLimited && best.daysRemaining != null) {
                Text(
                    "残り${best.daysRemaining}日",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (best.daysRemaining <= 3) warningColor()
                    else MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

// ---- 統一判定カード ----

@Composable
internal fun CampaignJudgmentCard(judgment: CampaignJudgment) {
    val brandColor = parseBrandColor(judgment.brandColor) ?: MaterialTheme.colorScheme.primary
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
            CampaignJudgmentCardBody(judgment, brandColor)
        }
    }
}

@Composable
private fun CampaignJudgmentCardBody(judgment: CampaignJudgment, brandColor: Color) {
    val campaign = judgment.campaign
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BenefitDisplay(judgment)
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BrandBadge(judgment.badgeLabel, brandColor)
                    val pm = judgment.pointMultiplier
                    if (pm != null && pm.badgeLabel.isNotBlank()) {
                        val pmColor = parseBrandColor(pm.color) ?: MaterialTheme.colorScheme.tertiary
                        BrandBadge(pm.badgeLabel, pmColor)
                    }
                    if (campaign.periodEnd != null) {
                        TimeLimitedBadge()
                    }
                }
                Text(
                    campaign.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        RateRulesRows(judgment)
        PeriodRow(campaign)
        RecurrenceRow(judgment)
        if (campaign.paymentInstruction.isNotBlank()) {
            Text("支払い方法：${campaign.paymentInstruction}", style = MaterialTheme.typography.bodyMedium)
        }
        judgment.storeNote?.let {
            Text("条件：$it", style = MaterialTheme.typography.bodyMedium)
        }
        judgment.warnings.forEach {
            NoticeRow(it, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        }
        judgment.exclusionNote?.let {
            NoticeRow(it, warningContainerColor(), onWarningContainerColor())
        }
        if (judgment.mayEndEarly) {
            NoticeRow("予算上限あり。期限より早く終了する可能性があります", warningContainerColor(), onWarningContainerColor())
        }
        if (judgment.benefitType == BenefitType.LOTTERY) {
            Text(
                "抽選のため、当選した場合のみ特典が付与されます(最大おトク率の比較対象外)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        MinPurchaseRow(judgment.minPurchase)
        judgment.usageLimitText?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        }
        CapRow(judgment.perTransactionCap, judgment.periodTotalCap, judgment.capNote)
        Column {
            judgment.storeSearchUrl?.let { url ->
                ExternalLinkButton("対象店舗を確認") { uriHandler.openUri(url) }
            }
            judgment.storeListUrl?.let { url ->
                ExternalLinkButton("公式の対象店舗一覧") { uriHandler.openUri(url) }
            }
            judgment.detailUrl?.let { url ->
                ExternalLinkButton("詳細を見る") { uriHandler.openUri(url) }
            }
            judgment.appPackage?.let { pkg ->
                // ラベルは起動先アプリの名前(appLabel)。バッジ(カード名)だと「三井住友カードアプリを開く」で
                // ウォレットが起動する齟齬が出る
                ExternalLinkButton("${judgment.appLabel ?: "${judgment.badgeLabel}アプリ"}を開く") {
                    val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                    if (intent != null) {
                        context.startActivity(intent)
                    } else {
                        uriHandler.openUri("https://play.google.com/store/apps/details?id=$pkg")
                    }
                }
            }
        }
        judgment.pointMultiplier?.takeIf { judgment.welcatsuApplied && it.appliedNote.isNotBlank() }?.let { pm ->
            Text(
                "※${pm.appliedNote}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        VerifiedDateRow(campaign.verifiedDate)
    }
}

@Composable
private fun BenefitDisplay(judgment: CampaignJudgment) {
    // 抽選は formatBenefit が null(比較対象外)のため専用表示。当選確率・最大額は conditions の文章で持つ
    if (judgment.benefitType == BenefitType.LOTTERY) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "抽選",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "当選で還元",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        return
    }
    val label = formatBenefit(judgment.benefitType, judgment.effectiveRate, judgment.discountAmount) ?: return
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // 段階制(rate_rules)の施策は rate_base = 最大値なので「最大」を冠し、断定に見せない
        if (judgment.campaign.rateRules.isNotEmpty()) {
            Text(
                "最大",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            label.value,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            label.suffix.trim(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * 条件別還元率(rate_rules=段階制)の内訳。「最大○%」の根拠をそのまま列挙する
 * (例: ・中小企業・小規模企業の店舗: 20% 還元 / ・大手企業の店舗: 10% 還元)。
 */
@Composable
private fun RateRulesRows(judgment: CampaignJudgment) {
    val rules = judgment.campaign.rateRules
    if (rules.isEmpty()) return
    Column {
        rules.forEach { rule ->
            val label = formatBenefit(judgment.benefitType, rule.rate, null)?.toString()
                ?: "${rule.rate}%"
            Text(
                "・${rule.condition}: $label",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/**
 * recurrence(繰り返し日付条件)の表示。対象日パターンと「今日は対象日」/「次の対象日」を出す。
 * 「探す」「近く」の判定は対象日のみ出るため通常は「今日は対象日」、キャンペーンタブ詳細では
 * 非対象日にも表示され「次の対象日」の案内になる。
 */
@Composable
private fun RecurrenceRow(judgment: CampaignJudgment) {
    val recurrence = judgment.campaign.recurrence ?: return
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "対象日: ${recurrenceLabel(recurrence)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        if (judgment.todayIsTarget) {
            Text(
                "今日は対象日",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            judgment.nextTargetDate?.let {
                Text(
                    "次の対象日: ${it.monthValue}/${it.dayOfMonth}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---- 位置情報ヒント ----

@Composable
private fun LocationHintNote(hint: LocationHint) {
    val uriHandler = LocalUriHandler.current
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            Icons.Default.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp).padding(top = 2.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                "自販機の位置はこのアプリでは取得できません",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                hint.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ExternalLinkButton(hint.label) { uriHandler.openUri(hint.url) }
        }
    }
}

// ---- 店舗対象判定 ----

@Composable
internal fun StoreCheckScreen(
    storeCheck: MainViewModel.StoreCheckState,
    onBack: () -> Unit,
    onStoreNameChange: (String) -> Unit,
) {
    BackHandler(onBack = onBack)
    OutlinedTextField(
        value = storeCheck.input,
        onValueChange = onStoreNameChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("店舗名を入力") },
        placeholder = { Text("例: ○○駅前店") },
        singleLine = true,
    )
    Spacer(Modifier.height(8.dp))

    if (storeCheck.verdicts.isEmpty()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                "対象か調べたい店舗名を入力してください。公式が対象/対象外を公表している店舗のみ判定します。",
                modifier = Modifier.padding(16.dp),
            )
        }
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(storeCheck.verdicts, key = { it.campaign.id }) { verdict ->
            StoreVerdictCard(verdict)
        }
    }
}

@Composable
private fun StoreVerdictCard(verdict: StoreVerdict) {
    val icon: ImageVector
    val label: String
    val containerColor: Color
    val contentColor: Color
    when (verdict.eligibility) {
        StoreEligibility.ELIGIBLE -> {
            icon = Icons.Default.CheckCircle; label = "対象"
            containerColor = MaterialTheme.colorScheme.primaryContainer
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        }
        StoreEligibility.INELIGIBLE -> {
            icon = Icons.Default.Close; label = "対象外"
            containerColor = MaterialTheme.colorScheme.errorContainer
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        }
        StoreEligibility.UNKNOWN -> {
            icon = Icons.Default.Info; label = "要確認"
            containerColor = warningContainerColor()
            contentColor = onWarningContainerColor()
        }
    }
    val reason = when (verdict.eligibility) {
        StoreEligibility.ELIGIBLE -> "「${verdict.matched}」は公式の対象店舗です"
        StoreEligibility.INELIGIBLE -> "「${verdict.matched}」は公式の対象外店舗です"
        StoreEligibility.UNKNOWN ->
            "公式の対象/対象外リストに掲載がない店舗です。一部対象外店舗があるため、店頭・公式サイトでご確認ください"
    }
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(verdict.campaign.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            Surface(
                color = containerColor,
                contentColor = contentColor,
                shape = RoundedCornerShape(10.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(icon, contentDescription = null)
                    Text(label, style = MaterialTheme.typography.titleLarge)
                }
            }
            Text(reason, style = MaterialTheme.typography.bodyMedium)
            if (verdict.updatedDate.isNotBlank()) {
                val dateLabel = if (verdict.dateIsOfficial) {
                    "公式情報の更新日：${verdict.updatedDate}"
                } else {
                    "公式リスト確認日：${verdict.updatedDate}（公式に更新日記載なし）"
                }
                Text(dateLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            verdict.sourceUrl?.let { url ->
                val uriHandler = LocalUriHandler.current
                ExternalLinkButton("公式の店舗情報を開く") { uriHandler.openUri(url) }
            }
        }
    }
}

@Composable
private fun ExternalLinkButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.defaultMinSize(minHeight = 36.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(4.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
    }
}
