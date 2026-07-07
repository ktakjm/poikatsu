package com.ktakjm.poikatsu.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext

// 「dynamic color 中心・最小」方針: Android 12+ は壁紙由来の dynamic color、
// 11 以下は M3 標準ベースライン配色にフォールバックする。固定ブランド色は持たない
// (発行体の identity は地図ピン/カードラベルの brand_color 側で表現する)。
// 将来ブランド色を持たせたくなったら、シードから生成した配色をここに差し込めばよい。
private val DarkColorScheme = darkColorScheme()
private val LightColorScheme = lightColorScheme()

@Composable
fun PoikatsuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    // 独自セマンティックカラー(warning 系)が colorScheme と同じテーマ判定を使えるよう、
    // アプリとしての darkTheme を CompositionLocal で配る(ExtendedColors.kt 参照)
    CompositionLocalProvider(LocalAppDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}