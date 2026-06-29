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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.ktakjm.poikatsu.data.LocationHint
import com.ktakjm.poikatsu.domain.Judgment
import com.ktakjm.poikatsu.domain.StoreEligibility
import com.ktakjm.poikatsu.domain.StoreVerdict
import com.ktakjm.poikatsu.ui.theme.onWarningContainerColor
import com.ktakjm.poikatsu.ui.theme.warningContainerColor

@Composable
internal fun JudgmentDetail(
    selection: MainViewModel.Selection,
    onBack: () -> Unit,
    onOpenStoreCheck: () -> Unit,
    onFindNearby: () -> Unit,
) {
    BackHandler(onBack = onBack)
    // 店名は TopAppBar に表示済み。ここではカテゴリを静的タグで補足する
    CategoryTag(selection.merchant.category)
    Spacer(Modifier.height(12.dp))
    // 近隣(YOLP)由来で開いた場合のみ、店舗名は YOLP データなので帰属表示を出す。
    // 名前検索由来(displayName == null)は merchants.json のデータなのでクレジットは出さない。
    if (selection.displayName != null) {
        Text(
            "店舗情報: Web Services by Yahoo! JAPAN",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
    // ブリッジ(探す→近く): 名前検索から来たとき(displayName == null)だけ「近くで探す」を出す。
    // 近隣由来(displayName != null)は既に地図上にいるので出さない。
    if (selection.displayName == null) {
        val locationHint = selection.merchant.locationHint
        if (locationHint == null) {
            FilledTonalButton(onClick = onFindNearby, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("近くのこの店を探す")
            }
        } else {
            // 位置情報を持たない発行体(自販機など)は「近く」が行き止まりになるので、
            // 代わりに位置を確認できる外部アプリ/サイトへ案内する
            LocationHintNote(locationHint)
        }
        Spacer(Modifier.height(8.dp))
    }
    // 公式が対象/対象外を言い切っているチェーンだけ、店舗単位の判定画面へ遷移できる。
    // 遷移元で文言を出し分ける: 地図ピン由来(displayName != null)は特定の1店舗を見ているので
    // 「この店舗が対象か」、名前検索由来(displayName == null)はチェーン全体なので「対象店舗を調べる」。
    if (selection.canCheckStore) {
        Button(onClick = onOpenStoreCheck, modifier = Modifier.fillMaxWidth()) {
            val storeCheckLabel =
                if (selection.displayName != null) "この店舗が対象か調べる →" else "対象店舗を調べる →"
            Text(storeCheckLabel)
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
        items(selection.judgments, key = { it.campaign.id }) { judgment ->
            JudgmentCard(judgment)
        }
    }
}

/**
 * 位置情報を持たない発行体(自販機など)向けの案内行。「近くのこの店を探す」の代わりに、
 * 位置を確認できる外部アプリ/サイト(例: Coke ON 公式アプリ)への導線を出す。
 * 失敗・警告ではなく案内なので Info アイコン+onSurfaceVariant 系の控えめな見せ方にする。
 */
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
                        Surface(
                            color = brandColor,
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                judgment.card?.cardName ?: campaign.issuer,
                                style = MaterialTheme.typography.labelMedium,
                                // ブランドカラーは任意の色なので、白固定ではなく輝度から読める文字色を選ぶ
                                color = onColorFor(brandColor),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                        // ウエル活フラグを持つ決済手段は、ON/OFF によらず常に識別バッジを付ける(色=ウエルシアのロゴ色)
                        val pm = judgment.card?.pointMultiplier
                        if (pm != null) {
                            val welciaColor = parseBrandColor(pm.color) ?: MaterialTheme.colorScheme.tertiary
                            Surface(color = welciaColor, shape = RoundedCornerShape(4.dp)) {
                                Text(
                                    "ウエル活利用可",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = onColorFor(welciaColor),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }
                    Text(
                        campaign.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            Text("支払い方法: ${campaign.paymentInstruction}", style = MaterialTheme.typography.bodyMedium)
            rule.note?.let {
                Text("この店の条件: $it", style = MaterialTheme.typography.bodyMedium)
            }
            // 致命的な注意(警告)は errorContainer、対象外があり得る等の注意は warningContainer で出し分ける
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
            campaign.monthlyCapNote?.let {
                Text("上限: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            // ウエル活フラグのある決済手段は、ON/OFF で実質還元率の意味づけを補足する
            judgment.card?.takeIf { it.pointMultiplier != null }?.let { card ->
                Text(
                    if (card.welcatsuApplied) "※還元率はウエル活利用時の実質還元率"
                    else "※WAONポイントに交換する事でウエル活利用可能",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Text(
                "情報確認日: ${campaign.verifiedDate} / 最新の条件は公式サイトで確認してください",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
    }
}

/** 公式が対象/対象外を言い切っているチェーン専用の、店舗単位の対象判定画面 */
@Composable
internal fun StoreCheckScreen(
    storeCheck: MainViewModel.StoreCheckState,
    onBack: () -> Unit,
    onStoreNameChange: (String) -> Unit,
) {
    BackHandler(onBack = onBack)
    // 画面タイトル(○○ 店舗判定)は TopAppBar 側に表示済み
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

// 状態は絵文字ではなく Material アイコン + セマンティックカラーのトーナル面(ピル)で表す。
// 色文字をカード地に直接乗せず、container/content の対で出すことでコントラストを担保する(テーマにも追従)。
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
