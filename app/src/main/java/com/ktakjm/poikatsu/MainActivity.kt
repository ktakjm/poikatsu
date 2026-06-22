package com.ktakjm.poikatsu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ktakjm.poikatsu.data.ThemeMode
import com.ktakjm.poikatsu.ui.MainViewModel
import com.ktakjm.poikatsu.ui.PoikatsuApp
import com.ktakjm.poikatsu.ui.theme.PoikatsuTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // テーマ設定をテーマ層に渡すため VM はここで生成し、同じインスタンスを PoikatsuApp に渡す。
            // Scaffold(TopAppBar/Snackbar 含む)は画面状態に応じて PoikatsuApp 側で構築する。
            val viewModel: MainViewModel = viewModel()
            val state by viewModel.state.collectAsState()
            val darkTheme = when (state.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // ステータスバー/ナビバーのアイコン明暗は「システムのダーク設定」ではなく「アプリのテーマ」に
            // 追従させる。地図モードを full-bleed(地図がステータスバー裏まで)にしたため、テーマ上書きで
            // システムと食い違うと地図上のアイコンが埋もれる。アプリ配色(=地図の明暗)に合わせて読めるようにする。
            // テーマが変わったときだけ適用すればよい(毎再コンポーズで叩かない)。
            val view = LocalView.current
            LaunchedEffect(view, darkTheme) {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            PoikatsuTheme(darkTheme = darkTheme, dynamicColor = state.dynamicColor) {
                PoikatsuApp(viewModel)
            }
        }
    }
}
