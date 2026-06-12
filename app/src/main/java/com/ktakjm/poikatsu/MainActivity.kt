package com.ktakjm.poikatsu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.ktakjm.poikatsu.ui.PoikatsuApp
import com.ktakjm.poikatsu.ui.theme.PoikatsuTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PoikatsuTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PoikatsuApp(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}
