package com.ktakjm.poikatsu.domain

import com.ktakjm.poikatsu.data.Campaign

/**
 * 「同じキャンペーン」として1件に畳む単位のキー。
 *
 * campaigns.json は **(施策 × 決済手段) 単位**で収録している(自治体施策は決済事業者ごとに
 * エントリが分かれ、カスタムキャンペーンも決済手段ごとの Campaign へ展開される)。
 * ユーザーから見た1キャンペーンはその束なので、おトクタブの一覧カードと通知(#67)で
 * この粒度を共有する——片方だけで畳むと「一覧は1件なのに通知は5行」のようにずれる。
 *
 * 自治体施策のキーに接頭辞を付けるのは、地域名が施策 id と衝突しても混ざらないようにするため
 * (常設 card_program の card_id も同様)。
 *
 * 常設(isTimeLimited=false)の card_program は発行体(card_id)単位に束ねる(#81)。エポス優待の
 * ように1カードへ複数の常設施策がぶら下がると、おトクタブの常設セクションに同じカードが
 * 5枚並ぶため。期間限定の card_program は開催期間が施策ごとに違い1カードで表現できないため
 * 束ねない。通知は card_program を丸ごと対象外にしている([notificationTargets])のでこの分岐は
 * 通知の畳み込みに影響しない。
 */
fun campaignGroupKey(campaign: Campaign): String = when {
    campaign.campaignType == CampaignType.MUNICIPAL -> "municipal:" + (campaign.region?.name ?: campaign.id)
    campaign.isCustom -> customCampaignBaseId(campaign.id)
    campaign.campaignType == CampaignType.CARD_PROGRAM && campaign.cardId != null && !campaign.isTimeLimited ->
        "cardProgram:" + campaign.cardId
    else -> campaign.id
}
