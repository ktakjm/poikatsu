package com.ktakjm.poikatsu.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ktakjm.poikatsu.data.CustomPayment
import com.ktakjm.poikatsu.data.MunicipalityMaster
import com.ktakjm.poikatsu.data.Region

// カスタムキャンペーン編集画面(CustomCampaignEditor.kt)の追加セクション(#91)。
// 本体が 900 行超のため、自治体キャンペーン向けの「地域」と「お支払い方法ごとの設定」をここに分ける。

/** 「還元されるポイント」の選択肢(payment_methods.json の point_currencies 由来) */
internal data class PointCurrencyOptionUi(
    val id: String,
    val name: String,
    val color: String?,
)

/**
 * 決済手段ごとの差分入力([CustomPayment] の帰属以外の部分)。エディタ内では決済手段の
 * 選択キー([PaymentOptionUi.key])で持ち、保存時に選択中の決済手段へ [applyTo] で写す。
 */
internal data class PaymentOverrideUi(
    val detailUrl: String = "",
    val note: String = "",
    val ineligibleNote: String = "",
    val pointCurrencyId: String? = null,
) {
    fun applyTo(payment: CustomPayment): CustomPayment = payment.copy(
        detailUrl = detailUrl.trim().takeIf { it.isNotEmpty() },
        note = note.trim(),
        ineligibleNote = ineligibleNote.trim(),
        pointCurrencyId = pointCurrencyId,
    )

    companion object {
        fun from(payment: CustomPayment) = PaymentOverrideUi(
            detailUrl = payment.detailUrl.orEmpty(),
            note = payment.note,
            ineligibleNote = payment.ineligibleNote,
            pointCurrencyId = payment.pointCurrencyId,
        )
    }
}

/**
 * 自治体キャンペーンの「地域」セクション。お店のキャンペーンの「対象のお店」の代わりに出す。
 * 選択は [RegionPickerDialog](単一選択)で、自由入力は持たない(名称不一致で地域フィルタ・
 * お知らせピル・通知が効かなくなるため。[CustomCampaign.region] 参照)。
 */
@Composable
internal fun RegionSection(
    region: Region?,
    master: MunicipalityMaster,
    onChange: (Region?) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    EditorSectionHeader("地域")
    Text(
        "自治体のキャンペーンとして、おトクタブの自治体一覧・地図のお知らせ・通知に表示されます(お店・地図タブの判定には出ません)。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            region?.let { regionDisplayName(it) } ?: "地域が選ばれていません",
            style = MaterialTheme.typography.bodyLarge,
            color = if (region == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { showPicker = true }) {
            Text(if (region == null) "地域を選択" else "変更")
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        if (region != null) {
            IconButton(onClick = { onChange(null) }) {
                Icon(Icons.Default.Close, contentDescription = "地域を消す")
            }
        }
    }
    if (showPicker) {
        RegionPickerDialog(
            master = master,
            selected = region,
            onSelect = {
                onChange(it)
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

/**
 * 「お支払い方法ごとの設定」(詳細条件の中)。選択中の決済手段ごとに、詳細ページ URL・
 * 対象/対象外の注記・還元されるポイントを個別に持てる(同梱 municipal の payment_variants 相当。
 * 自治体キャンペーンの「PayPay だけ告知ページが違う」「au PAY は残高還元」のような差分用だが、
 * お店のキャンペーンでも同じ規則で効く)。率・期間・上限は共通(異なる場合は別登録)。
 */
@Composable
internal fun PaymentOverridesSection(
    selectedOptions: List<PaymentOptionUi>,
    overrides: Map<String, PaymentOverrideUi>,
    pointCurrencies: List<PointCurrencyOptionUi>,
    onChange: (key: String, PaymentOverrideUi) -> Unit,
) {
    if (selectedOptions.isEmpty()) return
    Text(
        "お支払い方法ごとの設定",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
    Text(
        "支払い方法によって告知ページや対象外の条件、還元されるポイントが違うときだけ入力します。上の共通の内容に追加されます。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
    Spacer(Modifier.height(8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        selectedOptions.forEach { option ->
            val current = overrides[option.key] ?: PaymentOverrideUi()
            // 面の入れ子: フォーム地(surface)の上に 1 段高い面で決済手段ごとのグループ境界を見せる
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    NameWithColorDot(option.label, option.color, dotSize = 16.dp)
                    OutlinedTextField(
                        value = current.detailUrl,
                        onValueChange = { onChange(option.key, current.copy(detailUrl = it)) },
                        label = { Text("詳細ページURL(この支払い方法)") },
                        placeholder = { Text("https://…") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    OutlinedTextField(
                        value = current.note,
                        onValueChange = { onChange(option.key, current.copy(note = it)) },
                        label = { Text("対象・特典メモ(この支払い方法)") },
                        supportingText = { Text("1行につき1項目") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = current.ineligibleNote,
                        onValueChange = { onChange(option.key, current.copy(ineligibleNote = it)) },
                        label = { Text("対象外・注意(この支払い方法)") },
                        placeholder = { Text("例: 商品券での支払いは対象外") },
                        supportingText = { Text("1行につき1項目") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (pointCurrencies.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("還元されるポイント", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            val selected = pointCurrencies.firstOrNull { it.id == current.pointCurrencyId }
                            // 先頭の null は「既定」(支払い方法の通貨を継承。au PAY→Ponta 等)
                            SimpleDropdown(
                                buttonLabel = selected?.name ?: "既定",
                                options = listOf<PointCurrencyOptionUi?>(null) + pointCurrencies,
                                itemLabel = { it?.name ?: "既定(支払い方法のポイント)" },
                                onSelect = { onChange(option.key, current.copy(pointCurrencyId = it?.id)) },
                            )
                        }
                    }
                }
            }
        }
    }
}
