package com.ktakjm.poikatsu.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ktakjm.poikatsu.data.Merchant
import com.ktakjm.poikatsu.domain.CampaignJudgment
import com.ktakjm.poikatsu.domain.ExpiringPointNotice
import com.ktakjm.poikatsu.domain.campaignGroupDisplayTitle

// 横画面で詳細を「ペインの中」に出すときの共通部品(#90 で PoikatsuApp と NearbyScreen の重複を集約)。
// お店/おトクタブの詳細ペイン(TabListDetailScaffold)と地図タブのサイドシート(NearbyDetailSideSheet)が
// 同じ見出し+本文の組み合わせを使う。全画面(縦・一ペイン)は TopAppBar 経路で別(PoikatsuApp の topBar)。

/** 判定詳細のタイトル。全画面時の TopAppBar と二ペインの詳細ペインヘッダで共用する。 */
internal fun selectionTitle(selection: MainViewModel.Selection): String =
    selection.displayName ?: selection.merchant.name

/** 店舗判定のタイトル。全画面時の TopAppBar と二ペインの詳細ペインヘッダで共用する。 */
internal fun storeCheckTitle(storeCheck: MainViewModel.StoreCheckState): String =
    "${storeCheck.merchant.name} 対象判定"

/**
 * ペインの見出し行(#54)。全画面時に TopAppBar が担うタイトルと操作をペイン内で置き換える。
 * 一覧ペインはタイトル+再取得(trailing)、判定詳細は右端の✕(ペインを閉じる=カード様式)、
 * 店舗判定は左端の←(1 段深い画面から判定詳細へ戻る=TopAppBar 様式)。
 * [modifier] は [PaddedColumn] に入れない設定タブ(#56)が端の余白を自分で当てるためのもの。
 */
@Composable
internal fun PaneHeader(
    title: String,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        leading()
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

/**
 * 店舗判定のペイン表示。判定詳細から 1 段深い画面なので左端の←で判定詳細へ戻る(TopAppBar 様式)。
 */
@Composable
internal fun StoreCheckPane(
    storeCheck: MainViewModel.StoreCheckState,
    onClose: () -> Unit,
    onStoreNameChange: (String) -> Unit,
) {
    PaneHeader(
        title = storeCheckTitle(storeCheck),
        leading = {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "判定詳細に戻る")
            }
        },
    )
    StoreCheckScreen(storeCheck = storeCheck, onBack = onClose, onStoreNameChange = onStoreNameChange)
}

/** 判定詳細のペイン表示。右端の✕でペインを閉じる(カード様式) */
@Composable
internal fun JudgmentDetailPane(
    selection: MainViewModel.Selection,
    onClose: () -> Unit,
    onOpenStoreCheck: () -> Unit,
    onFindNearby: () -> Unit,
    onExcludeStore: (campaignId: String, storeName: String) -> Unit,
    onRestoreExcludedStore: (campaignId: String) -> Unit,
    expiringNotices: List<ExpiringPointNotice>,
) {
    PaneHeader(
        title = selectionTitle(selection),
        trailing = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "詳細を閉じる")
            }
        },
    )
    JudgmentDetail(
        selection = selection,
        onBack = onClose,
        onOpenStoreCheck = onOpenStoreCheck,
        onFindNearby = onFindNearby,
        onExcludeStore = onExcludeStore,
        onRestoreExcludedStore = onRestoreExcludedStore,
        expiringNotices = expiringNotices,
    )
}

/** 施策詳細のペイン表示。右端の✕でペインを閉じる(カード様式)。編集・削除はカスタムキャンペーン由来のときだけ渡す */
@Composable
internal fun CampaignDetailPane(
    group: List<CampaignJudgment>,
    merchants: Map<String, Merchant>,
    storeRates: Map<String, Map<String, Double>>,
    onClose: () -> Unit,
    onFindChains: (List<String>) -> Unit,
    onEditCustom: (() -> Unit)? = null,
    onDeleteCustom: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(),
) {
    PaneHeader(
        title = campaignGroupDisplayTitle(group.map { it.campaign }, merchants),
        trailing = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "詳細を閉じる")
            }
        },
    )
    CampaignDetail(
        judgments = group,
        merchants = merchants,
        storeRates = storeRates,
        onBack = onClose,
        onFindChains = onFindChains,
        onEditCustom = onEditCustom,
        onDeleteCustom = onDeleteCustom,
        contentPadding = contentPadding,
    )
}
