package com.ktakjm.poikatsu.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * アプリのテーマ(darkTheme)を配る CompositionLocal。PoikatsuTheme が provide する。
 * isSystemInDarkTheme() は OS のダーク設定を見るため、設定画面でテーマを上書きしたとき
 * (OS=ダーク・アプリ=ライト等)に colorScheme とズレて warning だけ暗い色が出る。
 * ステータスバーのアイコン明暗と同じく、必ずアプリのテーマに追従させる。
 */
val LocalAppDarkTheme = staticCompositionLocalOf { false }

// M3 標準カラーロールには「警告(warning)」が無い。致命/不可を表す error(赤)とは区別したい
// 注意喚起(例: 一部対象外店舗あり / 要確認)に使う琥珀系のセマンティックカラーを独自に定義する。
// 「注意=琥珀」の意味を一定に保つため dynamic color の影響は受けず固定値とする
// (error が端末によらず常に赤系であるのと同じ考え方)。
private val WarningLight = Color(0xFF8A5A00) // 明るい面で十分なコントラストを持つ濃いめの琥珀
private val WarningDark = Color(0xFFFFB951)  // 暗い面で映える明るめの琥珀

// warning の「コンテナ対」。M3 の errorContainer / onErrorContainer に対応する warning 版。
// グレーの surfaceVariant 等にテキストを直接乗せるとコントラストが保証されないため、
// 注意ボックス(NoticeRow)や状態ピル(StoreVerdictCard)は「淡い琥珀の面 + その上の濃い文字」で出す。
// warningColor()(濃い琥珀の単色)は、白に近い surface に直接乗せるテキスト色として引き続き使う。
// light の面は errorContainer(淡いピンク)と同程度のトーンまで彩度を抑える。
// 濃い琥珀だとライトモードで警告(注意)が error(致命)より目立ち、セマンティックの序列が逆転するため
private val WarningContainerLight = Color(0xFFFFECC9)   // 淡い琥珀の面(light)
private val OnWarningContainerLight = Color(0xFF2B1700) // その面の上に乗る濃い文字(light)
private val WarningContainerDark = Color(0xFF5E4200)    // 暗い面で映える琥珀のコンテナ(dark)
private val OnWarningContainerDark = Color(0xFFFFDEA6)  // その面の上に乗る明るい文字(dark)

/** 現在のダーク/ライトに応じた警告色(テキスト/アイコンのコンテンツ色として使う) */
@Composable
fun warningColor(): Color = if (LocalAppDarkTheme.current) WarningDark else WarningLight

/** 警告のトーナル面の色(注意ボックス・状態ピルの背景に使う) */
@Composable
fun warningContainerColor(): Color = if (LocalAppDarkTheme.current) WarningContainerDark else WarningContainerLight

/** warningContainerColor() の面の上に乗せる文字/アイコンの色 */
@Composable
fun onWarningContainerColor(): Color = if (LocalAppDarkTheme.current) OnWarningContainerDark else OnWarningContainerLight
