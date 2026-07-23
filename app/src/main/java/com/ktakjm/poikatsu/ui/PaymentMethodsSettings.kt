package com.ktakjm.poikatsu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.ktakjm.poikatsu.data.CustomCard
import com.ktakjm.poikatsu.domain.trimRate
import com.ktakjm.poikatsu.ui.theme.warningColor

/**
 * 支払い方法サブページ(#47)。マイカード / カードブランド / コード決済の 3 セクションを統合する
 * (いずれも「何を持っているか」の登録で意味的に同族)。値は DataStore 由来(MainViewModel 経由)で、
 * 変更は即 ViewModel の setter へ流す。
 */
@Composable
internal fun PaymentMethodsSettingsPage(
    cards: List<MainViewModel.CardSetting>,
    customCards: List<CustomCard>,
    brands: List<MainViewModel.BrandSetting>,
    qrPayments: List<MainViewModel.QrPaymentSetting>,
    onBack: () -> Unit,
    onCardOwnedChange: (String, Boolean) -> Unit,
    onCardRateChange: (String, Double?) -> Unit,
    onCardBrandChange: (String, String) -> Unit,
    onCardWelcatsuChange: (String, Boolean) -> Unit,
    onAddCustomCard: (name: String, color: String?, brand: String) -> Unit,
    onUpdateCustomCard: (CustomCard) -> Unit,
    onRemoveCustomCard: (String) -> Unit,
    onBrandOwnedChange: (String, Boolean) -> Unit,
    onQrEnabledChange: (String, Boolean) -> Unit,
) {
    BackHandler(onBack = onBack)

    // カスタムカードの追加/編集ダイアログ。NEW_CUSTOM_CARD(id 空のセンチネル)なら新規、null なら非表示
    var editingCustomCard by remember { mutableStateOf<CustomCard?>(null) }
    var deletingCustomCard by remember { mutableStateOf<CustomCard?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // --- マイカード ---
        SettingsSectionHeader("マイカード")
        cards.forEach { card ->
            CardSettingItem(
                card = card,
                onOwnedChange = { onCardOwnedChange(card.cardId, it) },
                onRateChange = { onCardRateChange(card.cardId, it) },
                onBrandChange = { onCardBrandChange(card.cardId, it) },
                onWelcatsuChange = { onCardWelcatsuChange(card.cardId, it) },
            )
        }
        // カタログ外のカスタムカード。識別はロゴでなく色(方針どおり)なので、色スウォッチを先頭に出す
        customCards.forEach { card ->
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
                modifier = Modifier.clickable { editingCustomCard = card },
            )
        }
        ListItem(
            headlineContent = { Text("カードを追加") },
            supportingContent = { Text("アプリ未対応のカードを登録できます") },
            leadingContent = {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            modifier = Modifier.clickable { editingCustomCard = NEW_CUSTOM_CARD },
        )

        // --- カードブランド(イシュアー不問のブランド施策向け。事前登録できるよう常時出す) ---
        if (brands.isNotEmpty()) {
            SettingsSectionHeader("カードブランド")
            Text(
                "上のカード以外で持っているブランドにチェックを入れてください。カード会社を問わないブランド対象キャンペーン(Visa割など)が始まると、開始と同時に判定へ表示されます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
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
                "利用中のコード決済(PayPay・楽天ペイなど)にチェックを入れると、キャンペーン情報が判定に表示されます。",
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
                )
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
}

/** カスタムカード追加ダイアログを新規モードで開くためのセンチネル(id 空)。 */
private val NEW_CUSTOM_CARD = CustomCard(id = "", name = "")

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
                    "アプリ未対応のカードを登録できます。ブランドを選ぶと、カード会社を問わないブランド対象キャンペーン(Visa割など)の判定にも使われます。",
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
                    Text("ブランド", style = MaterialTheme.typography.bodyLarge)
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
    onWelcatsuChange: (Boolean) -> Unit,
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
        modifier = Modifier.clickable { requestOwnedChange(!card.owned) },
    )
    if (card.owned) {
        if (card.showBrandPicker) {
            ListItem(
                headlineContent = { Text("ブランド") },
                trailingContent = {
                    BrandDropdown(brand = card.brand, options = card.brands, onChange = onBrandChange)
                },
                modifier = Modifier.padding(start = 24.dp),
            )
            if (card.brand.isBlank()) {
                // 除外され得るブランドはデータ駆動(ineligible_brands の集約)。除外ルールが無く
                // ブランド施策だけで選択 UI が出ているカードには、未一致の説明にとどめる
                val unselectedNote = if (card.ineligibleBrands.isNotEmpty()) {
                    "ブランド未選択のため、${card.ineligibleBrands.joinToString("/")} で優遇対象外になり得る店舗は対象外として扱われます。お持ちのブランドを選ぶと正確に判定されます"
                } else {
                    "ブランド未選択のため、ブランド限定の施策は判定に出ません。お持ちのブランドを選ぶと正確に判定されます"
                }
                Text(
                    unselectedNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = warningColor(),
                    modifier = Modifier.padding(start = 24.dp, end = 16.dp, bottom = 8.dp),
                )
            }
            if (card.ineligibleBrands.any { it.equals(card.brand, ignoreCase = true) }) {
                Text(
                    "${card.brand} は一部店舗が優遇対象外になります",
                    style = MaterialTheme.typography.bodySmall,
                    color = warningColor(),
                    modifier = Modifier.padding(start = 24.dp, end = 16.dp, bottom = 8.dp),
                )
            }
        }
        ListItem(
            headlineContent = { Text("還元率（公式アプリの表示値）") },
            trailingContent = {
                Text("${trimRate(card.rate)}%", style = MaterialTheme.typography.titleMedium)
            },
            modifier = Modifier.padding(start = 24.dp).clickable { showRateDialog = true },
        )
        card.pointMultiplier?.let { pm ->
            val welcatsuNote: (@Composable () -> Unit)? = if (card.welcatsu) {
                ({ Text("${trimRate(card.rate * pm.factor)}% で表示中") })
            } else {
                null
            }
            ListItem(
                headlineContent = { Text(pm.label) },
                leadingContent = { Checkbox(checked = card.welcatsu, onCheckedChange = onWelcatsuChange) },
                supportingContent = welcatsuNote,
                modifier = Modifier.padding(start = 24.dp).clickable { onWelcatsuChange(!card.welcatsu) },
            )
        }
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
        title = { Text("ブランドを選択") },
        text = {
            Column {
                Text(
                    "${cardName}はブランドによって判定が変わります。お持ちのカードのブランドを選んでください。",
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
                    "公式アプリに表示される実効還元率(%)を入力してください。",
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
