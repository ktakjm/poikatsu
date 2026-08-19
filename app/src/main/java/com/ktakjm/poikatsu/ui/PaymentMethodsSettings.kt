package com.ktakjm.poikatsu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ktakjm.poikatsu.data.CardClass
import com.ktakjm.poikatsu.data.CustomCard
import com.ktakjm.poikatsu.data.PointBalance
import com.ktakjm.poikatsu.domain.trimRate
import com.ktakjm.poikatsu.ui.theme.onWarningContainerColor
import com.ktakjm.poikatsu.ui.theme.warningContainerColor
import java.time.LocalDate

/**
 * お支払い方法サブページ(#47)。マイカード / 国際ブランド / コード決済 / ポイントの 4 セクションを
 * 統合する(いずれも「何を持っているか」の登録で意味的に同族)。値は DataStore 由来
 * (MainViewModel 経由)で、変更は即 ViewModel の setter へ流す。
 */
@Composable
internal fun PaymentMethodsSettingsPage(
    cards: List<MainViewModel.CardSetting>,
    customCards: List<CustomCard>,
    brands: List<MainViewModel.BrandSetting>,
    qrPayments: List<MainViewModel.QrPaymentSetting>,
    pointCurrencies: List<MainViewModel.PointCurrencySetting>,
    onBack: () -> Unit,
    onCardOwnedChange: (String, Boolean) -> Unit,
    onCardRateChange: (String, Double?) -> Unit,
    onCardBrandChange: (String, String) -> Unit,
    onCardClassChange: (String, String) -> Unit,
    onAddCustomCard: (name: String, color: String?, brand: String) -> Unit,
    onUpdateCustomCard: (CustomCard) -> Unit,
    onRemoveCustomCard: (String) -> Unit,
    onBrandOwnedChange: (String, Boolean) -> Unit,
    onQrEnabledChange: (String, Boolean) -> Unit,
    onPointProgramMemberChange: (String, Boolean) -> Unit,
    onPointMultiplierChange: (String, Boolean) -> Unit,
    onPointValueChange: (String, Double?) -> Unit,
    onPointBalanceChange: (String, PointBalance?) -> Unit,
) {
    BackHandler(onBack = onBack)

    // カスタムカードの追加/編集ダイアログ。NEW_CUSTOM_CARD(id 空のセンチネル)なら新規、null なら非表示
    var editingCustomCard by remember { mutableStateOf<CustomCard?>(null) }
    var deletingCustomCard by remember { mutableStateOf<CustomCard?>(null) }
    // 1pt 価値ピッカー(#13: 通貨単位)。編集対象の通貨(null なら非表示)
    var editingValueCurrency by remember { mutableStateOf<MainViewModel.PointCurrencySetting?>(null) }
    // 期間限定ポイントの残高・失効日入力(#13: 通貨ごとに1件)。編集対象の通貨(null なら非表示)
    var editingBalanceCurrency by remember { mutableStateOf<MainViewModel.PointCurrencySetting?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // --- マイカード ---
        SettingsSectionHeader("マイカード")
        Text(
            "持っているカードにチェックを入れてください。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        // カード1枚=面1つ。所有カードは設定行が下に膨らんで次のカードと地続きに見えるため、
        // 面の切れ目でグループ境界を示す(グループ化の背景は SettingsGroupSurface の KDoc)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            cards.forEach { card ->
                SettingsGroupSurface {
                    CardSettingItem(
                        card = card,
                        onOwnedChange = { onCardOwnedChange(card.cardId, it) },
                        onRateChange = { onCardRateChange(card.cardId, it) },
                        onBrandChange = { onCardBrandChange(card.cardId, it) },
                        onClassChange = { onCardClassChange(card.cardId, it) },
                    )
                }
            }
            // カタログ外のカスタムカード。識別はロゴでなく色(方針どおり)なので、色スウォッチを先頭に出す
            customCards.forEach { card ->
                SettingsGroupSurface {
                    ListItem(
                        headlineContent = { Text(card.name) },
                        supportingContent = {
                            Text(
                                if (card.brand.isBlank()) "カスタムカード"
                                else "カスタムカード・${card.brand}"
                            )
                        },
                        leadingContent = { CustomCardColorDot(card.color) },
                        trailingContent = {
                            IconButton(onClick = { deletingCustomCard = card }) {
                                Icon(Icons.Default.Close, contentDescription = "削除")
                            }
                        },
                        colors = transparentListItemColors(),
                        modifier = Modifier.clickable { editingCustomCard = card },
                    )
                }
            }
            SettingsGroupSurface {
                ListItem(
                    headlineContent = { Text("カードを追加") },
                    leadingContent = {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    colors = transparentListItemColors(),
                    modifier = Modifier.clickable { editingCustomCard = NEW_CUSTOM_CARD },
                )
            }
        }

        // --- 国際ブランド(イシュアー不問のブランド施策向け。事前登録できるよう常時出す) ---
        if (brands.isNotEmpty()) {
            SettingsSectionHeader("国際ブランド")
            Text(
                "マイカード以外で持っているカードの国際ブランドにチェックを入れてください。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            // 1行×N の均質なチェックリストで境界問題は起きないため、面にはしない
            // (面は「複数行に膨らむ設定グループの境界」=マイカード/ポイント。SettingsGroupSurface の KDoc 参照)
            brands.forEach { b ->
                ListItem(
                    headlineContent = { NameWithColorDot(b.brand, b.color) },
                    leadingContent = {
                        Checkbox(
                            checked = b.owned,
                            onCheckedChange = { onBrandOwnedChange(b.brand, it) },
                        )
                    },
                    modifier = Modifier.clickable { onBrandOwnedChange(b.brand, !b.owned) },
                    colors = transparentListItemColors(),
                )
            }
        }

        // --- コード決済 ---
        SettingsSectionHeader("コード決済")
        if (qrPayments.isEmpty()) {
            Text(
                "利用可能なコード決済がありません",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else {
            Text(
                "利用中のコード決済にチェックを入れてください。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            qrPayments.forEach { qr ->
                ListItem(
                    headlineContent = { NameWithColorDot(qr.name, qr.brandColor) },
                    leadingContent = {
                        Checkbox(
                            checked = qr.enabled,
                            onCheckedChange = { onQrEnabledChange(qr.id, it) },
                        )
                    },
                    modifier = Modifier.clickable { onQrEnabledChange(qr.id, !qr.enabled) },
                    colors = transparentListItemColors(),
                )
            }
        }

        // --- ポイント(#39: 通貨単位の会員登録・ポイント倍率) ---
        if (pointCurrencies.isNotEmpty()) {
            SettingsSectionHeader("ポイント")
            Text(
                "会員になっているポイントにチェックを入れてください。カード提示型の特典の表示に使われます。" +
                    "1ptの価値を設定すると、判定が実質還元率で比較されます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            // 通貨1つ=面1つ(マイカードと同型)。会員・倍率・1pt価値・期間限定ポイントの複数行が
            // どの通貨に属するかを面の切れ目で示す(実機フィードバック。#13)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                pointCurrencies.forEach { currency ->
                    SettingsGroupSurface {
                        // 会員チェック行(会員プログラムのある通貨のみ)。倍率だけの通貨(Vポイント等)は
                        // 会員チェック無しで名前行だけ出し、その下に倍率チェックをぶら下げる
                        if (currency.membershipProgram) {
                            ListItem(
                                headlineContent = { NameWithColorDot(currency.name, currency.brandColor) },
                                leadingContent = {
                                    Checkbox(
                                        checked = currency.member,
                                        onCheckedChange = { onPointProgramMemberChange(currency.id, it) },
                                    )
                                },
                                modifier = Modifier.clickable {
                                    onPointProgramMemberChange(currency.id, !currency.member)
                                },
                                colors = transparentListItemColors(),
                            )
                        } else {
                            ListItem(
                                headlineContent = { NameWithColorDot(currency.name, currency.brandColor) },
                                colors = transparentListItemColors(),
                            )
                        }
                        // ポイント倍率チェック(旧: カード行のウエル活チェック。#39 で通貨単位へ移設)
                        currency.pointMultiplier?.let { pm ->
                            val note: (@Composable () -> Unit)? = if (
                                currency.multiplierEnabled && currency.multiplierCardNames.isNotEmpty()
                            ) {
                                {
                                    Text(
                                        "${currency.multiplierCardNames.joinToString("・")}の還元率を" +
                                            "×${trimRate(pm.factor)}で表示中",
                                    )
                                }
                            } else {
                                null
                            }
                            ListItem(
                                headlineContent = { Text(pm.label) },
                                leadingContent = {
                                    Checkbox(
                                        checked = currency.multiplierEnabled,
                                        onCheckedChange = { onPointMultiplierChange(currency.id, it) },
                                    )
                                },
                                supportingContent = note,
                                colors = transparentListItemColors(),
                                modifier = Modifier.padding(start = 24.dp).clickable {
                                    onPointMultiplierChange(currency.id, !currency.multiplierEnabled)
                                },
                            )
                        }
                        // 1pt の価値(#13: 全通貨でユーザー設定可能。既定 1.0 円)
                        ListItem(
                            headlineContent = { Text(currency.pointValueConfig?.label ?: "1ptの価値") },
                            supportingContent = currency.pointValueConfig?.note
                                ?.takeIf { it.isNotBlank() }?.let { { Text(it) } },
                            trailingContent = {
                                Text(
                                    "1pt=${trimRate(currency.valueYen)}円",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            },
                            colors = transparentListItemColors(),
                            modifier = Modifier.padding(start = 24.dp)
                                .clickable { editingValueCurrency = currency },
                        )
                        // 期間限定ポイントの残高・失効日(#13: 通貨ごとに1件=直近失効分)
                        ListItem(
                            headlineContent = { Text("期間限定ポイント") },
                            supportingContent = {
                                val balance = currency.balance
                                when {
                                    balance == null -> Text("残高と失効日を登録すると、失効前にお知らせします")
                                    // 失効済みの警告は色文字でなく下の CardSettingWarning(container 対)で見せる
                                    currency.balanceExpired -> Text(
                                        "${"%,d".format(balance.balancePt)}pt・${balance.expiryDate} に失効",
                                    )
                                    else -> Text(
                                        "${"%,d".format(balance.balancePt)}pt・${balance.expiryDate} まで",
                                    )
                                }
                            },
                            colors = transparentListItemColors(),
                            modifier = Modifier.padding(start = 24.dp)
                                .clickable { editingBalanceCurrency = currency },
                        )
                        if (currency.balanceExpired) {
                            CardSettingWarning("失効済みです。残高を入れ直してください")
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    editingCustomCard?.let { editing ->
        CustomCardEditDialog(
            initial = editing.takeUnless { it.id.isEmpty() },
            brandOptions = brands.map { it.brand },
            onConfirm = { name, color, brand ->
                if (editing.id.isEmpty()) {
                    onAddCustomCard(name, color, brand)
                } else {
                    onUpdateCustomCard(editing.copy(name = name, color = color, brand = brand))
                }
                editingCustomCard = null
            },
            onDismiss = { editingCustomCard = null },
        )
    }

    deletingCustomCard?.let { card ->
        AlertDialog(
            onDismissRequest = { deletingCustomCard = null },
            title = { Text("カードを削除しますか？") },
            text = {
                Text("「${card.name}」を削除します。", style = MaterialTheme.typography.bodyMedium)
            },
            confirmButton = {
                TextButton(onClick = {
                    onRemoveCustomCard(card.id)
                    deletingCustomCard = null
                }) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { deletingCustomCard = null }) { Text("キャンセル") }
            },
        )
    }

    editingValueCurrency?.let { currency ->
        PointValuePickerDialog(
            currency = currency,
            onDismiss = { editingValueCurrency = null },
            onConfirm = {
                onPointValueChange(currency.id, it)
                editingValueCurrency = null
            },
        )
    }

    editingBalanceCurrency?.let { currency ->
        PointBalanceEditDialog(
            currency = currency,
            onDismiss = { editingBalanceCurrency = null },
            onConfirm = {
                onPointBalanceChange(currency.id, it)
                editingBalanceCurrency = null
            },
        )
    }
}

/** カスタムカード追加ダイアログを新規モードで開くためのセンチネル(id 空)。 */
private val NEW_CUSTOM_CARD = CustomCard(id = "", name = "")

/**
 * 設定グループ1つ分のトーナル面。所有カードは設定行が下に膨らみ、フラットなリストでは
 * 次のカード行と地続きに見えてグループ境界が読めないため、面の切れ目で境界を示す。
 * 用途は「複数行に膨らむ可変高グループの境界」に限る(マイカードのカード1枚=面1つ、
 * ポイントの通貨1つ=面1つ。後者は #13 の実機フィードバックで追加)。
 * 1行×N の均質なチェックリスト(国際ブランド/コード決済)や他の設定ページには使わない
 * ——スタイルとして広げ始めると設定タブ全体を grouped 化しないと一貫しなくなるため。
 * 色は surfaceContainerHigh 固定: 横画面の詳細ペイン(#78 で surfaceContainerLow 化)の上でも
 * 2段差、縦画面(素の surface)では3段差が付く。当初は背景+1段の surfaceContainer だったが、
 * ペイン上の1段差はライト/ダークとも実機で読めなかったため1段上げた(2026-08-13)。
 * 角丸はペイン想定の 16dp より1段小さい 12dp(入れ子の M3 定石)。
 */
@Composable
private fun SettingsGroupSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(content = content)
    }
}

/**
 * カード設定行の警告文。以前は warningColor() の色文字だったが、グループ化でグレーの
 * surfaceContainer 地に乗るようになったため、規約どおり container 対の面で見せる
 * (色文字を直接グレー地に乗せない)。インデントは設定行(start=24dp)に合わせる。
 */
@Composable
private fun CardSettingWarning(text: String) {
    Box(Modifier.padding(start = 24.dp, end = 16.dp, bottom = 8.dp)) {
        NoticeRow(
            text = text,
            containerColor = warningContainerColor(),
            contentColor = onWarningContainerColor(),
        )
    }
}

/** カスタムカードの色パレット(Material 系の定番12色)。これ以外はカラーコード入力で指定する */
private val CUSTOM_CARD_PALETTE = listOf(
    "#D32F2F", "#C2185B", "#F57C00", "#FBC02D",
    "#388E3C", "#00796B", "#1976D2", "#303F9F",
    "#7B1FA2", "#5D4037", "#607D8B", "#212121",
)

/** "#RRGGBB"(# 省略・小文字も可)を正規化する。形式外は null */
private fun normalizeHexColor(text: String): String? {
    val digits = text.trim().removePrefix("#")
    return if (digits.matches(Regex("[0-9a-fA-F]{6}"))) "#${digits.uppercase()}" else null
}

/** カスタムカードの識別色スウォッチ(未選択はデフォルト色)。 */
@Composable
private fun CustomCardColorDot(color: String?) {
    Box(
        Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(parseBrandColor(color ?: CustomCard.DEFAULT_COLOR) ?: Color.Gray),
    )
}

/**
 * 名前の左に発行体の識別色のドットを添えた headline。チェックボックス付きの行(カード・
 * ブランド・QR 決済)は leading が埋まっているため、名前側に色を併記する。
 * サイズはカスタムカード行の leading ドット([CustomCardColorDot])と同じ 24dp に揃える。
 * 色未定義(null)の項目はドットを出さない(グレー等で埋めると誤った識別色に見えるため)。
 */
@Composable
private fun NameWithColorDot(name: String, color: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        parseBrandColor(color)?.let { parsed ->
            Box(Modifier.size(24.dp).clip(CircleShape).background(parsed))
            Spacer(Modifier.width(8.dp))
        }
        Text(name)
    }
}

/**
 * カスタムカードの追加(initial=null)/編集ダイアログ。
 * 色はパレットのタップとカラーコード入力のどちらでも指定でき、内部状態は HEX 文字列1本に集約する
 * (パレットのタップも同じ文字列に落とす)。空欄=未選択で、保存時に null(デフォルト色)になる。
 */
@Composable
private fun CustomCardEditDialog(
    initial: CustomCard?,
    brandOptions: List<String>,
    onConfirm: (name: String, color: String?, brand: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var brand by remember { mutableStateOf(initial?.brand.orEmpty()) }
    var colorText by remember { mutableStateOf(initial?.color.orEmpty()) }
    val normalizedColor = normalizeHexColor(colorText)
    val colorError = colorText.isNotBlank() && normalizedColor == null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "カードを追加" else "カードを編集") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "アプリ未対応のカードを登録できます。国際ブランドを選ぶと、カード会社を問わないブランド対象キャンペーン(Visa割など)の判定にも使われます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("カード名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text("国際ブランド", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.weight(1f))
                    OptionalBrandDropdown(brand = brand, options = brandOptions, onChange = { brand = it })
                }
                Text(
                    "色(バッジ・地図ピンの識別色)",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                CUSTOM_CARD_PALETTE.chunked(4).forEach { rowColors ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowColors.forEach { hex ->
                            ColorSwatch(
                                hex = hex,
                                selected = normalizedColor == hex,
                                onClick = { colorText = hex },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = colorText,
                    onValueChange = { colorText = it },
                    label = { Text("カラーコード") },
                    placeholder = { Text("#1A73E8") },
                    singleLine = true,
                    isError = colorError,
                    supportingText = {
                        Text(
                            if (colorError) "#RRGGBB 形式で入力してください"
                            else "空欄の場合はグレー(既定色)になります"
                        )
                    },
                    trailingIcon = { CustomCardColorDot(normalizedColor) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && !colorError,
                onClick = { onConfirm(name.trim(), normalizedColor, brand) },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

/** パレットの色1つ分。タッチ領域48dpを確保しつつ見た目のスウォッチは32dpに留める。 */
@Composable
private fun ColorSwatch(hex: String, selected: Boolean, onClick: () -> Unit) {
    val color = parseBrandColor(hex) ?: return
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(color)
                .then(
                    if (selected) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "選択中",
                    tint = onColorFor(color),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** ブランド選択(「なし」も選べる)。選択肢はカタログ(payment_methods.json の card_brands)から出す。 */
@Composable
private fun OptionalBrandDropdown(brand: String, options: List<String>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(brand.ifBlank { "なし" })
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (listOf("") + options).forEach { b ->
                DropdownMenuItem(
                    text = { Text(b.ifBlank { "なし" }) },
                    onClick = {
                        onChange(b)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** カード1枚分の設定行: 所有チェック + (所有時) ブランド選択 / 還元率 / ウエル活。 */
@Composable
private fun CardSettingItem(
    card: MainViewModel.CardSetting,
    onOwnedChange: (Boolean) -> Unit,
    onRateChange: (Double?) -> Unit,
    onBrandChange: (String) -> Unit,
    onClassChange: (String) -> Unit,
) {
    var showRateDialog by remember { mutableStateOf(false) }
    var showBrandRequiredDialog by remember { mutableStateOf(false) }
    // ブランドが判定に効くカード(showBrandPicker)は、未選択のままだと除外側に倒れて
    // 過少表示になり得るため、有効化時にブランド選択を必須にする(選択せず閉じたら有効化しない)
    val requestOwnedChange: (Boolean) -> Unit = { owned ->
        if (owned && card.showBrandPicker && card.brand.isBlank()) {
            showBrandRequiredDialog = true
        } else {
            onOwnedChange(owned)
        }
    }
    // タイトルにブランドは出さない。ブランドが判定に効くカード(showBrandPicker)だけ下のブランド行で表示・変更する
    // (施策がブランド不問のカードに「（Visa）」等を出すと、そのブランド限定と誤読させるため)
    ListItem(
        headlineContent = { NameWithColorDot(card.cardName, card.brandColor) },
        leadingContent = { Checkbox(checked = card.owned, onCheckedChange = requestOwnedChange) },
        colors = transparentListItemColors(),
        modifier = Modifier.clickable { requestOwnedChange(!card.owned) },
    )
    if (card.owned) {
        if (card.showBrandPicker) {
            ListItem(
                headlineContent = { Text("国際ブランド") },
                trailingContent = {
                    BrandDropdown(brand = card.brand, options = card.brands, onChange = onBrandChange)
                },
                colors = transparentListItemColors(),
                modifier = Modifier.padding(start = 24.dp),
            )
            if (card.brand.isBlank()) {
                // 除外され得るブランドはデータ駆動(ineligible_brands の集約)。除外ルールが無く
                // ブランド施策だけで選択 UI が出ているカードには、未一致の説明にとどめる
                val unselectedNote = if (card.ineligibleBrands.isNotEmpty()) {
                    "ブランド未選択のため、${card.ineligibleBrands.joinToString("/")} で優遇対象外になり得るお店は対象外として扱われます。お持ちのブランドを選ぶと正確に判定されます"
                } else {
                    "ブランド未選択のため、ブランド限定のキャンペーンは判定に出ません。お持ちのブランドを選ぶと正確に判定されます"
                }
                CardSettingWarning(unselectedNote)
            }
            if (card.ineligibleBrands.any { it.equals(card.brand, ignoreCase = true) }) {
                CardSettingWarning("${card.brand} は一部のお店が優遇対象外になります")
            }
        }
        // カードクラス(JCB CARD W/S 等)。持っている種類で還元率が変わるカードだけ出す
        if (card.cardClasses.isNotEmpty()) {
            ListItem(
                headlineContent = { Text("カードの種類") },
                trailingContent = {
                    CardClassDropdown(
                        classes = card.cardClasses,
                        selectedId = card.cardClassId,
                        onChange = onClassChange,
                    )
                },
                colors = transparentListItemColors(),
                modifier = Modifier.padding(start = 24.dp),
            )
        }
        // 還元率行は手入力に意味があるカード(単一率プログラム=SMCC/MUFG)だけ出す。
        // クラスを持つカード(JCB)や店舗別レートのカード(dカード)は率が導出値・収録値で
        // 決まり設定の余地が無いため、行自体を出さない(この画面はユーザーが設定するものだけを置く)
        if (card.rateEditable) {
            ListItem(
                headlineContent = { Text("還元率") },
                supportingContent = { Text("公式アプリに表示される還元率を入力") },
                trailingContent = {
                    Text("${trimRate(card.rate)}%", style = MaterialTheme.typography.titleMedium)
                },
                colors = transparentListItemColors(),
                modifier = Modifier.padding(start = 24.dp).clickable { showRateDialog = true },
            )
        }
        // ウエル活等のポイント倍率チェックは #39 でカード行から「ポイント」セクション(通貨単位)へ移設
    }
    if (showRateDialog) {
        RateEditDialog(
            initial = card.rate,
            onDismiss = { showRateDialog = false },
            onConfirm = {
                onRateChange(it)
                showRateDialog = false
            },
        )
    }
    if (showBrandRequiredDialog) {
        BrandRequiredDialog(
            cardName = card.cardName,
            options = card.brands,
            onSelect = { brand ->
                onBrandChange(brand)
                onOwnedChange(true)
                showBrandRequiredDialog = false
            },
            onDismiss = { showBrandRequiredDialog = false },
        )
    }
}

/** カードクラス選択(JCB CARD W/S 等)。選択肢はカタログ(payment_methods.json の card_classes)から出す。 */
@Composable
private fun CardClassDropdown(
    classes: List<CardClass>,
    selectedId: String?,
    onChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = classes.firstOrNull { it.id == selectedId } ?: classes.firstOrNull()
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(selected?.label ?: "選択")
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            classes.forEach { c ->
                DropdownMenuItem(
                    text = { Text(c.label) },
                    onClick = {
                        onChange(c.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** ブランド選択。選択肢はカタログ(payment_methods.json の brands)から出す。 */
@Composable
private fun BrandDropdown(brand: String, options: List<String>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(brand.ifBlank { "選択" })
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { b ->
                DropdownMenuItem(
                    text = { Text(b) },
                    onClick = {
                        onChange(b)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** ブランドが判定に効くカードを有効化するとき、実ブランドの選択を求めるダイアログ。 */
@Composable
private fun BrandRequiredDialog(
    cardName: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("国際ブランドを選択") },
        text = {
            Column {
                Text(
                    "${cardName}は国際ブランドによって判定が変わります。お持ちのカードのブランドを選んでください。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                options.forEach { b ->
                    ListItem(
                        headlineContent = { Text(b) },
                        modifier = Modifier.clickable { onSelect(b) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

/**
 * 1pt 価値のピッカー(#13: 通貨単位・全通貨で設定可能)。プリセット(使わない=0円/等価=1円)+
 * カスタム入力。「既定に戻す」で上書きを解除する(null を返す)。旧カード単位の
 * PointValueEditDialog を通貨向けに一般化したもの(#39 の通貨マスタ移設に追従)。
 */
@Composable
private fun PointValuePickerDialog(
    currency: MainViewModel.PointCurrencySetting,
    onDismiss: () -> Unit,
    onConfirm: (Double?) -> Unit,
) {
    var text by remember { mutableStateOf(trimRate(currency.valueYen)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(currency.pointValueConfig?.label ?: "${currency.name}の1ptの価値") },
        text = {
            Column {
                Text(
                    buildString {
                        append("1ポイントをいくらの価値として計算するか選んでください。判定の実質還元率に反映されます。")
                        currency.pointValueConfig?.note?.takeIf { it.isNotBlank() }?.let { append("\n$it") }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { text = "0" }, label = { Text("使わない(0円)") })
                    AssistChip(onClick = { text = "1" }, label = { Text("等価(1円)") })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    prefix = { Text("1pt=") },
                    suffix = { Text("円") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.toDoubleOrNull()?.takeIf { it >= 0.0 }) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = { onConfirm(null) }) { Text("既定に戻す") } },
    )
}

/**
 * 期間限定ポイントの残高・失効日入力(#13)。通貨ごとに1件(直近失効分)。「削除」で登録を消す。
 * 失効日の選択は CustomCampaignEditor.kt の EditorDatePickerDialog を共用する(横画面 Input モード等の
 * 既存実装を再利用するため internal 化済み)。実物のシグネチャは LocalDate? ベースなので、
 * PointBalance.expiryDate(ISO 文字列)とはここで相互変換する。
 */
@Composable
private fun PointBalanceEditDialog(
    currency: MainViewModel.PointCurrencySetting,
    onDismiss: () -> Unit,
    onConfirm: (PointBalance?) -> Unit,
) {
    var balanceText by remember { mutableStateOf(currency.balance?.balancePt?.toString().orEmpty()) }
    var expiry by remember {
        mutableStateOf(
            currency.balance?.expiryDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        )
    }
    var showDatePicker by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${currency.name}の期間限定ポイント") },
        text = {
            Column {
                Text(
                    "直近で失効する分の残高と失効日を入力してください。失効が近づくと判定画面でお知らせします。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = balanceText,
                    onValueChange = { balanceText = it },
                    label = { Text("残高") },
                    suffix = { Text("pt") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { showDatePicker = true }) {
                    Text(expiry?.toString() ?: "失効日を選ぶ")
                }
            }
        },
        confirmButton = {
            val pt = balanceText.toIntOrNull()
            TextButton(
                onClick = {
                    val expiryValue = expiry
                    if (pt != null && pt > 0 && expiryValue != null) {
                        onConfirm(PointBalance(balancePt = pt, expiryDate = expiryValue.toString()))
                    }
                },
                enabled = pt != null && pt > 0 && expiry != null,
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = { onConfirm(null) }) { Text("削除") } },
    )
    if (showDatePicker) {
        EditorDatePickerDialog(
            initial = expiry,
            onConfirm = {
                expiry = it
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false },
        )
    }
}

/** 還元率の数値入力ダイアログ。空/「既定に戻す」で上書きを解除する(null を返す)。 */
@Composable
private fun RateEditDialog(initial: Double, onDismiss: () -> Unit, onConfirm: (Double?) -> Unit) {
    var text by remember { mutableStateOf(trimRate(initial)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("還元率を入力") },
        text = {
            Column {
                Text(
                    "公式アプリに表示される還元率(%)を入力してください。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    suffix = { Text("%") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.toDoubleOrNull()) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = { onConfirm(null) }) { Text("既定に戻す") } },
    )
}
