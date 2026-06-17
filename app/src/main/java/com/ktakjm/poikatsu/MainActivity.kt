package com.ktakjm.poikatsu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ktakjm.poikatsu.ui.PoikatsuApp
import com.ktakjm.poikatsu.ui.theme.PoikatsuTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Scaffold(TopAppBar/Snackbar 含む)は画面状態に応じて PoikatsuApp 側で構築する
            PoikatsuTheme {
                PoikatsuApp()
            }
        }
    }
}
