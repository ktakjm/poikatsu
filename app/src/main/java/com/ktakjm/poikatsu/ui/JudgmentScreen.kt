package com.ktakjm.poikatsu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.LocationHint
import com.ktakjm.poikatsu.domain.BenefitType
import com.ktakjm.poikatsu.domain.BestPaymentOption
import com.ktakjm.poikatsu.domain.Judgment
import com.ktakjm.poikatsu.domain.QrJudgment
import com.ktakjm.poikatsu.domain.StoreEligibility
import com.ktakjm.poikatsu.domain.StoreVerdict
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
                if (selection.displayName != null) "この店舗が対象か調べる →" else "対象店舗を調べる →"
            Text(storeCheckLabel)
        }
        Spacer(Modifier.height(8.dp))
    }

    if (selection.judgments.isEmpty() && selection.qrJudgments.isEmpty()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                "登録済みの高還元施策の対象外です。通常還元率のカード・QR決済を利用してください。",
                modifier = Modifier.padding(16.dp),
            )
        }
        return
    }

    val totalCount = selection.judgments.size + selection.qrJudgments.size
    val hasBothSections = selection.judgments.isNotEmpty() && selection.qrJudgments.isNotEmpty()

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (selection.bestOption != null && totalCount >= 2) {
            item(key = "__best") { BestOptionBanner(selection.bestOption) }
        }
        if (selection.judgments.isNotEmpty()) {
            if (hasBothSections) {
                item(key = "__card_header") { SectionHeader("カード決済") }
            }
            items(selection.judgments, key = { it.campaign.id }) { judgment ->
                JudgmentCard(judgment)
            }
        }
        if (selection.qrJudgments.isNotEmpty()) {
            item(key = "__qr_header") { SectionHeader("QR 決済") }
            items(selection.qrJudgments, key = { "qr_${it.campaign.id}" }) { qrJudgment ->
                QrJudgmentCard(qrJudgment)
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun BestOptionBanner(best: BestPaymentOption) {
    val benefitLabel = when {
        best.benefitType == BenefitType.COUPON_FIXED && best.discountAmount != null ->
            "${best.method} ${best.discountAmount}円引き"
        best.benefitType == BenefitType.COUPON_PERCENT && best.rate != null ->
            "${best.method} ${trimRate(best.rate)}% OFF"
        best.rate != null ->
            "${best.method} ${trimRate(best.rate)}% 還元"
        else -> return
    }
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
            Text("最もお得: $benefitLabel", style = MaterialTheme.typography.titleSmall)
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

// ---- カード判定カード ----

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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        BrandBadge(judgment.card?.cardName ?: campaign.issuer, brandColor)
                        val pm = judgment.card?.pointMultiplier
                        if (pm != null) {
                            val welciaColor = parseBrandColor(pm.color) ?: MaterialTheme.colorScheme.tertiary
                            BrandBadge("ウエル活利用可", welciaColor)
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
            PeriodRow(campaign)
            Text("支払い方法: ${campaign.paymentInstruction}", style = MaterialTheme.typography.bodyMedium)
            rule.note?.let {
                Text("この店の条件: $it", style = MaterialTheme.typography.bodyMedium)
            }
            judgment.warnings.forEach {
                NoticeRow(it, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
            }
            rule.exclusionNote?.let {
                NoticeRow(it, warningContainerColor(), onWarningContainerColor())
            }
            rule.storeListUrl?.let { url ->
                val uriHandler = LocalUriHandler.current
                Text(
                    "公式の対象店舗一覧を開く →",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { uriHandler.openUri(url) },
                )
            }
            CapRow(campaign.perTransactionCap, campaign.periodTotalCap, campaign.capNote ?: campaign.monthlyCapNote)
            ConditionsList(campaign.conditions, campaign.minPurchase)
            judgment.card?.takeIf { it.pointMultiplier != null }?.let { card ->
                Text(
                    if (card.welcatsuApplied) "※還元率はウエル活利用時の実質還元率"
                    else "※WAONポイントに交換する事でウエル活利用可能",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            VerifiedDateRow(campaign.verifiedDate)
    }
}

// ---- QR 判定カード ----

@Composable
private fun QrJudgmentCard(judgment: QrJudgment) {
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
            QrJudgmentCardBody(judgment, brandColor)
        }
    }
}

@Composable
private fun QrJudgmentCardBody(judgment: QrJudgment, brandColor: Color) {
    val campaign = judgment.campaign
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            QrBenefitDisplay(judgment)
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BrandBadge(judgment.paymentMethod.name, brandColor)
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
        PeriodRow(campaign)
        if (campaign.paymentInstruction.isNotBlank()) {
            Text("支払い方法: ${campaign.paymentInstruction}", style = MaterialTheme.typography.bodyMedium)
        }
        MinPurchaseRow(judgment.minPurchase)
        campaign.usageLimitNote?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        } ?: judgment.usageLimit?.let {
            Text("お一人様${it}回まで", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        }
        judgment.daysRemaining?.let { days ->
            if (days <= 3) {
                NoticeRow("残り${days}日", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        CapRow(judgment.perTransactionCap, judgment.periodTotalCap, campaign.capNote)
        ConditionsList(campaign.conditions, judgment.minPurchase)
        QrExternalLinks(campaign, judgment)
        VerifiedDateRow(campaign.verifiedDate)
    }
}

@Composable
private fun QrBenefitDisplay(judgment: QrJudgment) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        when (judgment.benefitType) {
            BenefitType.COUPON_FIXED -> {
                Text(
                    "${judgment.discountAmount ?: 0}円",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text("引き", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            BenefitType.COUPON_PERCENT -> {
                Text(
                    "${trimRate(judgment.effectiveRate ?: 0.0)}%",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text("OFF", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            BenefitType.REBATE -> {
                if (judgment.discountAmount != null) {
                    Text(
                        "${judgment.discountAmount}円",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text("還元", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                } else {
                    Text(
                        "${trimRate(judgment.effectiveRate ?: 0.0)}%",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun QrExternalLinks(campaign: Campaign, judgment: QrJudgment) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    campaign.campaignUrl?.let { url ->
        Text(
            "キャンペーン詳細 →",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { uriHandler.openUri(url) },
        )
    }
    val appPackage = judgment.paymentMethod.appPackage
    if (appPackage.isNotBlank()) {
        Text(
            "${judgment.paymentMethod.name}アプリを開く →",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable {
                val intent = context.packageManager.getLaunchIntentForPackage(appPackage)
                if (intent != null) {
                    context.startActivity(intent)
                } else {
                    uriHandler.openUri("https://play.google.com/store/apps/details?id=$appPackage")
                }
            },
        )
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
            Spacer(Modifier.height(4.dp))
            Text(
                "${hint.label} →",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { uriHandler.openUri(hint.url) },
            )
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
                    "公式情報の更新日: ${verdict.updatedDate}"
                } else {
                    "公式リスト確認日: ${verdict.updatedDate}(公式に更新日記載なし)"
                }
                Text(dateLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            verdict.sourceUrl?.let { url ->
                val uriHandler = LocalUriHandler.current
                Text(
                    "公式の店舗情報を開く →",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { uriHandler.openUri(url) },
                )
            }
        }
    }
}
