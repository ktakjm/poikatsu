package com.ktakjm.poikatsu.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.core.content.ContextCompat
import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.DataSource
import com.ktakjm.poikatsu.data.Merchant
import com.ktakjm.poikatsu.data.MerchantRule
import com.ktakjm.poikatsu.data.MIN_PURCHASE_SCOPE_PERIOD_TOTAL
import com.ktakjm.poikatsu.data.MIN_PURCHASE_SCOPE_TRANSACTION
import com.ktakjm.poikatsu.data.RegisteredArea
import com.ktakjm.poikatsu.data.SettingsBackup
import com.ktakjm.poikatsu.data.ThemeMode
import com.ktakjm.poikatsu.domain.BenefitType
import com.ktakjm.poikatsu.domain.CampaignType
import com.ktakjm.poikatsu.domain.campaignType
import com.ktakjm.poikatsu.domain.formatBenefit
import com.ktakjm.poikatsu.domain.isPrefectureWide
import com.ktakjm.poikatsu.domain.storeRatesVary
import com.ktakjm.poikatsu.domain.trimRate
import com.ktakjm.poikatsu.ui.theme.onWarningContainerColor
import com.ktakjm.poikatsu.ui.theme.warningContainerColor
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** "#RRGGBB" を Color に変換。形式が不正なら null */
internal fun parseBrandColor(hex: String?): Color? {
    val digits = hex?.removePrefix("#") ?: return null
    if (digits.length != 6) return null
    return digits.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
}

/** 背景色に対して読めるコンテンツ色(黒/白)を輝度から選ぶ */
internal fun onColorFor(background: Color): Color =
    if (background.luminance() > 0.5f) Color.Black else Color.White

internal fun dataStatusLabel(
    updatedAt: String,
    source: DataSource?,
    useTestData: Boolean = false,
    useBundledData: Boolean = false,
): String {
    val testLabel = if (useTestData) " [テストデータ]" else ""
    return "データ更新日：$updatedAt ${dataSourceLabel(source, useBundledData)}$testLabel"
}

/**
 * 設定トップ「キャンペーンデータ」行のサマリ。行タイトルと重複する「データ更新日：」の
 * 接頭辞を省いた短縮形(お店タブ・データサブページ内の表示は [dataStatusLabel] のまま)。
 */
internal fun dataRowSummary(
    updatedAt: String,
    source: DataSource?,
    useTestData: Boolean = false,
    useBundledData: Boolean = false,
): String {
    val testLabel = if (useTestData) " [テストデータ]" else ""
    val parts = listOf(updatedAt, dataSourceLabel(source, useBundledData)).filter { it.isNotBlank() }
    return parts.joinToString("・") + testLabel
}

// BUNDLED はトグルによる意図的な同梱表示と、キャッシュなしフォールバックの両方で立つため、
// 実データとの取り違え防止にトグル ON 中は開発者設定によるものだと明示する
private fun dataSourceLabel(source: DataSource?, useBundledData: Boolean): String = when {
    source == DataSource.BUNDLED && useBundledData -> "同梱データ表示中(開発者設定)"
    source == DataSource.REMOTE -> "最新データ取得済み"
    source == DataSource.CACHE -> "前回取得データ(オフライン？)"
    source == DataSource.BUNDLED -> "同梱データ(オフライン？)"
    else -> ""
}

/**
 * 設定画面「開発者向け設定」行のサマリ。非既定値の項目だけ列挙し、開発者モード ON 中に
 * どの設定が効いているかを画面遷移せず確認できるようにする(戻し忘れの気づき用)。
 */
internal fun developerSettingsSummary(
    dataCommitRef: String,
    useTestData: Boolean,
    useBundledData: Boolean,
): String {
    val active = buildList {
        if (useTestData) add("テストデータ ON")
        if (useBundledData) add("同梱データ ON")
        if (dataCommitRef.isNotBlank()) add("ref=$dataCommitRef")
    }
    return if (active.isEmpty()) "すべて既定値" else active.joinToString("・")
}

// ---- 設定トップのカテゴリ行サマリ(#47)。畳んだ情報をトップで一望するための純関数群 ----

/** ThemeMode の表示ラベル(表示サブページのセグメントボタンと「表示」行サマリで共用) */
internal fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "システム"
    ThemeMode.LIGHT -> "ライト"
    ThemeMode.DARK -> "ダーク"
}

/** 「表示」行のサマリ。dynamic color 非対応端末(Android 11 以下)では壁紙の色の項を出さない */
internal fun displaySettingsSummary(
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    dynamicSupported: Boolean,
): String = buildList {
    add("テーマ: ${themeModeLabel(themeMode)}")
    if (dynamicSupported) add(if (dynamicColor) "壁紙の色 ON" else "壁紙の色 OFF")
}.joinToString("・")

/**
 * 「お支払い方法」行のサマリ。登録のある種別だけ列挙し(カード=カタログ所有+カスタム)、
 * すべて未登録なら「未登録」。
 */
internal fun paymentMethodsSummary(cardCount: Int, brandCount: Int, qrCount: Int): String {
    val parts = buildList {
        if (cardCount > 0) add("カード${cardCount}枚")
        if (brandCount > 0) add("国際ブランド${brandCount}件")
        if (qrCount > 0) add("コード決済${qrCount}件")
    }
    return if (parts.isEmpty()) "未登録" else parts.joinToString("・")
}

/**
 * 登録地域の表示名「都道府県 名前」(#47)。「南部」だけではどこの南部か分からないため、
 * 登録済みリスト・サマリとも都道府県を冠する。
 */
internal fun areaDisplayName(area: RegisteredArea): String = "${area.prefecture} ${area.name}"

/**
 * 「マイエリア」行のサマリ。先頭の登録地域+「ほかN件」。
 * 未登録時は「未登録」だけだと登録する動機が伝わらないため、効果を一言添える。
 */
internal fun municipalitySummary(areas: List<RegisteredArea>): String = when {
    areas.isEmpty() -> "未登録(登録するとおトクタブを地域のキャンペーンに絞れます)"
    areas.size == 1 -> areaDisplayName(areas.first())
    else -> "${areaDisplayName(areas.first())} ほか${areas.size - 1}件"
}

/**
 * 通知時刻(0時からの分)の表示ラベル。「8:00頃」のように「頃」を付け、
 * WorkManager の省電力制約で厳密な時刻にならないことを示す。
 */
internal fun notifyTimeLabel(minutesOfDay: Int): String =
    "%d:%02d頃".format(minutesOfDay / 60, minutesOfDay % 60)

/** 「通知」行のサマリ(#6)。ON のときは通知の目安時刻まで出す */
internal fun notificationSummary(enabled: Boolean, notifyTimeMinutes: Int): String =
    if (enabled) "キャンペーン通知 オン(毎日${notifyTimeLabel(notifyTimeMinutes)})" else "キャンペーン通知 オフ"

/**
 * 「対象外に登録したお店」行のサマリ(#63)。未登録時は登録の入口(判定詳細)を一言添える
 * (この画面からは登録できないため)。
 */
internal fun excludedStoresSummary(count: Int): String =
    if (count == 0) "未登録(お店・地図タブの判定詳細から登録できます)" else "${count}件を登録中"

/**
 * 通知を出せる状態か(Android 13+ の POST_NOTIFICATIONS が許可済みか)。12 以下は実行時権限が
 * 無いため常に true。「許可を取ってから通知設定を ON にする」判断を、通知サブページの ON 操作(#6)と
 * 通知 ON のバックアップの復元(#50)で共有するために切り出している。
 */
internal fun notificationPermissionGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED

/**
 * エクスポートの既定ファイル名(#50)。日付入りにして、複数世代を同じフォルダに残しても
 * 上書き確認にならず、どの時点のものか見分けられるようにする。
 */
internal fun backupFileName(today: LocalDate): String =
    "poikatsu-settings-${today.format(DateTimeFormatter.BASIC_ISO_DATE)}.json"

/**
 * インポート確認ダイアログに出す、選んだファイルの中身の要約(#50)。上書きしてよいファイルかを
 * 件数で確かめられるようにする。件数 0 の種別は並べない(空の項目で埋めても判断材料にならない)。
 */
internal fun backupContentSummary(backup: SettingsBackup): String {
    val parts = buildList {
        if (backup.cardOverrides.isNotEmpty()) add("マイカードの設定${backup.cardOverrides.size}件")
        if (backup.customCards.isNotEmpty()) add("カスタムカード${backup.customCards.size}枚")
        if (backup.ownedBrands.isNotEmpty()) add("国際ブランド${backup.ownedBrands.size}件")
        if (backup.enabledQrPaymentIds.isNotEmpty()) add("コード決済${backup.enabledQrPaymentIds.size}件")
        if (backup.registeredAreas.isNotEmpty()) add("マイエリア${backup.registeredAreas.size}件")
        if (backup.customCampaigns.isNotEmpty()) {
            add("自分で登録したキャンペーン${backup.customCampaigns.size}件")
        }
        if (backup.excludedStorePairs.isNotEmpty()) {
            add("対象外に登録したお店${backup.excludedStorePairs.size}件")
        }
    }
    return if (parts.isEmpty()) "登録内容なし(表示・通知の設定のみ)" else parts.joinToString("・")
}

/** 「開発者向け」行のサマリ。ON 中は非既定値([developerSettingsSummary])まで出し戻し忘れに気づけるように */
internal fun developerRowSummary(
    developerMode: Boolean,
    dataCommitRef: String,
    useTestData: Boolean,
    useBundledData: Boolean,
): String = if (!developerMode) {
    "開発者モード オフ"
} else {
    "開発者モード オン・${developerSettingsSummary(dataCommitRef, useTestData, useBundledData)}"
}

/**
 * 警告・注意のトーナル面表示(アイコン + 文)。container/content の対で error(致命) / warning(注意) を出し分ける。
 * グレーのカード地に色文字を直接乗せるとコントラストが不足するため、専用の淡い面の上に濃い文字で出す。
 * アイコン/文字の色は Surface の contentColor から自動で引き継ぐ。
 * 注意でない案内(提示のみ注記等の secondary 面)に使うときは [icon] を Info に差し替える
 * (⚠のままだと面の色を利点用にしても警告に読めてしまう)。
 *
 * 解決手段がある注意([actionLabel] + [onAction])は面の下に右寄せのボタンで出す(M3 の banner の型)。
 * 面全体をタップ領域にはしない——注意文の面はタップできるように見えず、押しても何が起きるか読めないため。
 */
@Composable
internal fun NoticeRow(
    text: String,
    containerColor: Color,
    contentColor: Color,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    icon: ImageVector = Icons.Default.Warning,
) {
    val hasAction = actionLabel != null && onAction != null
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // ボタンは自前に余白を持つので、ある場合だけ面の下余白を詰める
        Column(
            Modifier.padding(
                start = 12.dp,
                end = 12.dp,
                top = 8.dp,
                bottom = if (hasAction) 4.dp else 8.dp,
            )
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp).padding(top = 2.dp),
                )
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
            if (hasAction) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    // 枠線つき(中強調)。テキストボタンだと注意面に埋もれて操作と気づけない。
                    // 線・文字はどちらも面の content 色に合わせる(既定の primary はブランド色なので、
                    // 警告面の上では意味が混ざりコントラストも崩れる)
                    OutlinedButton(
                        onClick = onAction!!,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = LocalContentColor.current,
                        ),
                        border = BorderStroke(1.dp, LocalContentColor.current),
                    ) { Text(actionLabel!!) }
                }
            }
        }
    }
}

/**
 * 端末のこのアプリの通知設定画面を開く。実行時パーミッションのダイアログを出せない状態
 * (2 回拒否済み等、システムが要求を無視する)ときの逃げ道。通知設定画面が無い端末では
 * アプリ情報画面にフォールバックする。
 */
internal fun openAppNotificationSettings(context: Context) {
    val notificationSettings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    runCatching { context.startActivity(notificationSettings) }.onFailure {
        val appDetails = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        )
        runCatching { context.startActivity(appDetails) }
    }
}

/**
 * この Composable を載せている Activity。`shouldShowRequestPermissionRationale`(Activity 必須)の
 * ために辿る。LocalContext は端末・構成によって ContextWrapper で包まれることがあるため素の
 * キャストにしない(activity-compose 1.10 の LocalActivity が使えるまでの代替)。
 */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * NoticeRow の複数項目版(「対象外」セクション等)。項目ごとに面を積むと視覚的に重いため、
 * 1 つのトーナル面の中に箇条書きでまとめる(1 件なら箇条書き記号を付けず NoticeRow と同じ見た目)。
 */
@Composable
internal fun NoticeList(items: List<String>, containerColor: Color, contentColor: Color) {
    if (items.isEmpty()) return
    if (items.size == 1) {
        NoticeRow(items[0], containerColor, contentColor)
        return
    }
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(18.dp).padding(top = 2.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items.forEach {
                    Text("・$it", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** 非インタラクティブなカテゴリ表示。押せる見た目(Chip)を持たせない静的タグ */
@Composable
internal fun CategoryTag(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** [horizontalFadingEdges] のフェード幅。 */
private val FADING_EDGE_WIDTH = 24.dp

/**
 * 横スクロール行の端に「まだ続きがある」ことを示すフェード(Android 標準の fading edge 相当)。
 * スクロール余地のある側だけ内容をグラデーションで透明に落とす(右端=先に進める、左端=戻れる)。
 * DstIn 合成でマスクするため、描画を一旦オフスクリーンに逃がす CompositingStrategy が必要。
 * ブラシはサイズ確定時に一度だけ作る(drawWithCache。draw のたびに作るとシェーダキャッシュが効かない)。
 * horizontalScroll より前(外側)にチェーンすること。アプリは日本語のみのため RTL は考慮しない。
 */
internal fun Modifier.horizontalFadingEdges(scrollState: ScrollState): Modifier =
    this
        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithCache {
            val w = FADING_EDGE_WIDTH.toPx()
            val startFade = Brush.horizontalGradient(
                colors = listOf(Color.Transparent, Color.Black),
                startX = 0f,
                endX = w,
            )
            val endFade = Brush.horizontalGradient(
                colors = listOf(Color.Black, Color.Transparent),
                startX = size.width - w,
                endX = size.width,
            )
            onDrawWithContent {
                drawContent()
                if (scrollState.canScrollBackward) {
                    drawRect(brush = startFade, size = Size(w, size.height), blendMode = BlendMode.DstIn)
                }
                if (scrollState.canScrollForward) {
                    drawRect(
                        brush = endFade,
                        topLeft = Offset(size.width - w, 0f),
                        size = Size(w, size.height),
                        blendMode = BlendMode.DstIn,
                    )
                }
            }
        }

/**
 * YOLP 利用規約: 店舗データの帰属表示。YOLP 由来の店舗情報を表示する画面では常に出す。
 * 色・サイズを潰さないこと(docs/map-data-stack.md §3.2/§7)。
 */
@Composable
internal fun YolpAttribution(modifier: Modifier = Modifier) {
    Text(
        "店舗情報: Web Services by Yahoo! JAPAN",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
internal fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/**
 * surfaceContainer 系の面の上に置く ListItem 用の colors。ListItem は既定で自前の
 * containerColor(surface)を塗り、下の面を打ち消して白いブロックに見えるため透明にする。
 * 縦画面の全画面表示(素の surface 上)では background == surface のため、透明化しても
 * 見た目は変わらない(縦横で同じ Composable を共用できる)。
 */
@Composable
internal fun transparentListItemColors() = ListItemDefaults.colors(containerColor = Color.Transparent)

/**
 * 地図以外の画面共通の縦並びコンテナ。従来ルートにあった横16dpパディングをここに移譲。
 * 二ペインのペイン内容は画面端の側だけ余白を取る(ペイン間はライブラリの gutter が空ける)ため、
 * padding を差し替えられるようにしている。
 */
@Composable
internal fun PaddedColumn(
    padding: PaddingValues = PaddingValues(horizontal = 16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding),
        content = content,
    )
}

/** Campaign の特典テキスト(rate% / 円引き / % OFF / 円還元)。検索結果カード・判定詳細で共用 */
internal fun benefitText(campaign: Campaign): String =
    formatBenefit(BenefitType.fromString(campaign.benefitType), campaign.rateBase, campaign.discountAmount)
        ?.toString() ?: ""

/** 期間表示用の日付("2026/07/01")。年を省くと年跨ぎ期間が読めなくなるため常に年付きで出す */
internal fun formatPeriodDate(date: LocalDate): String =
    date.format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd"))

/**
 * 期間テキスト("2026/07/01〜2026/07/31")。開始日・終了日とも無い施策は、早期終了があり得る
 * (may_end_early。期限未発表)なら「終了日未定」、そうでなければ「常設」
 * (空欄・「〜」だけの表示にしない)。
 */
internal fun formatPeriod(campaign: Campaign): String {
    if (campaign.periodStart == null && campaign.periodEnd == null) {
        return if (campaign.mayEndEarly) "終了日未定" else "常設"
    }
    return buildString {
        campaign.periodStart?.let { append(formatPeriodDate(LocalDate.parse(it))) }
        append("〜")
        campaign.periodEnd?.let { append(formatPeriodDate(LocalDate.parse(it))) }
    }
}

internal fun formatCap(yen: Int): String =
    "%,d円".format(yen)

/** 期間限定バッジ */
@Composable
internal fun TimeLimitedBadge(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(4.dp),
        modifier = modifier,
    ) {
        Text(
            "期間限定",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * カスタムキャンペーン(ユーザー自作)バッジ。同梱データの施策(当方で条件を照合済み)と
 * 本人登録の情報を見分けられるように出す。注意の意味ではないので secondary 系。
 */
@Composable
internal fun CustomCampaignBadge(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(4.dp),
        modifier = modifier,
    ) {
        Text(
            "自作",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * 提示のみ(presentation_only。#80)バッジ。「支払わなくても提示だけで OK」という利点の表示で
 * 注意の意味ではないため warning 系にしない(自作バッジと同じ secondary 系)。
 */
@Composable
internal fun PresentationOnlyBadge(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(4.dp),
        modifier = modifier,
    ) {
        Text(
            "提示のみ",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * 対象商品限定(product_scope)バッジ。期間限定と同列に出す。
 * 「全商品には効かない」注意の意味なので warning 系(期間限定の tertiary と区別)。
 */
@Composable
internal fun ProductScopeBadge(modifier: Modifier = Modifier) {
    Surface(
        color = warningContainerColor(),
        contentColor = onWarningContainerColor(),
        shape = RoundedCornerShape(4.dp),
        modifier = modifier,
    ) {
        Text(
            "商品限定",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * 特定店舗限定(網羅リスト list_is_exhaustive。#64)バッジ。「対象のお店のみ」=対象店が
 * 確定列挙されていて掲載外は対象外、の意味。「対象外のお店あり」系のネガティブ表現に
 * しないこと(対象外店舗があり得るのは全キャンペーン共通で、バッジの無い施策には対象外が
 * 無いという誤読を誘発する)。「チェーンの全店には効かない」スコープ注意なので
 * 商品限定と同じ warning 系。
 */
@Composable
internal fun ExhaustiveStoreListBadge(modifier: Modifier = Modifier) {
    Surface(
        color = warningContainerColor(),
        contentColor = onWarningContainerColor(),
        shape = RoundedCornerShape(4.dp),
        modifier = modifier,
    ) {
        Text(
            "対象のお店のみ",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

// ---- 共通カード部品 ----

/** カード左端のブランドカラーストライプ(単色 or 斜め分割マルチカラー) */
@Composable
internal fun StripeBar(stripeColors: List<Color>, separatorColor: Color) {
    Box(
        Modifier
            .width(6.dp)
            .fillMaxHeight()
            .drawBehind {
                if (stripeColors.size == 1) {
                    drawRect(stripeColors[0])
                } else {
                    val gap = 1.dp.toPx()
                    val n = stripeColors.size
                    val segH = (size.height - gap * (n - 1)) / n
                    val skew = size.width * 0.5f
                    drawRect(separatorColor)
                    stripeColors.forEachIndexed { i, c ->
                        val segTop = i * (segH + gap)
                        val segBot = segTop + segH
                        val path = Path().apply {
                            if (i == 0) {
                                moveTo(0f, 0f); lineTo(size.width, 0f)
                            } else {
                                moveTo(0f, segTop + skew); lineTo(size.width, segTop - skew)
                            }
                            if (i == n - 1) {
                                lineTo(size.width, size.height); lineTo(0f, size.height)
                            } else {
                                lineTo(size.width, segBot - skew); lineTo(0f, segBot + skew)
                            }
                            close()
                        }
                        drawPath(path, c)
                    }
                }
            },
    )
}

/** ブランドカラー背景の決済手段バッジ */
@Composable
internal fun BrandBadge(label: String, brandColor: Color) {
    Surface(color = brandColor, shape = RoundedCornerShape(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = onColorFor(brandColor),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

// ---- 共通: 期間・上限・条件 (CampaignScreen / JudgmentScreen 共用) ----

@Composable
internal fun PeriodRow(campaign: Campaign) {
    PeriodRow(formatPeriod(campaign))
}

@Composable
internal fun PeriodRow(periodText: String) {
    Text("期間: $periodText", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
}

@Composable
internal fun CapRow(perTransaction: Int?, periodTotal: Int?, capNote: String?) {
    val capText = buildCapText(perTransaction, periodTotal)
    val text = when {
        capText != null && capNote != null -> "$capText ($capNote)"
        capText != null -> capText
        capNote != null -> capNote
        else -> return
    }
    Text("上限: $text", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
}

internal fun buildCapText(perTransaction: Int?, periodTotal: Int?): String? {
    if (perTransaction == null && periodTotal == null) return null
    return buildString {
        perTransaction?.let { append("1回あたり${formatCap(it)}") }
        if (perTransaction != null && periodTotal != null) append("、")
        periodTotal?.let { append("期間合計${formatCap(it)}") }
    }
}

@Composable
internal fun MinPurchaseRow(minPurchase: Int?, scope: String = MIN_PURCHASE_SCOPE_TRANSACTION) {
    if (minPurchase == null) return
    // period_total は「期間中の合計で超えればよい」型(PayPay×花王等)。1決済ごとと誤読させない
    val text = if (scope == MIN_PURCHASE_SCOPE_PERIOD_TOTAL) {
        "期間中の購入合計%,d円(税込)以上で適用(複数回の買い物の合算可)".format(minPurchase)
    } else {
        "%,d円(税込)以上の決済で適用".format(minPurchase)
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
}

/**
 * merchant のグループ名(未設定なら「{代表看板}グループ」)。検索結果の従属表示・
 * 地図の束ね見出し・判定詳細の業態行・施策詳細の対象ラベルで共用する。
 */
internal fun groupLabelOf(merchant: Merchant): String =
    merchant.groupLabel ?: "${merchant.name}グループ"

/**
 * 施策詳細の「対象:」に出すラベル一覧(#60)。managed 施策の merchant_rules を解決する:
 * - `banner_ids` で看板(業態)を限定したルールは**業態名**(「杏林堂だけ」のカスタムで
 *   「ツルハドラッグ」と出す誤解を避ける)
 * - 業態を持つ系列の全業態ルールは**グループ名**(「マツモトキヨシ」だと業態名なのか
 *   グループなのか区別できないため。一部除外(`ineligible_banner_ids`)もグループ扱いで、
 *   除外の内訳は判定カードの注記が示す)
 * - 業態を持たない merchant は従来どおり merchant 名
 */
internal fun campaignTargetLabels(
    campaigns: List<Campaign>,
    merchants: Map<String, Merchant>,
): List<String> = campaignTargetLabelGroups(campaigns, merchants).flatMap { it.labels }.distinct()

/** merchant_rule 1 件分の対象ラベル(業態限定は業態名 / 業態持ちの全業態はグループ名 / それ以外は merchant 名) */
private fun ruleTargetLabels(rule: MerchantRule, merchants: Map<String, Merchant>): List<String> {
    val merchant = merchants[rule.merchantId] ?: return emptyList()
    return when {
        rule.bannerIds.isNotEmpty() ->
            rule.bannerIds.mapNotNull { merchant.bannerName(it) }.ifEmpty { listOf(merchant.name) }
        merchant.banners.isNotEmpty() -> listOf(groupLabelOf(merchant))
        else -> listOf(merchant.name)
    }
}

/** 率別の対象ラベルグループ(#52)。rate = null は「率の区別なし」(単一グループ=従来表示) */
internal data class TargetLabelGroup(val rate: Double?, val labels: List<String>)

/**
 * 施策詳細の「対象:」を率別にグルーピングしたラベル一覧(#52)。J-POINT パートナーのように
 * 1 施策内で店舗ごとに率が異なる場合、「最大10%」+全店列挙だと低率店(セブン 1.5% 等)も
 * 最大率と誤読されるため、率ごとに分けて見せる。storeRates は merchant_id → 実効率
 * (UiState.campaignStoreRates。所有カードならクラス加算・1pt価値の合成済み)。
 * 率が 2 種類未満なら従来どおり単一グループ(rate = null)に畳む。
 * 率の無いルールが混在する場合は rate = null のグループとして末尾に置く。
 */
internal fun campaignTargetLabelGroups(
    campaigns: List<Campaign>,
    merchants: Map<String, Merchant>,
    storeRates: Map<String, Double> = emptyMap(),
): List<TargetLabelGroup> {
    val labeled = campaigns
        .filter { it.storeScope == "managed" }
        .flatMap { it.merchantRules }
        .flatMap { rule -> ruleTargetLabels(rule, merchants).map { label -> label to storeRates[rule.merchantId] } }
    val distinctRates = labeled.mapNotNull { it.second }.distinct()
    if (distinctRates.size < 2) {
        return listOf(TargetLabelGroup(null, labeled.map { it.first }.distinct()))
            .filter { it.labels.isNotEmpty() }
    }
    val (rated, unrated) = labeled.groupBy { it.second }.entries.partition { it.key != null }
    return rated.sortedByDescending { it.key!! }
        .map { TargetLabelGroup(it.key, it.value.map { p -> p.first }.distinct()) } +
        unrated.map { TargetLabelGroup(null, it.value.map { p -> p.first }.distinct()) }
}

/** 対象チェーン列挙をこの件数以下ならそのまま全部出す(超えたら折りたたむ) */
private const val TARGET_CHAINS_COLLAPSE_THRESHOLD = 6

/** 折りたたみ時に見せる先頭チェーン数(残りは「他N」に畳む) */
private const val TARGET_CHAINS_COLLAPSED_COUNT = 4

/**
 * 対象チェーンの「対象:」行(#52 の率別グループ対応)。空なら何も出さない。
 * 率別グループは「{率}%: 」を行頭に付けて率ごとに行を分ける。複数グループのときは
 * 全グループを 1 つの枠にまとめて一括で畳む(グループ行単位に畳むと、長いグループだけが
 * 枠付きになり短いグループが枠外に見えて分断されるため)。
 * 施策詳細の最上部(TargetChainSection)と、発行体束ね(#81)の施策カード内で共用する。
 * カード内では [noRatePrefix]=「対象のお店」(eligible_notes の「対象：」と行頭が被るため)、
 * [alwaysFramed]=true(折りたたみが無くても枠の面に入れ、注記の行に埋もれないようにする)。
 */
@Composable
internal fun TargetGroupLines(
    groups: List<TargetLabelGroup>,
    noRatePrefix: String = "対象",
    alwaysFramed: Boolean = false,
) {
    when {
        groups.size == 1 -> groups.single().let { group ->
            TargetLabelLine(
                prefix = group.rate?.let { "${trimRate(it)}%" } ?: noRatePrefix,
                names = group.labels,
                alwaysFramed = alwaysFramed,
            )
        }
        groups.isNotEmpty() -> RateGroupedTargetLines(groups, noRatePrefix, alwaysFramed)
    }
}

/**
 * 対象チェーン行の枠(surfaceContainerHigh の面)。onClick 非 null なら面全体をタップ可能。
 * タップ可能なときだけタッチ領域の最小 48dp を確保する(表示だけの枠は詰める)。
 */
@Composable
private fun TargetLabelFrame(
    onClick: (() -> Unit)?,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    val color = MaterialTheme.colorScheme.surfaceContainerHigh
    val row: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .then(if (onClick != null) Modifier.heightIn(min = 48.dp) else Modifier)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            content()
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = color, modifier = Modifier.fillMaxWidth()) { row() }
    } else {
        Surface(shape = shape, color = color, modifier = Modifier.fillMaxWidth()) { row() }
    }
}

/**
 * 率別グループが複数あるときの対象チェーン列挙(#52)。全グループを 1 つの面にまとめ、
 * どれかのグループが折りたたみ対象なら面全体をタップ可能(chevron 付き)にして一括で展開する。
 * 折りたたみ時も各グループの行と率は必ず見せる(先頭数件+「他N」)。全グループが短ければ
 * 面なしテキスト行([alwaysFramed] ならタップ不可の枠)にする(展開できない枠に chevron を出さない)。
 */
@Composable
private fun RateGroupedTargetLines(
    groups: List<TargetLabelGroup>,
    noRatePrefix: String = "対象",
    alwaysFramed: Boolean = false,
) {
    val needsFold = groups.any { it.labels.size > TARGET_CHAINS_COLLAPSE_THRESHOLD }
    var expanded by remember(groups) { mutableStateOf(false) }

    @Composable
    fun groupTexts() {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            groups.forEach { group ->
                val prefix = group.rate?.let { "${trimRate(it)}%" } ?: noRatePrefix
                val names = group.labels
                val label = if (expanded || names.size <= TARGET_CHAINS_COLLAPSE_THRESHOLD) {
                    "$prefix: ${names.joinToString("・")}"
                } else {
                    "$prefix: ${names.take(TARGET_CHAINS_COLLAPSED_COUNT).joinToString("・")} " +
                        "他${names.size - TARGET_CHAINS_COLLAPSED_COUNT}"
                }
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    when {
        !needsFold && !alwaysFramed -> groupTexts()
        !needsFold -> TargetLabelFrame(onClick = null) {
            Box(Modifier.weight(1f)) { groupTexts() }
        }
        else -> TargetLabelFrame(onClick = { expanded = !expanded }) {
            Box(Modifier.weight(1f)) { groupTexts() }
            Spacer(Modifier.width(8.dp))
            ExpandChevron(expanded)
        }
    }
}

/**
 * 対象チェーン列挙の 1 行(単一グループの「対象: ◯◯・◯◯」)。
 * 多チェーン(SMCC/MUFG の常設プログラム等)は全列挙が長大になるため先頭だけ見せて畳む。
 * 展開できることが伝わるよう行全体をタップ可能な面(chevron 付き)にする。
 * 畳む必要が無いときは面なしテキスト行([alwaysFramed] ならタップ不可の枠)。
 */
@Composable
private fun TargetLabelLine(prefix: String, names: List<String>, alwaysFramed: Boolean = false) {
    if (names.isEmpty()) return
    if (names.size <= TARGET_CHAINS_COLLAPSE_THRESHOLD) {
        val label = "$prefix: ${names.joinToString("・")}"
        val text: @Composable () -> Unit = {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (alwaysFramed) {
            TargetLabelFrame(onClick = null) { Box(Modifier.weight(1f)) { text() } }
        } else {
            text()
        }
        return
    }
    var expanded by remember(names) { mutableStateOf(false) }
    val label = if (expanded) {
        "$prefix: ${names.joinToString("・")}"
    } else {
        "$prefix: ${names.take(TARGET_CHAINS_COLLAPSED_COUNT).joinToString("・")} " +
            "他${names.size - TARGET_CHAINS_COLLAPSED_COUNT}"
    }
    TargetLabelFrame(onClick = { expanded = !expanded }) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        ExpandChevron(expanded)
    }
}

/** 対象チェーン枠の展開/折りたたみ chevron */
@Composable
private fun ExpandChevron(expanded: Boolean) {
    Icon(
        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
        contentDescription = if (expanded) "対象のお店を折りたたむ" else "対象のお店をすべて表示",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
    )
}

/**
 * キャンペーングループの表示タイトル。
 * 自治体: "都道府県名 自治体名"(県全域施策は県名のみ。「神奈川県 神奈川県」にしない)、
 * card_program: display_name → campaign.name(常設プログラムは固有名で呼ぶ。多チェーンでも
 * 「{先頭チェーン} 他Nチェーン」にしない)、
 * それ以外はフォールバック連鎖: display_name → 単一チェーンは看板名/merchant 名([singleMerchantTitle]) →
 * 複数チェーンは「{先頭チェーン} 他Nチェーン」(→ merchant_rules が無ければ campaign.name)。
 * 多チェーン施策が先頭 merchant 名だけで「1チェーンの施策」に見えないようにする。
 */
/**
 * 自治体施策の併催グループ(県全域+市区町村の同時開催)の地域併記ラベル。
 * 全施策が自治体施策で地域名が複数あるときだけ「千葉県・千葉市」(県全域は県名が region.name。
 * 県→市区町村の順)を返し、それ以外(単独・自治体以外を含む)は null。
 * 地図のお知らせピルと施策詳細タイトルで共用し、ピル「千葉市」/タイトル「千葉県」のように
 * 同じグループの文言が食い違わないようにする。
 */
internal fun municipalRegionsLabel(campaigns: List<Campaign>): String? {
    if (campaigns.isEmpty() || !campaigns.all { it.campaignType == CampaignType.MUNICIPAL }) return null
    val regions = campaigns.mapNotNull { it.region }
    val names = (regions.filter { it.isPrefectureWide } + regions.filterNot { it.isPrefectureWide })
        .map { it.name }
        .distinct()
    return if (names.size >= 2) names.joinToString("・") else null
}

/**
 * グループの最大還元率/特典テキスト(サマリーカード右側用)。抽選は比較に載せず「抽選」と表示する。
 * 表示する数字が「変動する率の最大値」のとき(店舗別 rate_override・条件別 rate_rules・
 * グループ内で率の異なる複数施策・rebate/discount の型混在)と、対象商品限定(product_scope。
 * 全商品には効かない)のときは「最大」を冠し、一律の率と誤認されないようにする。
 * [personalRates] に載っている施策(所有カードの card_program)は rate_base の代わりに
 * ユーザー実効率(ウエル活込み)で出す(お店タブの判定と同じ値になる)。
 *
 * 発行体束ね(#81)で rebate と discount が混在するグループは、%が大きい方の型だけを代表で
 * 出す(2.5%還元と30%OFFを「最大30%還元」に合成すると割引率が還元率に化ける)。同率は
 * 会計にその場で効く OFF を優先する。
 */
internal fun campaignGroupMaxBenefit(
    campaigns: List<Campaign>,
    personalRates: Map<String, Double> = emptyMap(),
): String? {
    val comparable = campaigns.filter { BenefitType.fromString(it.benefitType) != BenefitType.LOTTERY }
    if (comparable.isEmpty()) return "抽選"
    val byType = comparable.groupBy { BenefitType.fromString(it.benefitType) }
    val subset = if (byType.size == 1) {
        byType.values.first()
    } else {
        val rebateMax = byType[BenefitType.REBATE]?.let { effectiveRates(it, personalRates).maxOrNull() }
        val discountMax = byType[BenefitType.DISCOUNT]?.let { effectiveRates(it, personalRates).maxOrNull() }
        // 率を持たない型(定額のみ等)は%比較に載らない。両型とも率が無ければ OFF 側に倒す
        if ((rebateMax ?: Double.NEGATIVE_INFINITY) > (discountMax ?: Double.NEGATIVE_INFINITY)) {
            byType.getValue(BenefitType.REBATE)
        } else {
            byType.getValue(BenefitType.DISCOUNT)
        }
    }
    val type = BenefitType.fromString(subset.first().benefitType)
    val allRates = effectiveRates(subset, personalRates)
    val maxRate = allRates.maxOrNull()
    val maxDiscount = subset.mapNotNull { it.discountAmount }.maxOrNull()
    val label = formatBenefit(type, maxRate, maxDiscount)?.toString() ?: return null
    // 店舗別レートのばらつきは personalRates で allRates を実効率 1 値に絞った後も検知できるよう
    // 収録値(rate_override + rate_base)側で判定する(Campaign.storeRatesVary。施策詳細の「最大」と共通)
    val storeRatesVary = subset.any { it.storeRatesVary }
    val ratesVary = allRates.distinct().size > 1 || storeRatesVary || byType.size > 1 ||
        subset.any { it.rateRules.isNotEmpty() || it.productScope != null }
    return if (maxRate != null && ratesVary) "最大$label" else label
}

/**
 * 施策列の表示候補レート一覧。所有カードの card_program はユーザー実効率が最大値
 * (店舗別 rate_override はカードのクラス加算・1pt価値でこれ以下にスケールされる。#52)。
 * 収録値の rate_override を混ぜると 1pt価値 < 1円 等の設定時に実際より大きい「最大◯%」が
 * 出るため、personalRates に載っている施策は実効率だけを使う
 */
private fun effectiveRates(campaigns: List<Campaign>, personalRates: Map<String, Double>): List<Double> =
    campaigns.flatMap { c ->
        personalRates[c.id]?.let { return@flatMap listOf(it) }
        c.merchantRules.mapNotNull { it.rateOverride } +
            c.rateRules.map { it.rate } +
            listOfNotNull(c.rateBase)
    }

/**
 * 発行体束ね(#81)のグループか: 同一 card_id の常設 card_program が2件以上。
 * エポス優待のように1カードへ複数の常設施策がぶら下がる場合、おトクタブの一覧では
 * 1カードに束ねて出す([campaignGroupKey] の cardProgram 分岐で畳まれたグループ)。
 * 1件だけのカード(dカード特約店等)は従来表示のまま。
 */
internal fun isCardProgramBundle(campaigns: List<Campaign>): Boolean =
    campaigns.size >= 2 &&
        campaigns.all { it.campaignType == CampaignType.CARD_PROGRAM && it.cardId != null } &&
        campaigns.mapTo(HashSet()) { it.cardId }.size == 1

/**
 * 発行体束ねカードの内訳サブ行。「モンテローザ優待 / KEYUCA優待 / カラオケ館優待 ほか2件」。
 * 施策名は display_name から operator 接頭辞(「エポスカード 」)を除いた短縮形
 * (束ねタイトルが発行体名を出すため重複させない)。束ねグループ以外は null。
 */
internal fun cardProgramBundleSubtitle(campaigns: List<Campaign>): String? {
    if (!isCardProgramBundle(campaigns)) return null
    val names = campaigns.map { c ->
        (c.displayName ?: c.name).removePrefix("${c.operator} ").ifBlank { c.name }
    }
    val shown = names.take(3).joinToString(" / ")
    val rest = names.size - 3
    return if (rest > 0) "$shown ほか${rest}件" else shown
}

/**
 * 施策グループ(同一カード/詳細にまとめて出す施策列)の表示タイトル。
 * 自治体の併催グループ(地図のお知らせピル発)は地域併記([municipalRegionsLabel])、
 * 発行体束ね(#81)は「{operator} 優待・特典」(施策ごとの display_name はサブ行・内訳側で出す)、
 * それ以外は先頭施策のタイトル(単数版の [campaignGroupDisplayTitle])。
 */
internal fun campaignGroupDisplayTitle(group: List<Campaign>, merchants: Map<String, Merchant>): String =
    municipalRegionsLabel(group)
        ?: (if (isCardProgramBundle(group)) "${group.first().operator} 優待・特典" else null)
        ?: campaignGroupDisplayTitle(group.first(), merchants)

internal fun campaignGroupDisplayTitle(first: Campaign, merchants: Map<String, Merchant>): String =
    if (first.campaignType == CampaignType.MUNICIPAL) {
        val prefecture = first.region?.prefecture ?: ""
        val name = first.region?.name ?: first.name
        if (prefecture.isNotBlank() && name != prefecture) "$prefecture $name" else name
    } else if (first.campaignType == CampaignType.CARD_PROGRAM) {
        first.displayName ?: first.name
    } else {
        first.displayName ?: run {
            val chainIds = first.merchantRules.map { it.merchantId }.distinct()
            val head = chainIds.firstOrNull()?.let { merchants[it] }
            when {
                head == null -> first.name
                chainIds.size == 1 -> singleMerchantTitle(first, head)
                else -> "${head.name} 他${chainIds.size - 1}チェーン"
            }
        }
    }

/**
 * 単一チェーン施策のタイトル。看板スコープ(#69): 全ルールが `banner_ids` 持ちで対象看板が
 * 1 つに絞られていれば**その看板名**(福太郎限定クーポンを系列代表の「ツルハドラッグ」と
 * 出さない)。看板 2 つ以上・全看板(banner_ids 未指定)・一部除外は従来どおり merchant 名。
 * 判定詳細の「対象:」([ruleTargetLabels])が業態名で出すのと表記を揃える。
 */
private fun singleMerchantTitle(first: Campaign, merchant: Merchant): String {
    if (first.merchantRules.any { it.bannerIds.isEmpty() }) return merchant.name
    val bannerId = first.merchantRules.flatMap { it.bannerIds }.distinct().singleOrNull()
    return bannerId?.let { merchant.bannerName(it) } ?: merchant.name
}

@Composable
internal fun VerifiedDateRow(verifiedDate: String) {
    if (verifiedDate.isBlank()) return
    Text(
        "情報確認日：$verifiedDate / 最新の条件は公式サイトで確認してください",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
    )
}
