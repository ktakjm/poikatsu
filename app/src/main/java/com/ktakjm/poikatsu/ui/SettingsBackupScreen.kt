package com.ktakjm.poikatsu.ui

import android.Manifest
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ktakjm.poikatsu.data.SettingsBackup
import java.time.LocalDate

/** バックアップの MIME タイプ。書き出し(CreateDocument)の型指定に使う */
private const val BACKUP_MIME_TYPE = "application/json"

/**
 * 読み込み(OpenDocument)で選べるようにする型。JSON でも、共有元やクラウドのプロバイダによっては
 * text/plain や application/octet-stream として見えるため、書き出したファイルが
 * 「選択できない(グレーアウト)」状態にならないよう広めに許容する。
 */
private val BACKUP_OPEN_MIME_TYPES =
    arrayOf(BACKUP_MIME_TYPE, "text/plain", "application/octet-stream")

/**
 * バックアップサブページ(#50)。ユーザー設定を JSON 1 ファイルに書き出し・復元する。
 *
 * Auto Backup(Google バックアップ)が有効な端末では機種変更・再インストール時に自動復元される
 * (#51)ので、ここは自動復元が効かない場合(バックアップ無効・別 Google アカウントへの移行)の
 * 保険という位置づけ。手入力資産(カスタムカード・カスタムキャンペーン)を失う痛みが大きいため、
 * ユーザーが自分のタイミングで取れる控えを用意する。
 *
 * 保存先・読み込み元は SAF(CreateDocument / OpenDocument)でユーザーに選ばせる。アプリは
 * ストレージのパーミッションを持たず、選ばれた 1 ファイルにだけ触る。
 */
@Composable
internal fun BackupSettingsPage(
    /** 読み込み済みで未適用のバックアップ。非 null の間だけ復元の確認ダイアログを出す */
    pendingImport: SettingsBackup?,
    onBack: () -> Unit,
    onExport: (Uri) -> Unit,
    onPickImport: (Uri) -> Unit,
    /** 引数は通知を出せる状態か。false で復元するとキャンペーン通知は OFF に落とされる */
    onConfirmImport: (Boolean) -> Unit,
    onCancelImport: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE)
    ) { uri -> uri?.let(onExport) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onPickImport) }
    // 通知 ON のバックアップを復元するときの通知パーミッション要求(Android 13+)。
    // 権限はバックアップに入れられない(アプリが持てる情報ではない)ため復元時にその場で確認する。
    // 許可・拒否のどちらでもそのまま復元へ進み、拒否なら通知だけ OFF になる
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> onConfirmImport(granted) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ListItem(
            headlineContent = { Text("設定をファイルに書き出す") },
            supportingContent = {
                Text("お支払い方法・マイエリア・自分で登録したカードとキャンペーンなど、この端末の設定を JSON 1 ファイルに保存します")
            },
            trailingContent = {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            },
            modifier = Modifier.clickable { exportLauncher.launch(backupFileName(LocalDate.now())) },
        )
        ListItem(
            headlineContent = { Text("ファイルから設定を復元") },
            supportingContent = { Text("書き出したファイルを選ぶと、いまの設定を上書きします") },
            trailingContent = {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            },
            modifier = Modifier.clickable { importLauncher.launch(BACKUP_OPEN_MIME_TYPES) },
        )
        // 自動バックアップとの関係の説明。選べる項目ではないので ListItem にせず、
        // 通知サブページと同じく末尾の説明段落として出す
        SettingsSectionHeader("自動バックアップとの関係")
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Google バックアップが有効な端末では、機種変更・再インストール時にこのアプリの設定も自動で復元されます。" +
                    "自動復元が効かない場合(バックアップを使っていない、別の Google アカウントに移す)に備えて、" +
                    "ファイルに控えを取っておけます。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "開発者向け設定は書き出しません。復元しても端末ごとの検証設定はそのまま残ります。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }

    if (pendingImport != null) {
        // 通知 ON のファイルなのに権限が無い = 復元しただけでは通知が来ない状態。
        // 「復元」で権限要求を挟み、要求することを事前に予告する
        val needsNotificationPermission =
            pendingImport.notificationsEnabled && !notificationPermissionGranted(context)
        AlertDialog(
            onDismissRequest = onCancelImport,
            title = { Text("設定を復元しますか？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "いまの設定をファイルの内容で上書きします。元に戻せません。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        backupSourceLabel(pendingImport),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        backupContentSummary(pendingImport),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (needsNotificationPermission) {
                        Text(
                            "キャンペーン通知がオンのファイルです。続けて通知の許可を確認します" +
                                "(許可しない場合、通知はオフで復元します)。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (needsNotificationPermission) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            onConfirmImport(true)
                        }
                    },
                ) { Text("復元") }
            },
            dismissButton = { TextButton(onClick = onCancelImport) { Text("キャンセル") } },
        )
    }
}

/**
 * 確認ダイアログに出す「いつ・どのバージョンで書き出したファイルか」。古い控えを取り違えて
 * 上書きしないための手掛かり。日時は ISO 形式のまま(T 区切りだけ空白に)出す。
 */
private fun backupSourceLabel(backup: SettingsBackup): String {
    val exported = backup.exportedAt.replace('T', ' ').substringBeforeLast('.')
    return buildList {
        if (exported.isNotBlank()) add("書き出し日時: $exported")
        if (backup.appVersion.isNotBlank()) add("アプリ ${backup.appVersion}")
    }.joinToString("・").ifEmpty { "書き出し日時: 不明" }
}
