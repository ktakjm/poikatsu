package com.ktakjm.poikatsu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 開発者向け設定画面。設定タブの「開発者向け設定」(開発者モード ON 中のみ表示)から開く
 * オーバーレイ。開発者向け設定 = 開発者モード OFF で一括リセットされる項目はすべてここに置く。
 * 一般設定(テーマ・カード等)はリセット対象外なので設定画面本体に置く。
 */
@Composable
internal fun DeveloperSettingsScreen(
    dataCommitRef: String,
    /** 表示中データのフル commit SHA。同梱データ・SHA 解決失敗・旧キャッシュでは null */
    dataCommitSha: String?,
    useTestData: Boolean,
    useBundledData: Boolean,
    onBack: () -> Unit,
    onDataCommitRefChange: (String) -> Unit,
    onUseTestDataChange: (Boolean) -> Unit,
    onUseBundledDataChange: (Boolean) -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            "開発・検証用の設定です。開発者モードをオフにするとすべて既定値に戻ります。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        ListItem(
            headlineContent = { Text("テストデータを使う") },
            supportingContent = { Text("data-test/ のショーケースデータに切り替えます") },
            trailingContent = { Switch(checked = useTestData, onCheckedChange = onUseTestDataChange) },
        )
        ListItem(
            headlineContent = { Text("同梱データを使う") },
            supportingContent = { Text("APK 同梱の JSON を直接表示します(リモート取得を停止)。push せずにデータ変更を実機検証する用") },
            trailingContent = { Switch(checked = useBundledData, onCheckedChange = onUseBundledDataChange) },
        )
        // 同梱モード中はリモートを見ないため commit 指定は無意味 → グレーアウト
        CommitRefRow(value = dataCommitRef, onChange = onDataCommitRefChange, enabled = !useBundledData)
        // 実際に表示しているデータの commit(取得時に ref から解決した SHA)。取得先 ref が main の
        // ままでも「どの commit の内容か」を突き合わせられるようにする。同梱モード中はリモート
        // データではないため意味を持たない → グレーアウト
        ListItem(
            headlineContent = { Text("使用中データの commit") },
            supportingContent = if (useBundledData) {
                { Text("同梱データ使用中") }
            } else {
                null
            },
            trailingContent = {
                Text(
                    when {
                        useBundledData -> "—"
                        dataCommitSha != null -> dataCommitSha.take(7)
                        else -> "不明"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            colors = if (useBundledData) {
                ListItemDefaults.colors(
                    headlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    supportingColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    trailingIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )
            } else {
                ListItemDefaults.colors()
            },
        )
    }
}

@Composable
private fun CommitRefRow(value: String, onChange: (String) -> Unit, enabled: Boolean = true) {
    var text by remember(value) { mutableStateOf(value) }
    ListItem(
        headlineContent = { Text("データ取得先 commit") },
        supportingContent = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(40) },
                placeholder = { Text("空欄 = main") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        },
        trailingContent = {
            TextButton(
                onClick = { onChange(text) },
                enabled = enabled && text.trim() != value,
            ) { Text("適用") }
        },
        colors = if (enabled) {
            ListItemDefaults.colors()
        } else {
            ListItemDefaults.colors(
                headlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
        },
    )
}
