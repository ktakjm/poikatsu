package com.ktakjm.poikatsu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

/** 遅延テスト通知の待ち時間(秒)。押してから画面を消すのに要る程度の短さ */
private const val TEST_NOTIFICATION_DELAY_SECONDS = 10L

/**
 * 開発者向けサブページ(#47 で設定トップの階層化に合わせてサブページ化)。「開発者モード」トグルと、
 * ON 中のみ現れる開発者向け設定項目を 1 ページにまとめる(トグルだけトップに残すと二重ネストに
 * なるため統合)。開発者向け設定 = 開発者モード OFF で一括リセットされる項目はすべてここに置く。
 * 一般設定(テーマ・カード等)はリセット対象外なので他のサブページに置く。
 */
@Composable
internal fun DeveloperSettingsPage(
    developerMode: Boolean,
    dataCommitRef: String,
    /** 表示中データのフル commit SHA。同梱データ・SHA 解決失敗・旧キャッシュでは null */
    dataCommitSha: String?,
    useTestData: Boolean,
    useBundledData: Boolean,
    /** 地図タブで最後に記録した生 POI の件数(#70。開発者モード ON の検索時だけ記録される) */
    nearbyPoiCount: Int,
    onBack: () -> Unit,
    onDeveloperModeChange: (Boolean) -> Unit,
    onDataCommitRefChange: (String) -> Unit,
    onUseTestDataChange: (Boolean) -> Unit,
    onUseBundledDataChange: (Boolean) -> Unit,
    onTestNotification: (Long) -> Unit,
    onClearNotifiedCampaigns: () -> Unit,
    onOpenNearbyPois: () -> Unit,
) {
    BackHandler(onBack = onBack)

    // 開発者モードの切替確認ダイアログ。非 null なら表示中で、値が切替先(true=オン/false=オフ)。
    // ON は想定外挙動の注意、OFF は一括リセットの注意と、どちらの方向も確認を挟む
    var developerModeDialogTarget by remember { mutableStateOf<Boolean?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ListItem(
            headlineContent = { Text("開発者モード") },
            supportingContent = { Text("開発・検証用の設定を表示します") },
            trailingContent = {
                Switch(
                    checked = developerMode,
                    onCheckedChange = { enabled -> developerModeDialogTarget = enabled },
                )
            },
            colors = transparentListItemColors(),
        )
        if (developerMode) {
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
                colors = transparentListItemColors(),
            )
            ListItem(
                headlineContent = { Text("同梱データを使う") },
                supportingContent = { Text("APK 同梱の JSON を直接表示します(リモート取得を停止)。push せずにデータ変更を実機検証する用") },
                trailingContent = { Switch(checked = useBundledData, onCheckedChange = onUseBundledDataChange) },
                colors = transparentListItemColors(),
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
                        containerColor = Color.Transparent,
                        headlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        supportingColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        trailingIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    )
                } else {
                    transparentListItemColors()
                },
            )

            // 地図の取得データの実測(#70)。YOLP の生 POI と照合・間引き結果を確認する
            // (alias 補完の要否判断・yolp_coverage_note の実測根拠の材料)
            SettingsSectionHeader("地図")
            ListItem(
                headlineContent = { Text("取得した地図データ") },
                supportingContent = {
                    Text(
                        if (nearbyPoiCount > 0) "最後の検索で取得した${nearbyPoiCount}件と照合結果"
                        else "地図タブでお店を検索すると記録されます",
                    )
                },
                modifier = Modifier.clickable(onClick = onOpenNearbyPois),
                colors = transparentListItemColors(),
            )

            // 通知(#6)のテスト。日次ジョブの発火時刻を待たずに本番と同じ判定・通知経路を通す
            SettingsSectionHeader("通知のテスト")
            ListItem(
                headlineContent = { Text("今すぐテスト通知") },
                supportingContent = { Text("その日の対象を判定して通知します(端末の通知許可が必要)") },
                modifier = Modifier.clickable { onTestNotification(0) },
                colors = transparentListItemColors(),
            )
            ListItem(
                headlineContent = { Text("${TEST_NOTIFICATION_DELAY_SECONDS}秒後にテスト通知") },
                supportingContent = { Text("押したあと画面を消して、消灯中の鳴り方を確認する用") },
                modifier = Modifier.clickable { onTestNotification(TEST_NOTIFICATION_DELAY_SECONDS) },
                colors = transparentListItemColors(),
            )
            ListItem(
                headlineContent = { Text("通知済み履歴を消す") },
                supportingContent = { Text("同じキャンペーンをもう一度通知させたいときに使います") },
                modifier = Modifier.clickable(onClick = onClearNotifiedCampaigns),
                colors = transparentListItemColors(),
            )
        }
    }

    developerModeDialogTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { developerModeDialogTarget = null },
            title = { Text(if (target) "開発者モードをオンにしますか？" else "開発者モードをオフにしますか？") },
            text = {
                Text(
                    if (target) {
                        "開発者モードは開発者専用です。テストデータの表示など、アプリが通常と異なる想定外の動作になることがあります。それでもオンにしますか？"
                    } else {
                        "開発者向け設定(テストデータ・同梱データ・データ取得先 commit)はすべて既定値に戻ります。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeveloperModeChange(target)
                    developerModeDialogTarget = null
                }) { Text(if (target) "オンにする" else "オフにする") }
            },
            dismissButton = {
                TextButton(onClick = { developerModeDialogTarget = null }) { Text("キャンセル") }
            },
        )
    }
}

/**
 * 「取得した地図データ」ページ(#70。開発者向け配下の 2 階層目)。地図タブで最後に取得した
 * YOLP の生 POI と照合・間引き結果の一覧。重複集約の前なので同一店舗の重複登録もそのまま並ぶ。
 * TSV コピーは collect-campaigns の alias 補完判断・yolp_coverage_note の実測根拠に渡す用。
 */
@Composable
internal fun DeveloperPoisPage(
    pois: List<MainViewModel.DebugPoi>,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    if (pois.isEmpty()) {
        Text(
            "記録がありません。開発者モードをオンにした状態で、地図タブでお店を検索すると記録されます。",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    val clipboard = LocalClipboardManager.current
    val matched = pois.count { it.matchLabel != null }
    val shown = pois.count { it.status == MainViewModel.DebugPoiStatus.SHOWN }
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "__summary") {
            Text(
                "取得${pois.size}件・照合${matched}件・表示${shown}件",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item(key = "__copy") {
            ListItem(
                headlineContent = { Text("一覧をコピー") },
                supportingContent = { Text("TSV 形式でクリップボードにコピーします") },
                modifier = Modifier.clickable { clipboard.setText(AnnotatedString(buildPoiTsv(pois))) },
                colors = transparentListItemColors(),
            )
        }
        itemsIndexed(pois) { index, poi ->
            ListItem(
                headlineContent = { Text(poi.name) },
                supportingContent = {
                    Text(
                        "%.4f, %.4f・照合: %s".format(poi.lat, poi.lon, poi.matchLabel ?: "一致なし"),
                    )
                },
                trailingContent = {
                    Text(
                        poi.status.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (poi.status == MainViewModel.DebugPoiStatus.SHOWN) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
                colors = transparentListItemColors(),
            )
        }
    }
}

private fun buildPoiTsv(pois: List<MainViewModel.DebugPoi>): String = buildString {
    appendLine("poi_name\tlat\tlon\tmatch\tstatus")
    pois.forEach { p ->
        appendLine("${p.name}\t${p.lat}\t${p.lon}\t${p.matchLabel.orEmpty()}\t${p.status.label}")
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
            transparentListItemColors()
        } else {
            ListItemDefaults.colors(
                containerColor = Color.Transparent,
                headlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
        },
    )
}
