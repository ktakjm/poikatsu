package com.ktakjm.poikatsu.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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
private val WarningContainerLight = Color(0xFFFFDEA6)   // 淡い琥珀の面(light)
private val OnWarningContainerLight = Color(0xFF2B1700) // その面の上に乗る濃い文字(light)
private val WarningContainerDark = Color(0xFF5E4200)    // 暗い面で映える琥珀のコンテナ(dark)
private val OnWarningContainerDark = Color(0xFFFFDEA6)  // その面の上に乗る明るい文字(dark)

/** 現在のダーク/ライトに応じた警告色(テキスト/アイコンのコンテンツ色として使う) */
@Composable
fun warningColor(): Color = if (isSystemInDarkTheme()) WarningDark else WarningLight

/** 警告のトーナル面の色(注意ボックス・状態ピルの背景に使う) */
@Composable
fun warningContainerColor(): Color = if (isSystemInDarkTheme()) WarningContainerDark else WarningContainerLight

/** warningContainerColor() の面の上に乗せる文字/アイコンの色 */
@Composable
fun onWarningContainerColor(): Color = if (isSystemInDarkTheme()) OnWarningContainerDark else OnWarningContainerLight
