package com.ktakjm.poikatsu.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json

@Serializable
data class Merchant(
    val id: String,
    val name: String,
    val reading: String = "",
    val aliases: List<String> = emptyList(),
    val category: String = "",
)

@Serializable
data class MerchantsFile(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("updated_at") val updatedAt: String = "",
    val merchants: List<Merchant> = emptyList(),
)

@Serializable
data class RateBreakdown(
    val group: String = "",
    val condition: String,
    val rate: Double,
)

/**
 * 公式が対象/対象外を店舗名で言い切っているリスト。これがある merchant_rule だけ、
 * 別画面で店舗名を入力して判定する。
 * - ineligible_stores に一致 → 対象外
 * - eligible_stores に一致 → 対象
 * - どちらにも無い → 要確認(公式リスト外。一部対象外店舗があるため断定しない)
 * exclusion(ineligible)を優先判定する。
 */
@Serializable
data class OfficialStoreList(
    @SerialName("eligible_stores") val eligibleStores: List<String> = emptyList(),
    @SerialName("ineligible_stores") val ineligibleStores: List<String> = emptyList(),
    /** 断定の鮮度として表示する日付。official=true なら公式情報自体の更新日、false なら当方の確認日 */
    @SerialName("updated_date") val updatedDate: String = "",
    @SerialName("date_is_official") val dateIsOfficial: Boolean = false,
    @SerialName("source_url") val sourceUrl: String? = null,
)

@Serializable
data class MerchantRule(
    @SerialName("merchant_id") val merchantId: String,
    val note: String? = null,
    @SerialName("exclusion_note") val exclusionNote: String? = null,
    @SerialName("amex_excluded") val amexExcluded: Boolean = false,
    @SerialName("store_list_url") val storeListUrl: String? = null,
    @SerialName("official_store_list") val officialStoreList: OfficialStoreList? = null,
)

@Serializable
data class Campaign(
    val id: String,
    val issuer: String,
    @SerialName("brand_color") val brandColor: String? = null,
    val name: String,
    @SerialName("payment_instruction") val paymentInstruction: String,
    @SerialName("rate_base") val rateBase: Double,
    @SerialName("rate_max") val rateMax: Double = 0.0,
    @SerialName("rate_note") val rateNote: String = "",
    @SerialName("entry_required") val entryRequired: Boolean = false,
    @SerialName("period_start") val periodStart: String? = null,
    @SerialName("period_end") val periodEnd: String? = null,
    @SerialName("monthly_cap_note") val monthlyCapNote: String? = null,
    @SerialName("eligible_cards") val eligibleCards: List<String> = emptyList(),
    @SerialName("ineligible_cards") val ineligibleCards: List<String> = emptyList(),
    val conditions: List<String> = emptyList(),
    @SerialName("rate_breakdown") val rateBreakdown: List<RateBreakdown> = emptyList(),
    @SerialName("global_exclusions") val globalExclusions: List<String> = emptyList(),
    @SerialName("merchant_rules") val merchantRules: List<MerchantRule> = emptyList(),
    val sources: List<String> = emptyList(),
    @SerialName("verified_date") val verifiedDate: String = "",
)

@Serializable
data class CampaignsFile(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("updated_at") val updatedAt: String = "",
    val campaigns: List<Campaign> = emptyList(),
)

/**
 * ポイント価値の倍率(例: 三井住友カードの V ポイントはウエル活で 1.5 倍価値)。
 * 設定画面でこのカードに「ウエル活利用時の還元率を表示」チェックを出すかどうかと、
 * ON 時に掛ける係数を担う。label/factor をデータ側に持たせ UI に文言をハードコードしない。
 */
@Serializable
data class PointMultiplier(
    val label: String,
    val factor: Double,
    /** バッジ等に使う識別色(例: ウエルシアのコーポレートカラー)。"#RRGGBB" 形式 */
    val color: String? = null,
)

@Serializable
data class ProfileCard(
    @SerialName("campaign_id") val campaignId: String,
    @SerialName("card_name") val cardName: String,
    val brand: String = "",
    @SerialName("effective_rate_default") val effectiveRateDefault: Double? = null,
    @SerialName("point_multiplier") val pointMultiplier: PointMultiplier? = null,
    /** 実行時フラグ: ウエル活(×factor)を適用済みか。VM のマージで設定し JSON には現れない */
    @Transient val welcatsuApplied: Boolean = false,
    val notes: List<String> = emptyList(),
)

@Serializable
data class Profile(val cards: List<ProfileCard> = emptyList())

@Serializable
data class ProfileFile(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("updated_at") val updatedAt: String = "",
    val profile: Profile = Profile(),
)

data class PoikatsuData(
    val merchants: List<Merchant>,
    val campaigns: List<Campaign>,
    val profile: Profile,
    val updatedAt: String,
)

object PoikatsuJson {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    fun parse(merchantsJson: String, campaignsJson: String, profileJson: String): PoikatsuData {
        val merchantsFile = json.decodeFromString<MerchantsFile>(merchantsJson)
        val campaignsFile = json.decodeFromString<CampaignsFile>(campaignsJson)
        val profileFile = json.decodeFromString<ProfileFile>(profileJson)
        return PoikatsuData(
            merchants = merchantsFile.merchants,
            campaigns = campaignsFile.campaigns,
            profile = profileFile.profile,
            updatedAt = campaignsFile.updatedAt,
        )
    }
}
