package com.ktakjm.poikatsu.domain

import com.ktakjm.poikatsu.data.ExcludedStorePair
import com.ktakjm.poikatsu.data.Merchant
import java.time.LocalDate

/**
 * 地図 POI を「薄いピン」で残すときの間引き理由(#77 施策5)。
 * いずれも「除外が無ければ特典が出ていた店」にだけ付く。
 */
enum class HiddenReason {
    /** 公式の店舗リストで対象外と明示された店([JudgmentEngine.isExcludedStore]) */
    OFFICIALLY_EXCLUDED,

    /** 網羅リスト(list_is_exhaustive。#64)に掲載がなく、その施策を間引いた結果 0 件になった店 */
    EXHAUSTIVE_INELIGIBLE,

    /** ユーザーが「このお店では対象外」と登録したペア(#63)で 0 件になった店 */
    USER_EXCLUDED,
}

/** 地図 POI の表示分類。[JudgmentEngine.classifyStore] の結果 */
sealed interface StoreVisibility {
    /** 通常ピン。ブリッジ中の下見残し(判定なし)も含むため [visible] は空になり得る */
    data class Shown(val result: JudgmentResult) : StoreVisibility {
        /** ピンの色・ラベルに使う施策(判定+並記枠の提示のみ施策) */
        val visible: List<CampaignJudgment>
            get() = result.judgments + result.presentationJudgments
    }

    /** 薄いピン。除外が無ければ特典が出ていた店を、理由付きで残す */
    data class Hidden(val reason: HiddenReason) : StoreVisibility

    /**
     * 描かない。[exhaustiveIneligible] は網羅リスト外の間引きがあったこと(ブリッジ中の 0 件文言の
     * 計数・開発者向け一覧の理由表示用。除外が無くても判定が無い店でも間引き自体は記録する)。
     */
    data class Dropped(val exhaustiveIneligible: Boolean) : StoreVisibility
}

/**
 * 地図 POI として照合済みの店を、通常ピン / 薄いピン+理由 / 描かない に分類する。
 * 近隣取得([loadNearbyAround])と設定変更時の再計算([recomputeNearbyPlaces])で同じ基準を共有する。
 *
 * - 判定(または並記枠)が残る店は通常ピン
 * - 除外(公式対象外・網羅リスト外・ユーザー登録)で 0 件になった店は、**除外が無ければ出ていた**
 *   場合だけ薄いピン。所有カードの都合等で除外に関係なく出ない店まで薄くすると
 *   「対象外」の意味がぼやけるため描かない
 * - [previewMerchantIds](施策詳細からのブリッジで絞り込み中のチェーン)は、除外にかかっていない
 *   判定なしの店を場所の下見用に通常ピン(還元ラベルなし)で残す
 */
fun JudgmentEngine.classifyStore(
    merchant: Merchant,
    bannerId: String?,
    storeName: String,
    today: LocalDate,
    enabledQrIds: Set<String>,
    excludedPairs: List<ExcludedStorePair>,
    memberships: Set<String>,
    previewMerchantIds: Set<String> = emptySet(),
): StoreVisibility {
    val officiallyExcluded = isExcludedStore(merchant, storeName)
    val excludedIds = excludedCampaignIdsFor(merchant, storeName, excludedPairs)
    val ineligibleIds = exhaustiveListIneligibleCampaignIds(merchant, storeName)
    val result = judgeAll(merchant, today, enabledQrIds, bannerId, excludedIds, ineligibleIds, memberships)
    val visibleIds = (result.judgments + result.presentationJudgments).map { it.campaign.id }
    if (!officiallyExcluded && visibleIds.isNotEmpty()) return StoreVisibility.Shown(result)

    if (officiallyExcluded || excludedIds.isNotEmpty() || ineligibleIds.isNotEmpty()) {
        // 除外を外して判定し直し、実際に施策を消した除外だけを理由にする
        val unrestricted = judgeAll(merchant, today, enabledQrIds, bannerId, memberships = memberships)
        val wouldShow = (unrestricted.judgments + unrestricted.presentationJudgments).map { it.campaign.id }
        // 公式対象外は店ごと除外なので、判定に残っていた施策も含めて全部が「消えた施策」
        val removed = if (officiallyExcluded) wouldShow else wouldShow - visibleIds.toSet()
        if (removed.isNotEmpty()) {
            val reason = when {
                officiallyExcluded -> HiddenReason.OFFICIALLY_EXCLUDED
                removed.any { it in ineligibleIds } -> HiddenReason.EXHAUSTIVE_INELIGIBLE
                else -> HiddenReason.USER_EXCLUDED
            }
            return StoreVisibility.Hidden(reason)
        }
    }
    // 除外に関係なく判定なし。ブリッジ中のチェーンは下見用に残すが、網羅リスト外・公式対象外の店は
    // 下見の意味が無いため残さない(施策詳細から「近くの対象のお店を探す」と全国の非対象店が並ぶ。#70)
    if (merchant.id in previewMerchantIds && !officiallyExcluded && ineligibleIds.isEmpty()) {
        return StoreVisibility.Shown(result)
    }
    return StoreVisibility.Dropped(exhaustiveIneligible = ineligibleIds.isNotEmpty())
}
