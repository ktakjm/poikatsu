package com.ktakjm.poikatsu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ktakjm.poikatsu.data.CustomCampaign
import com.ktakjm.poikatsu.data.CustomPayment
import com.ktakjm.poikatsu.data.MIN_PURCHASE_SCOPE_PERIOD_TOTAL
import com.ktakjm.poikatsu.data.MIN_PURCHASE_SCOPE_TRANSACTION
import com.ktakjm.poikatsu.data.Merchant
import com.ktakjm.poikatsu.domain.trimRate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * カスタムキャンペーンの紐付け先候補1件。カード(所有カタログ+カスタム)・QR 決済・
 * ブランド指定(カード会社不問)を1つのピッカーで選べるよう共通の形に均す。
 * cardId / qrPaymentId / cardBrand はどれか1つだけが入る。
 */
internal data class PaymentOptionUi(
    val cardId: String? = null,
    val qrPaymentId: String? = null,
    val cardBrand: String? = null,
    val label: String,
    val color: String? = null,
) {
    /** 選択状態の同一性キー(CustomPayment と相互変換できる形) */
    val key: String
        get() = when {
            cardId != null -> "card:$cardId"
            qrPaymentId != null -> "qr:$qrPaymentId"
            else -> "brand:$cardBrand"
        }

    fun toPayment() = CustomPayment(cardId = cardId, qrPaymentId = qrPaymentId, cardBrand = cardBrand)
}

private fun CustomPayment.optionKey(): String = when {
    cardId != null -> "card:$cardId"
    qrPaymentId != null -> "qr:$qrPaymentId"
    else -> "brand:$cardBrand"
}

/** 対象日(recurrence)の入力モード */
private enum class RecurrenceMode { EVERY_DAY, WEEKLY, MONTHLY }

private val WEEK_DAYS = listOf(
    "MON" to "月", "TUE" to "火", "WED" to "水", "THU" to "木",
    "FRI" to "金", "SAT" to "土", "SUN" to "日",
)

/**
 * カスタムキャンペーンの追加(initial=null)/編集の全画面エディタ。TopAppBar(タイトル・閉じる)は
 * PoikatsuApp 側が出し、この Composable は本文(フォーム+保存ボタン)を描く。
 * onSave には登録内容を渡す(新規は id 空のまま。採番は ViewModel が行う)。
 *
 * 2段階の入力構成: 基本(名前・決済手段・店舗・特典・期間)+折りたたみ「詳細条件」
 * (対象外・商品限定・最低購入額・回数/上限・URL)。詳細は登録すべきデータのガイドを兼ねる。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun CustomCampaignEditorScreen(
    initial: CustomCampaign?,
    paymentOptions: List<PaymentOptionUi>,
    chains: List<Merchant>,
    onSave: (CustomCampaign) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)

    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    // 紐付け先が消えている(カスタムカード削除等)ものは候補に無いので選択から落ちる
    var selectedPaymentKeys by remember {
        val optionKeys = paymentOptions.map { it.key }.toSet()
        mutableStateOf(initial?.payments.orEmpty().map { it.optionKey() }.filter { it in optionKeys }.toSet())
    }
    var merchantIds by remember { mutableStateOf(initial?.merchantIds.orEmpty().toSet()) }
    var storeNames by remember { mutableStateOf(initial?.storeNames.orEmpty()) }
    var storeNameInput by remember { mutableStateOf("") }
    var benefitType by remember { mutableStateOf(initial?.benefitType ?: "rebate") }
    var rateText by remember { mutableStateOf(initial?.rate?.let { trimRate(it) }.orEmpty()) }
    var discountText by remember { mutableStateOf(initial?.discountAmount?.toString().orEmpty()) }
    var note by remember { mutableStateOf(initial?.note.orEmpty()) }
    var startDate by remember { mutableStateOf(initial?.startDate?.let { LocalDate.parse(it) }) }
    var endDate by remember { mutableStateOf(initial?.endDate?.let { LocalDate.parse(it) }) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    var recurrenceMode by remember {
        mutableStateOf(
            when {
                !initial?.daysOfWeek.isNullOrEmpty() -> RecurrenceMode.WEEKLY
                !initial?.daysOfMonth.isNullOrEmpty() -> RecurrenceMode.MONTHLY
                else -> RecurrenceMode.EVERY_DAY
            }
        )
    }
    var daysOfWeek by remember { mutableStateOf(initial?.daysOfWeek.orEmpty().toSet()) }
    var daysOfMonthText by remember {
        mutableStateOf(initial?.daysOfMonth.orEmpty().joinToString(","))
    }
    // --- 詳細条件(2段階目)。初期値が1つでも入っていれば開いた状態で始める ---
    var ineligibleNote by remember { mutableStateOf(initial?.ineligibleNote.orEmpty()) }
    var productScope by remember { mutableStateOf(initial?.productScope.orEmpty()) }
    var minPurchaseText by remember { mutableStateOf(initial?.minPurchase?.toString().orEmpty()) }
    var minPurchasePeriodTotal by remember {
        mutableStateOf(initial?.minPurchaseScope == MIN_PURCHASE_SCOPE_PERIOD_TOTAL)
    }
    var usageLimitText by remember { mutableStateOf(initial?.usageLimit?.toString().orEmpty()) }
    var perCapText by remember { mutableStateOf(initial?.perTransactionCap?.toString().orEmpty()) }
    var totalCapText by remember { mutableStateOf(initial?.periodTotalCap?.toString().orEmpty()) }
    var capNote by remember { mutableStateOf(initial?.capNote.orEmpty()) }
    var detailUrl by remember { mutableStateOf(initial?.detailUrl.orEmpty()) }
    var detailsExpanded by remember {
        mutableStateOf(
            initial != null && (
                initial.ineligibleNote.isNotBlank() || initial.productScope != null ||
                    initial.minPurchase != null || initial.usageLimit != null ||
                    initial.perTransactionCap != null || initial.periodTotalCap != null ||
                    initial.capNote != null || initial.detailUrl != null
                )
        )
    }

    // --- バリデーション ---
    val rate = rateText.trim().toDoubleOrNull()
    val rateError = rateText.isNotBlank() && (rate == null || rate < 0)
    val discount = discountText.trim().toIntOrNull()
    val discountError = discountText.isNotBlank() && (discount == null || discount <= 0)
    // 率と定額は排他(campaigns.json と同じ前提)。両方入れると定額だけが表示され率が黙って
    // 無視されるため、保存前に弾く。「10%OFF・最大500円引き」型は率+還元上限で表す
    val benefitConflict = rate != null && discount != null
    val isLottery = benefitType == "lottery"
    val daysOfMonth = parseDaysOfMonth(daysOfMonthText)
    val daysOfMonthError = recurrenceMode == RecurrenceMode.MONTHLY &&
        daysOfMonthText.isNotBlank() && daysOfMonth == null
    val recurrenceOk = when (recurrenceMode) {
        RecurrenceMode.EVERY_DAY -> true
        RecurrenceMode.WEEKLY -> daysOfWeek.isNotEmpty()
        RecurrenceMode.MONTHLY -> !daysOfMonth.isNullOrEmpty()
    }
    val periodError = startDate != null && endDate != null && startDate!! > endDate!!
    // 自由入力欄に書きかけ(未チップ化)の店名も保存時に取り込むため、店舗ありとみなす
    val hasStore = merchantIds.isNotEmpty() || storeNames.isNotEmpty() || storeNameInput.isNotBlank()
    // 抽選は率・額を持たない(「抽選」表示)。それ以外は率・定額・メモのどれかが必要
    val hasBenefit = isLottery || rate != null || discount != null || note.isNotBlank()
    val minPurchase = minPurchaseText.trim().toIntOrNull()
    val usageLimit = usageLimitText.trim().toIntOrNull()
    val perCap = perCapText.trim().toIntOrNull()
    val totalCap = totalCapText.trim().toIntOrNull()
    fun numberError(text: String, value: Int?) = text.isNotBlank() && (value == null || value <= 0)
    val detailNumbersOk = !numberError(minPurchaseText, minPurchase) &&
        !numberError(usageLimitText, usageLimit) &&
        !numberError(perCapText, perCap) && !numberError(totalCapText, totalCap)
    val canSave = name.isNotBlank() && selectedPaymentKeys.isNotEmpty() && hasStore &&
        !rateError && !discountError && !benefitConflict && hasBenefit &&
        recurrenceOk && !daysOfMonthError && !periodError && detailNumbersOk

    // 未確定の自由入力店名をチップ化して取り込む(「追加」押し忘れの入力を捨てない)
    fun commitStoreNameInput() {
        val trimmed = storeNameInput.trim()
        if (trimmed.isNotEmpty() && storeNames.none { it == trimmed }) {
            storeNames = storeNames + trimmed
        }
        storeNameInput = ""
    }

    fun buildCampaign(): CustomCampaign {
        val optionsByKey = paymentOptions.associateBy { it.key }
        return CustomCampaign(
            id = initial?.id.orEmpty(),
            name = name.trim(),
            payments = selectedPaymentKeys.mapNotNull { optionsByKey[it]?.toPayment() },
            merchantIds = merchantIds.toList(),
            storeNames = storeNames,
            benefitType = benefitType,
            // 抽選は率・額を持たせない(型を切り替える前の入力値を残さない)
            rate = rate.takeUnless { isLottery },
            discountAmount = discount.takeUnless { isLottery },
            productScope = productScope.trim().takeIf { it.isNotEmpty() },
            note = note.trim(),
            ineligibleNote = ineligibleNote.trim(),
            startDate = startDate?.toString(),
            endDate = endDate?.toString(),
            daysOfWeek = if (recurrenceMode == RecurrenceMode.WEEKLY) {
                WEEK_DAYS.map { it.first }.filter { it in daysOfWeek }
            } else {
                emptyList()
            },
            daysOfMonth = if (recurrenceMode == RecurrenceMode.MONTHLY) daysOfMonth.orEmpty() else emptyList(),
            minPurchase = minPurchase,
            minPurchaseScope = if (minPurchasePeriodTotal) {
                MIN_PURCHASE_SCOPE_PERIOD_TOTAL
            } else {
                MIN_PURCHASE_SCOPE_TRANSACTION
            },
            usageLimit = usageLimit,
            perTransactionCap = perCap,
            periodTotalCap = totalCap,
            capNote = capNote.trim().takeIf { it.isNotEmpty() },
            detailUrl = detailUrl.trim().takeIf { it.isNotEmpty() },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        Text(
            "会員ポータル限定クーポンなど、アプリに未登録のキャンペーンを自分で登録できます。登録するとお店・地図・期間限定タブに表示されます。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("キャンペーン名") },
            placeholder = { Text("例: 誕生月クーポン10%") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // --- 紐付け先の決済手段(複数可) ---
        EditorSectionHeader("決済手段")
        Text(
            "複数選ぶと、同じ内容の施策が決済手段ごとに登録されます(率・条件は共通。決済ごとに率が異なる場合は別々に登録してください)。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(4.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PaymentPickerDropdown(
                options = paymentOptions,
                selectedKeys = selectedPaymentKeys,
                onToggle = { key ->
                    selectedPaymentKeys =
                        if (key in selectedPaymentKeys) selectedPaymentKeys - key else selectedPaymentKeys + key
                },
            )
            paymentOptions.filter { it.key in selectedPaymentKeys }.forEach { option ->
                InputChip(
                    selected = true,
                    onClick = { selectedPaymentKeys = selectedPaymentKeys - option.key },
                    label = { Text(option.label) },
                    trailingIcon = {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "${option.label}を外す",
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }
        }

        // --- 対象店舗 ---
        EditorSectionHeader("対象店舗")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ChainPickerDropdown(
                chains = chains,
                selectedIds = merchantIds,
                onToggle = { id ->
                    merchantIds = if (id in merchantIds) merchantIds - id else merchantIds + id
                },
            )
            chains.filter { it.id in merchantIds }.forEach { m ->
                InputChip(
                    selected = true,
                    onClick = { merchantIds = merchantIds - m.id },
                    label = { Text(m.name) },
                    trailingIcon = {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "${m.name}を外す",
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }
            storeNames.forEach { storeName ->
                InputChip(
                    selected = true,
                    onClick = { storeNames = storeNames - storeName },
                    label = { Text(storeName) },
                    trailingIcon = {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "${storeName}を外す",
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }
        }
        OutlinedTextField(
            value = storeNameInput,
            onValueChange = { storeNameInput = it },
            label = { Text("一覧に無い店名を入力") },
            placeholder = { Text("例: ○○ベーカリー") },
            singleLine = true,
            supportingText = { Text("店名の部分一致でお店・地図タブにマッチします") },
            trailingIcon = {
                IconButton(
                    onClick = { commitStoreNameInput() },
                    enabled = storeNameInput.isNotBlank(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = "店名を追加")
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )

        // --- 還元内容 ---
        EditorSectionHeader("還元内容")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val types = listOf("rebate" to "還元", "discount" to "割引", "lottery" to "抽選")
            types.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = benefitType == value,
                    onClick = { benefitType = value },
                    shape = SegmentedButtonDefaults.itemShape(index, types.size),
                ) { Text(label) }
            }
        }
        if (isLottery) {
            Text(
                "抽選は率・額の代わりに「抽選」と表示され、最大おトク率の比較対象外になります。当選内容はメモに書けます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            OutlinedTextField(
                value = rateText,
                onValueChange = { rateText = it },
                label = { Text(if (benefitType == "discount") "割引率" else "還元率") },
                suffix = { Text("%") },
                singleLine = true,
                isError = rateError || benefitConflict,
                supportingText = {
                    Text(
                        when {
                            rateError -> "0以上の数値を入力してください"
                            benefitConflict -> "率と定額はどちらか一方だけ入力してください"
                            else -> "率で表せない特典は空欄にして定額かメモへ"
                        }
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = discountText,
                onValueChange = { discountText = it },
                label = { Text(if (benefitType == "discount") "定額割引" else "定額還元") },
                suffix = { Text("円") },
                singleLine = true,
                isError = discountError || benefitConflict,
                supportingText = {
                    Text(
                        when {
                            discountError -> "1以上の整数を入力してください"
                            benefitConflict -> "率と定額はどちらか一方だけ入力してください。「10%OFF・最大500円引き」型は率+詳細条件の還元上限で表せます"
                            else -> "「500円引き」のような定額特典(率とどちらか一方)"
                        }
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("対象・特典メモ") },
            placeholder = { Text("例: クーポン提示で適用") },
            supportingText = { Text("1行につき1項目。判定カードの「対象」に表示されます") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        // --- 期間・対象日 ---
        EditorSectionHeader("期間")
        DateFieldRow(
            label = "開始日",
            date = startDate,
            emptyLabel = "なし(開始済み)",
            onPick = { showStartPicker = true },
            onClear = { startDate = null },
        )
        DateFieldRow(
            label = "終了日",
            date = endDate,
            emptyLabel = "なし(期限なし)",
            onPick = { showEndPicker = true },
            onClear = { endDate = null },
        )
        if (periodError) {
            Text(
                "開始日が終了日より後になっています",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text("対象日", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val modes = listOf(
                RecurrenceMode.EVERY_DAY to "毎日",
                RecurrenceMode.WEEKLY to "曜日指定",
                RecurrenceMode.MONTHLY to "日付指定",
            )
            modes.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = recurrenceMode == mode,
                    onClick = { recurrenceMode = mode },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                ) { Text(label) }
            }
        }
        when (recurrenceMode) {
            RecurrenceMode.EVERY_DAY -> Unit
            RecurrenceMode.WEEKLY -> FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                WEEK_DAYS.forEach { (value, label) ->
                    FilterChip(
                        selected = value in daysOfWeek,
                        onClick = {
                            daysOfWeek = if (value in daysOfWeek) daysOfWeek - value else daysOfWeek + value
                        },
                        label = { Text(label) },
                    )
                }
            }
            RecurrenceMode.MONTHLY -> OutlinedTextField(
                value = daysOfMonthText,
                onValueChange = { daysOfMonthText = it },
                label = { Text("対象日(1〜31)") },
                placeholder = { Text("例: 20,30") },
                singleLine = true,
                isError = daysOfMonthError,
                supportingText = {
                    Text(
                        if (daysOfMonthError) "1〜31 の数字をカンマ区切りで入力してください"
                        else "カンマ区切りで複数指定できます"
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }

        // --- 詳細条件(折りたたみ。登録すべきデータのガイドを兼ねる) ---
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .heightIn(min = 48.dp)
                .clickable { detailsExpanded = !detailsExpanded },
        ) {
            Text(
                "詳細条件(任意)",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (detailsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (detailsExpanded) "詳細条件を閉じる" else "詳細条件を開く",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        if (detailsExpanded) {
            OutlinedTextField(
                value = ineligibleNote,
                onValueChange = { ineligibleNote = it },
                label = { Text("対象外・注意") },
                placeholder = { Text("例: セール品は対象外") },
                supportingText = { Text("1行につき1項目。判定カードに警告色で表示されます") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = productScope,
                onValueChange = { productScope = it },
                label = { Text("対象商品限定") },
                placeholder = { Text("例: 対象の化粧品のみ") },
                singleLine = true,
                supportingText = { Text("店の全商品に効かない特典。「商品限定」バッジが付き、最大おトク率の比較から外れます") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = minPurchaseText,
                onValueChange = { minPurchaseText = it },
                label = { Text("最低購入額") },
                suffix = { Text("円") },
                singleLine = true,
                isError = numberError(minPurchaseText, minPurchase),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            if (minPurchase != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { minPurchasePeriodTotal = !minPurchasePeriodTotal },
                ) {
                    Checkbox(checked = minPurchasePeriodTotal, onCheckedChange = null)
                    Text("期間中の購入合計に対する条件(複数回の買い物の合算可)", style = MaterialTheme.typography.bodyMedium)
                }
            }
            OutlinedTextField(
                value = usageLimitText,
                onValueChange = { usageLimitText = it },
                label = { Text("利用回数の上限") },
                suffix = { Text("回") },
                singleLine = true,
                isError = numberError(usageLimitText, usageLimit),
                supportingText = { Text("「お一人様○回まで」型の制限") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = perCapText,
                onValueChange = { perCapText = it },
                label = { Text("還元上限(1回あたり)") },
                suffix = { Text("円") },
                singleLine = true,
                isError = numberError(perCapText, perCap),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = totalCapText,
                onValueChange = { totalCapText = it },
                label = { Text("還元上限(期間合計)") },
                suffix = { Text("円") },
                singleLine = true,
                isError = numberError(totalCapText, totalCap),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = capNote,
                onValueChange = { capNote = it },
                label = { Text("上限の補足") },
                placeholder = { Text("例: ポイントは翌月付与") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = detailUrl,
                onValueChange = { detailUrl = it },
                label = { Text("詳細ページURL") },
                placeholder = { Text("https://…") },
                singleLine = true,
                supportingText = { Text("会員ポータル等。判定カードの「詳細を見る」から開けます") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(24.dp))
        Button(
            enabled = canSave,
            onClick = {
                commitStoreNameInput()
                onSave(buildCampaign())
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("保存") }
    }

    if (showStartPicker) {
        EditorDatePickerDialog(
            initial = startDate,
            onConfirm = {
                startDate = it
                showStartPicker = false
            },
            onDismiss = { showStartPicker = false },
        )
    }
    if (showEndPicker) {
        EditorDatePickerDialog(
            initial = endDate,
            onConfirm = {
                endDate = it
                showEndPicker = false
            },
            onDismiss = { showEndPicker = false },
        )
    }
}

/** 「20,30」形式を日付リストへ。空は null(未入力)、形式外・範囲外(1〜31)も null(エラー) */
private fun parseDaysOfMonth(text: String): List<Int>? {
    if (text.isBlank()) return null
    val days = text.split(',', '、', ' ').filter { it.isNotBlank() }.map { it.trim().toIntOrNull() }
    if (days.any { it == null || it < 1 || it > 31 }) return null
    return days.filterNotNull().distinct().sorted()
}

@Composable
private fun EditorSectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

/** 日付欄1行(ラベル+選択ボタン+クリア)。開始日・終了日で共用 */
@Composable
private fun DateFieldRow(
    label: String,
    date: LocalDate?,
    emptyLabel: String,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onPick) {
            Text(date?.let { formatPeriodDate(it) } ?: emptyLabel)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        if (date != null) {
            IconButton(onClick = onClear) {
                Icon(Icons.Default.Close, contentDescription = "${label}を消す")
            }
        }
    }
}

/**
 * 紐付け先の決済手段ピッカー(複数選択)。カード(カタログ所有分+カスタム)・QR 決済・
 * ブランド指定を1本の候補で出し、トグルしてもメニューは閉じず続けて選べる。
 */
@Composable
private fun PaymentPickerDropdown(
    options: List<PaymentOptionUi>,
    selectedKeys: Set<String>,
    onToggle: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text("決済手段を選択") },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 400.dp),
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { NameWithColorDotItem(option.label, option.color) },
                    leadingIcon = {
                        Checkbox(
                            checked = option.key in selectedKeys,
                            onCheckedChange = null, // 行タップに委ねる(タッチ領域を行全体にする)
                        )
                    },
                    onClick = { onToggle(option.key) },
                )
            }
        }
    }
}

/** 名前+識別色ドット(ピッカーの行用)。色未定義はドットなし。 */
@Composable
private fun NameWithColorDotItem(name: String, color: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        parseBrandColor(color)?.let { parsed ->
            Box(Modifier.size(16.dp).clip(CircleShape).background(parsed))
            Spacer(Modifier.width(8.dp))
        }
        Text(name)
    }
}

/**
 * 対象チェーンのピッカー(複数選択)。カタログの全チェーン(65 前後)をフラットに出すと
 * 目当てのチェーンを探せないため、**カテゴリ見出し(データ定義順=お店タブのチップと同順)で
 * 区切り、各カテゴリ内は読み仮名順**で並べる。トグルしてもメニューは閉じず続けて選べる
 * (地図タブの ChainFilterDropdown と同じ操作感)。
 */
@Composable
private fun ChainPickerDropdown(
    chains: List<Merchant>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // groupBy はカテゴリの出現順(データ定義順)を保つ
    val grouped = remember(chains) {
        chains.groupBy { it.category }.mapValues { (_, members) -> members.sortedBy { it.reading } }
    }
    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text("チェーンを選択") },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 400.dp),
        ) {
            grouped.forEach { (category, members) ->
                Text(
                    category,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                members.forEach { merchant ->
                    DropdownMenuItem(
                        text = { Text(merchant.name) },
                        leadingIcon = {
                            Checkbox(
                                checked = merchant.id in selectedIds,
                                onCheckedChange = null, // 行タップに委ねる(タッチ領域を行全体にする)
                            )
                        },
                        onClick = { onToggle(merchant.id) },
                    )
                }
            }
        }
    }
}

/** 開始日・終了日の DatePicker。M3 の DatePickerState は UTC ミリ秒なので LocalDate と相互変換する。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorDatePickerDialog(
    initial: LocalDate?,
    onConfirm: (LocalDate?) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    state.selectedDateMillis?.let {
                        Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                )
            }) { Text("決定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    ) {
        DatePicker(state = state)
    }
}
