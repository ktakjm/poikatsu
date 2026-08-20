package com.ktakjm.poikatsu

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ktakjm.poikatsu.data.ThemeMode
import com.ktakjm.poikatsu.notification.CampaignNotifications
import com.ktakjm.poikatsu.ui.MainViewModel
import com.ktakjm.poikatsu.ui.PoikatsuApp
import com.ktakjm.poikatsu.ui.theme.PoikatsuTheme

class MainActivity : ComponentActivity() {

    // setContent の viewModel() と同じ Activity スコープの同一インスタンス。
    // 通知タップ(#82)は onCreate/onNewIntent という Compose の外で受けるため、ここでも持つ
    private val notificationViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleNotificationIntent(intent)
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

    /** 通知タップ(#82)。launchMode=singleTop なので起動中のタップはここに来る */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    /**
     * 通知タップの extra(キャンペーングループキー)を VM へ渡す。データロードが非同期のため、
     * 詳細カードを開くタイミングは VM 側が判断する(pending として保持し、データが揃ったら開く)。
     * extra は消費したら消す——構成変更(回転等)で onCreate が同じ intent で再実行されたとき、
     * ユーザーが閉じた詳細カードを勝手に開き直さないため。
     */
    private fun handleNotificationIntent(intent: Intent?) {
        if (intent?.hasExtra(CampaignNotifications.EXTRA_CAMPAIGN_GROUP_KEY) != true) return
        notificationViewModel.onNotificationTapped(
            intent.getStringExtra(CampaignNotifications.EXTRA_CAMPAIGN_GROUP_KEY),
        )
        intent.removeExtra(CampaignNotifications.EXTRA_CAMPAIGN_GROUP_KEY)
    }
}
