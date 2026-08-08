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
 * 自治体施策のキーに接頭辞を付けるのは、地域名が施策 id と衝突しても混ざらないようにするため。
 */
fun campaignGroupKey(campaign: Campaign): String = when {
    campaign.campaignType == CampaignType.MUNICIPAL -> "municipal:" + (campaign.region?.name ?: campaign.id)
    campaign.isCustom -> customCampaignBaseId(campaign.id)
    else -> campaign.id
}
