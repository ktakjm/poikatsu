package com.ktakjm.poikatsu.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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

@Serializable
data class MerchantRule(
    @SerialName("merchant_id") val merchantId: String,
    val note: String? = null,
    @SerialName("exclusion_note") val exclusionNote: String? = null,
    @SerialName("exclusion_patterns") val exclusionPatterns: List<String> = emptyList(),
    @SerialName("amex_excluded") val amexExcluded: Boolean = false,
    @SerialName("store_list_url") val storeListUrl: String? = null,
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
    @SerialName("facility_risk_patterns") val facilityRiskPatterns: List<String> = emptyList(),
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

@Serializable
data class ProfileCard(
    @SerialName("campaign_id") val campaignId: String,
    @SerialName("card_name") val cardName: String,
    val brand: String = "",
    @SerialName("entry_done") val entryDone: Boolean? = null,
    @SerialName("payment_account_mufg_bank") val paymentAccountMufgBank: Boolean? = null,
    @SerialName("effective_rate_default") val effectiveRateDefault: Double? = null,
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
