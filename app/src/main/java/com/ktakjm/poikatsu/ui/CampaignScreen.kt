package com.ktakjm.poikatsu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ktakjm.poikatsu.data.Campaign
import com.ktakjm.poikatsu.data.Merchant
import com.ktakjm.poikatsu.data.StoreScope
import com.ktakjm.poikatsu.domain.CampaignJudgment
import com.ktakjm.poikatsu.domain.CampaignStatus
import com.ktakjm.poikatsu.domain.CampaignType
import com.ktakjm.poikatsu.domain.allStoreListsExhaustive
import com.ktakjm.poikatsu.domain.campaignGroupKey
import com.ktakjm.poikatsu.domain.campaignType
import com.ktakjm.poikatsu.domain.customCampaignBaseId
import com.ktakjm.poikatsu.domain.isCustom
import com.ktakjm.poikatsu.domain.isTargetDay
import com.ktakjm.poikatsu.domain.isTimeLimited
import com.ktakjm.poikatsu.domain.nextTargetDay
import com.ktakjm.poikatsu.domain.recurrenceLabel
import com.ktakjm.poikatsu.ui.theme.AppIcons
import com.ktakjm.poikatsu.ui.theme.onWarningContainerColor
import com.ktakjm.poikatsu.ui.theme.warningColor
import com.ktakjm.poikatsu.ui.theme.warningContainerColor
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// ==================== 一覧画面 ====================

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CampaignPane(
    activeCampaigns: List<Campaign>,
    upcomingCampaigns: List<Campaign>,
    /** 終了日を過ぎたカスタムキャンペーン。編集・削除の入口を残すため専用セクションに出す */
    expiredCustomCampaigns: List<Campaign>,
    merchants: Map<String, Merchant>,
    campaignColors: Map<String, String>,
    /** 施策 id → ユーザー実効率(所有カードの card_program のみ。お店タブと同じ基準の表示レート上書き) */
    personalRates: Map<String, Double>,
    filter: CampaignFilter,
    onFilterChange: (CampaignFilter) -> Unit,
    /** 登録エリアによる絞り込みチップを出すか(自治体登録あり かつ マスタ読込済みのとき) */
    showRegionChip: Boolean,
    /** 「登録地域のみ」絞り込み中か(既定 ON。OFF=すべて表示) */
    regionFilterOn: Boolean,
    onToggleRegionFilter: () -> Unit,
    /** 二ペイン時に詳細ペインへ出しているグループの先頭施策 id(選択カードのハイライト用)。一覧のみなら null */
    selectedGroupId: String? = null,
    onSelectGroup: (List<Campaign>) -> Unit,
) {
    // 地域絞り込みで 0 件のときは全画面の空表示にせず、チップ行と件数メッセージを出す
    // (「すべて」へ切り替える導線を残すため)。終了済みカスタムがあるときも一覧側に出す。
    // カスタムキャンペーンの登録導線はこの画面には無い(PoikatsuApp の FAB。空状態でも出ている)
    if (activeCampaigns.isEmpty() && upcomingCampaigns.isEmpty() && expiredCustomCampaigns.isEmpty() &&
        !(showRegionChip && regionFilterOn)
    ) {
        Centered {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 32.dp),
            ) {
                Icon(
                    AppIcons.LocalOffer,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(16.dp))
                Text("おトクなキャンペーンはありません", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "カード会社のキャンペーンや自治体のキャッシュレス還元キャンペーン、常設のポイントアップキャンペーンが登録されると、ここに表示されます。右下の＋からは会員ポータル限定クーポンなどを自分で登録できます。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        return
    }

    val filterFn: (Campaign) -> Boolean = when (filter) {
        CampaignFilter.ALL -> { _ -> true }
        CampaignFilter.MUNICIPAL -> { c -> c.campaignType == CampaignType.MUNICIPAL }
        CampaignFilter.NON_MUNICIPAL -> { c -> c.campaignType != CampaignType.MUNICIPAL }
    }
    val allActiveGroups = remember(activeCampaigns, filter) {
        groupCampaignsForDisplay(activeCampaigns.filter(filterFn))
    }
    val upcomingGroups = remember(upcomingCampaigns, filter) {
        groupCampaignsForDisplay(upcomingCampaigns.filter(filterFn))
    }
    // 常設(isTimeLimited=false。card_program・常設 promotion・終了日なしカスタム)は
    // 「期間限定」と混ぜず専用セクションに出す(見出しの軸は開催状態でなく限定性で統一。
    // 常設も開催中ではあるため旧見出し「開催中」は常設との対比がずれていた)
    val today = LocalDate.now()
    val (allPermanentGroups, timeLimitedActiveGroups) = allActiveGroups.partition { group ->
        group.none { it.isTimeLimited }
    }
    // recurrence 施策で今日が対象日でないグループは「期間限定」「常設」と混ぜず別セクションに出す
    // (期間内=開催中だが今日は使えないため。カード内で「次の対象日」を案内する)。
    // 常設側もたぬきの抽選会(毎月5/8/15/25日)のような recurrence 持ちがあるため同じ振り分けをする
    val (activeGroups, offDayGroups) = timeLimitedActiveGroups.partition { group ->
        group.any { isTargetDay(it, today) }
    }
    val (permanentGroups, permanentOffDayGroups) = allPermanentGroups.partition { group ->
        group.any { isTargetDay(it, today) }
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        // 末尾は FAB(56dp+マージン)に隠れない高さまで空ける
        contentPadding = PaddingValues(bottom = 88.dp),
    ) {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CampaignFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { onFilterChange(f) },
                        label = { Text(campaignFilterLabel(f)) },
                    )
                }
                if (showRegionChip) {
                    FilterChip(
                        selected = regionFilterOn,
                        onClick = onToggleRegionFilter,
                        label = { Text("登録地域のみ") },
                    )
                }
            }
        }
        if (activeGroups.isNotEmpty()) {
            item {
                Text(
                    "期間限定",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(activeGroups, key = { it.first().id }) { group ->
                CampaignSummaryCard(group, CampaignStatus.ACTIVE, merchants, campaignColors, personalRates, selected = group.first().id == selectedGroupId, onClick = { onSelectGroup(group) })
            }
        }
        if (permanentGroups.isNotEmpty()) {
            item {
                Text(
                    "常設",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            items(permanentGroups, key = { "permanent_${it.first().id}" }) { group ->
                CampaignSummaryCard(group, CampaignStatus.ACTIVE, merchants, campaignColors, personalRates, selected = group.first().id == selectedGroupId, onClick = { onSelectGroup(group) })
            }
        }
        if (offDayGroups.isNotEmpty()) {
            item {
                Text(
                    "期間限定（本日対象外）",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            items(offDayGroups, key = { "offday_${it.first().id}" }) { group ->
                CampaignSummaryCard(group, CampaignStatus.ACTIVE, merchants, campaignColors, personalRates, selected = group.first().id == selectedGroupId, onClick = { onSelectGroup(group) })
            }
        }
        if (permanentOffDayGroups.isNotEmpty()) {
            item {
                Text(
                    "常設（本日対象外）",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            items(permanentOffDayGroups, key = { "permanent_offday_${it.first().id}" }) { group ->
                CampaignSummaryCard(group, CampaignStatus.ACTIVE, merchants, campaignColors, personalRates, selected = group.first().id == selectedGroupId, onClick = { onSelectGroup(group) })
            }
        }
        if (upcomingGroups.isNotEmpty()) {
            item {
                Text(
                    "もうすぐ開始",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            items(upcomingGroups, key = { "upcoming_${it.first().id}" }) { group ->
                CampaignSummaryCard(group, CampaignStatus.UPCOMING, merchants, campaignColors, personalRates, selected = group.first().id == selectedGroupId, onClick = { onSelectGroup(group) })
            }
        }
        if (allActiveGroups.isEmpty() && upcomingGroups.isEmpty()) {
            item {
                Text(
                    if (showRegionChip && regionFilterOn) {
                        "登録地域に該当するキャンペーンはありません。「登録地域のみ」を外すと全て表示されます。"
                    } else {
                        "このフィルタに一致するキャンペーンはありません。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
        // 終了済みの自作キャンペーン。判定・上の一覧からは消えているが、編集(期間延長)・削除の
        // 入口としてここに残す。フィルタ(自治体/以外)は掛けない(自作は常に自治体以外のため)
        val expiredGroups = expiredCustomCampaigns
            .groupBy { customCampaignBaseId(it.id) }
            .values.toList()
        if (expiredGroups.isNotEmpty() && filter != CampaignFilter.MUNICIPAL) {
            item {
                Text(
                    "終了(自作)",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            items(expiredGroups, key = { "expired_${it.first().id}" }) { group ->
                CampaignSummaryCard(
                    group,
                    CampaignStatus.EXPIRED,
                    merchants,
                    campaignColors,
                    personalRates,
                    selected = group.first().id == selectedGroupId,
                    onClick = { onSelectGroup(group) },
                )
            }
        }
    }
}

/**
 * サマリーカード: SearchResultCard と同じレイアウト。
 * 左: 名前 + 期間限定バッジ / 期間
 * 右: 最大還元率
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CampaignSummaryCard(
    campaigns: List<Campaign>,
    status: CampaignStatus,
    merchants: Map<String, Merchant>,
    campaignColors: Map<String, String>,
    personalRates: Map<String, Double>,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val today = LocalDate.now()
    // 発行体束ね(#81): 同一カードの常設 card_program 複数施策は「{カード名} 優待・特典 N件」の
    // 1カードにまとめ、内訳(施策名)はサブ行+タップ先の詳細で見せる
    val bundle = isCardProgramBundle(campaigns)
    val title = campaignGroupDisplayTitle(campaigns, merchants)
    val hasTimeLimited = campaigns.any { it.isTimeLimited }
    val maxBenefit = campaignGroupMaxBenefit(campaigns, personalRates)

    val allEnds = campaigns.mapNotNull { c -> c.periodEnd?.let { LocalDate.parse(it) } }
    val allStarts = campaigns.mapNotNull { c -> c.periodStart?.let { LocalDate.parse(it) } }
    val earliestStart = allStarts.minOrNull()
    val latestEnd = allEnds.maxOrNull()
    // 常設(期間限定でない)グループは「常設」セクションの見出しが期間の説明を兼ねるため、
    // 日付が一切無ければ期間行を出さない(「終了日未定」は may_end_early=期間限定側の表現)
    val periodLabel = buildPeriodLabel(earliestStart, latestEnd)
        .takeUnless { !hasTimeLimited && earliestStart == null && latestEnd == null }
    val daysInfo = daysInfo(status, today, earliestStart, latestEnd)

    val fallback = MaterialTheme.colorScheme.primary
    val stripeColors = campaigns.mapNotNull { campaignColors[it.id] }.distinct()
        .mapNotNull { parseBrandColor(it) }
        .ifEmpty { listOf(fallback) }
    val separatorColor = MaterialTheme.colorScheme.surfaceContainerHigh

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        // 二ペイン時に詳細ペインへ出しているグループのハイライト(M3 list-detail の定石。
        // お店タブの SearchResultCard と同じ)。一覧のみの表示では常に false
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        // 本文が高さを決め、左端ストライプは matchParentSize で全高に追従する。
        // Row(IntrinsicSize.Min) だとタイトル+バッジの FlowRow が折り返したときに
        // 2行目がカード高さからクリップされるため使わない
        Box {
            Row(
                modifier = Modifier.padding(start = 20.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // タイトルとバッジが幅に入らないときはバッジを潰さず折り返して次の行に出す
                    FlowRow(
                        itemVerticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (bundle) {
                            Text(
                                "${campaigns.size}件",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (campaigns.any { it.isCustom }) {
                            CustomCampaignBadge()
                        }
                        if (hasTimeLimited) {
                            TimeLimitedBadge()
                        }
                        // 施策単位のバッジは発行体束ねでは出さない(一部の施策にしか当たらない
                        // バッジをグループに付けると誤解を生む。#59 の注記混入と同種の問題。
                        // 内訳のタップ先で施策ごとに従来どおり表示される)
                        if (!bundle) {
                            if (campaigns.any { it.presentationOnly }) {
                                PresentationOnlyBadge()
                            }
                            if (campaigns.any { it.productScope != null }) {
                                ProductScopeBadge()
                            }
                            if (campaigns.any { it.allStoreListsExhaustive }) {
                                ExhaustiveStoreListBadge()
                            }
                        }
                    }
                    cardProgramBundleSubtitle(campaigns)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (periodLabel != null || daysInfo != null) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            periodLabel?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            daysInfo?.let { (label, urgent) ->
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (urgent) warningColor() else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    // 早期終了があり得る施策では「残り○日」が断定に見えないよう注記を添える。
                    // 終了日が無い施策では比較対象の期限が無く「早期」がズレるため、
                    // 「予告なく終了」の言い回しに変える(期間ラベル「終了日未定」の補完)
                    if (campaigns.any { it.mayEndEarly }) {
                        Surface(
                            color = warningContainerColor(),
                            contentColor = onWarningContainerColor(),
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                if (latestEnd == null) "※予告なく終了する場合があります" else "※早期終了の可能性あり",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    recurrenceInfo(campaigns, status, today)?.let { (label, isToday) ->
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isToday) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (maxBenefit != null) {
                    Text(
                        maxBenefit,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Box(Modifier.matchParentSize()) {
                StripeBar(stripeColors, separatorColor)
            }
        }
    }
}

// ==================== 詳細画面 ====================

@Composable
internal fun CampaignDetail(
    judgments: List<CampaignJudgment>,
    merchants: Map<String, Merchant>,
    /** 施策 id → (merchant_id → 実効率)。店舗別レート施策の率別グルーピング用(UiState.campaignStoreRates) */
    storeRates: Map<String, Map<String, Double>> = emptyMap(),
    onBack: () -> Unit,
    /** 対象チェーンの地図ブリッジ(merchant_id 群を渡す。チップは1件、まとめては全件) */
    onFindChains: (List<String>) -> Unit,
    /** カスタムキャンペーンの編集(非 null のときだけ編集・削除の行を出す) */
    onEditCustom: (() -> Unit)? = null,
    /** カスタムキャンペーンの削除(確認ダイアログは呼び出し側が出す) */
    onDeleteCustom: (() -> Unit)? = null,
    /** 本文 LazyColumn の余白。二ペインの詳細ペインでは FAB に隠れない高さを下端に空ける(#55) */
    contentPadding: PaddingValues = PaddingValues(bottom = 16.dp),
) {
    BackHandler(onBack = onBack)

    // managed 施策の対象チェーン(自治体系は merchant_rules を持たないため出ない)。
    // グループは promotion なら1施策なので、実質その施策の merchant_rules を解決した一覧
    val campaigns = judgments.map { it.campaign }
    val chainIds = campaigns
        .filter { it.storeScope == StoreScope.MANAGED }
        .flatMap { c -> c.merchantRules.map { it.merchantId } }
        .distinct()
    // 発行体束ね(#81)の詳細は「対象:」を最上部で合成せず各施策カード内に出す(複数施策の
    // 対象を率別に混ぜると、どの対象がどの施策のものか読めない。ビッグエコーのように
    // 同じチェーンが別条件で複数施策に登場すると特に)。最上部は地図ブリッジのボタンだけ残す
    val bundle = isCardProgramBundle(campaigns)
    // 「対象:」の表示ラベル(業態対応の詳細は campaignTargetLabelGroups)。店舗別レートを持つ
    // 施策(J-POINT パートナー等)は率別にグルーピングして出す(#52。「最大10%」+全店列挙だと
    // 低率店も最大率と誤読されるため)。表示条件:
    // - 2件以上: 従来どおり列挙
    // - カスタム施策: タイトルが登録名固定で対象がどこにも出ないため 1 件でも出す
    // - 単一系列でも業態を持つグループ: タイトル(merchant 名)だけでは範囲が伝わらないため
    //   「対象: ◯◯グループ」を出す(「マツモトキヨシ」が業態かグループか区別できない問題)
    val allTargetGroups = campaignTargetLabelGroups(
        campaigns,
        merchants,
        campaigns.flatMap { storeRates[it.id]?.toList().orEmpty() }.toMap(),
    )
    val allLabelCount = allTargetGroups.sumOf { it.labels.size }
    val targetGroups = when {
        bundle -> emptyList()
        allLabelCount >= 2 -> allTargetGroups
        campaigns.any { it.isCustom } -> allTargetGroups
        chainIds.singleOrNull()?.let { merchants[it]?.banners?.isNotEmpty() } == true -> allTargetGroups
        else -> emptyList()
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = contentPadding,
    ) {
        // カスタムキャンペーンだけ編集・削除の操作行を出す(登録内容の管理はこの詳細に集約)
        if (onEditCustom != null || onDeleteCustom != null) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    onEditCustom?.let {
                        OutlinedButton(onClick = it, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("編集")
                        }
                    }
                    onDeleteCustom?.let {
                        OutlinedButton(onClick = it, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("削除")
                        }
                    }
                }
            }
        }
        // ブリッジは判定詳細(お店タブ)と同じく本文の上に置き、見た目・文言も揃える
        if (chainIds.isNotEmpty()) {
            item {
                TargetChainSection(
                    campaign = judgments.first { it.campaign.storeScope == StoreScope.MANAGED }.campaign,
                    chainIds = chainIds,
                    targetGroups = targetGroups,
                    onFindChains = onFindChains,
                )
            }
        }
        items(judgments, key = { it.campaign.id }) { judgment ->
            CampaignJudgmentCard(
                judgment,
                // 束ね時はその施策の merchant_rules だけから「対象:」を作りカード内に出す。
                // 対象が 1 チェーンでも省く条件(上の allLabelCount 判定)は適用しない——
                // タイトルが「{カード名} 優待・特典」で対象がどこにも出なくなるため
                targetGroups = if (bundle) {
                    campaignTargetLabelGroups(
                        listOf(judgment.campaign),
                        merchants,
                        storeRates[judgment.campaign.id].orEmpty(),
                    )
                } else {
                    emptyList()
                },
            )
        }
    }
}

/**
 * 対象チェーンの地図ブリッジ。主動線はお店タブと同じ FilledTonalButton(全チェーンで地図へ)。
 * チェーン個別の絞り込みは地図タブ側のフィルタピル(各✕で解除)に一本化し、ここではやらない
 * (チップだと「単独チェーンで地図表示」のアクションに読めないため廃止。2026-07)。
 * 複数チェーンのときは対象チェーン名の一覧を情報表示として添える(多数のときは先頭数件+
 * 「他N」に畳み、タップで全展開。常設 card_program の 30 チェーン級で詳細が埋まらないように)。
 * 開始前・recurrence 非対象日でも遷移は許可する(店舗の場所の下見用途。ブリッジ中の
 * チェーンは YOLP 検索対象に加わるが、判定が無いため還元率ラベルは出ない)。
 * その旨とタイミング(開始日/次の対象日)は warning 色の注意面(container 対)で目立たせる。
 */
@Composable
private fun TargetChainSection(
    campaign: Campaign,
    chainIds: List<String>,
    targetGroups: List<TargetLabelGroup>,
    onFindChains: (List<String>) -> Unit,
) {
    val today = LocalDate.now()
    val started = campaign.periodStart?.let { LocalDate.parse(it) <= today } != false
    val isTarget = started && isTargetDay(campaign, today)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(
            onClick = { onFindChains(chainIds) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (chainIds.size == 1) "近くのこのお店を探す" else "近くの対象のお店を探す")
        }
        // 表示するグループの選別(単一チェーンの同梱施策では空になる等)は CampaignDetail 側で行う
        TargetGroupLines(targetGroups)
        if (!isTarget) {
            val note = if (!started) {
                "開始前です。地図ではお店の場所のみ確認できます"
            } else {
                val next = nextTargetDay(campaign, today)
                    ?.let { "（次の対象日: ${it.monthValue}/${it.dayOfMonth}）" }.orEmpty()
                "本日は対象日ではありません$next。地図ではお店の場所のみ確認できます"
            }
            Surface(
                color = warningContainerColor(),
                contentColor = onWarningContainerColor(),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(note, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// ==================== 共通ヘルパー ====================

/** 一覧カードの期間ラベル。開始・終了とも無ければ「終了日未定」(「〜」だけの表示にしない) */
private fun buildPeriodLabel(earliestStart: LocalDate?, latestEnd: LocalDate?): String {
    if (earliestStart == null && latestEnd == null) return "終了日未定"
    return buildString {
        if (earliestStart != null) append(formatPeriodDate(earliestStart))
        append("〜")
        if (latestEnd != null) append(formatPeriodDate(latestEnd))
    }
}

/**
 * recurrence 施策のサマリー表示(「対象日: 毎週金・土曜 | 今日は対象日」等)。
 * グループ内に recurrence 施策が無い、または開催前なら null。Boolean は「今日が対象日」か。
 */
private fun recurrenceInfo(
    campaigns: List<Campaign>,
    status: CampaignStatus,
    today: LocalDate,
): Pair<String, Boolean>? {
    if (status != CampaignStatus.ACTIVE) return null
    val campaign = campaigns.firstOrNull { it.recurrence != null } ?: return null
    val pattern = recurrenceLabel(campaign.recurrence ?: return null)
    return if (isTargetDay(campaign, today)) {
        "対象日: $pattern | 今日は対象日" to true
    } else {
        val next = nextTargetDay(campaign, today)
        val nextLabel = next?.let { " | 次の対象日: ${it.monthValue}/${it.dayOfMonth}" }.orEmpty()
        "対象日: $pattern$nextLabel" to false
    }
}

private fun daysInfo(status: CampaignStatus, today: LocalDate, earliestStart: LocalDate?, latestEnd: LocalDate?): Pair<String, Boolean>? =
    when (status) {
        CampaignStatus.ACTIVE -> latestEnd?.let { end ->
            val days = ChronoUnit.DAYS.between(today, end).toInt()
            if (days >= 0) "残り${days}日" to (days <= 3) else null
        }
        CampaignStatus.UPCOMING -> earliestStart?.let { start ->
            val days = ChronoUnit.DAYS.between(today, start).toInt()
            if (days > 0) "あと${days}日で開始" to false else null
        }
        else -> null
    }

/**
 * 一覧カードの単位に畳む。畳み方(自治体は地域単位・カスタムは登録単位・同梱施策は id がユニーク
 * なので実質1件グループ)は通知(#67)と共有する——[campaignGroupKey]。
 * 自治体グループを先に並べるため、キーで畳む前に partition する。
 */
private fun groupCampaignsForDisplay(campaigns: List<Campaign>): List<List<Campaign>> {
    val (municipal, others) = campaigns.partition { it.campaignType == CampaignType.MUNICIPAL }
    return municipal.groupBy(::campaignGroupKey).values.toList() +
        others.groupBy(::campaignGroupKey).values.toList()
}

private fun campaignFilterLabel(filter: CampaignFilter): String = when (filter) {
    CampaignFilter.ALL -> "全て"
    CampaignFilter.MUNICIPAL -> "自治体"
    CampaignFilter.NON_MUNICIPAL -> "自治体以外"
}
