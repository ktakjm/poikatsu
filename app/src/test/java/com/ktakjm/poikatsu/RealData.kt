package com.ktakjm.poikatsu

import com.ktakjm.poikatsu.data.MunicipalityMaster
import com.ktakjm.poikatsu.data.PoikatsuData
import com.ktakjm.poikatsu.data.PoikatsuJson
import java.io.File

/**
 * リポジトリ直下の実データ(data/)・ショーケース用テストデータ(data-test/)のフィクスチャ(#90)。
 * ユニットテストの CWD は app/ モジュールなので ../ で辿る。生 JSON とパース済み [PoikatsuData] を
 * 1 回だけ読んで全テストクラスで共有する(クラスごとの 3 行読み込みを置き換え)。
 */
object RealData {
    val production: RealDataSet by lazy { RealDataSet("../data") }
    val test: RealDataSet by lazy { RealDataSet("../data-test") }

    /** 自治体マスタ。data/ のみに置き、テストデータ利用時も同じファイルを使う(#90) */
    val municipalities: MunicipalityMaster by lazy {
        PoikatsuJson.parseMunicipalities(File("../data/municipalities.json").readText())
    }
}

class RealDataSet(dir: String) {
    val merchantsRaw: String = File("$dir/merchants.json").readText()
    val campaignsRaw: String = File("$dir/campaigns.json").readText()
    val paymentMethodsRaw: String = File("$dir/payment_methods.json").readText()
    val data: PoikatsuData = PoikatsuJson.parse(
        merchantsJson = merchantsRaw,
        campaignsJson = campaignsRaw,
        paymentMethodsJson = paymentMethodsRaw,
    )
}
