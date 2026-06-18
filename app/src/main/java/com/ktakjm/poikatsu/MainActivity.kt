package com.ktakjm.poikatsu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
            PoikatsuTheme(darkTheme = darkTheme, dynamicColor = state.dynamicColor) {
                PoikatsuApp(viewModel)
            }
        }
    }
}
