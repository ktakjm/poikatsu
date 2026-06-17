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

/** 現在のダーク/ライトに応じた警告色(テキスト/アイコンのコンテンツ色として使う) */
@Composable
fun warningColor(): Color = if (isSystemInDarkTheme()) WarningDark else WarningLight
