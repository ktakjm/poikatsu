package com.ktakjm.poikatsu.util

import java.text.Normalizer

object JapaneseText {

    // 長音「ー」は読みの一部なので残す
    private val ignoredChars = setOf(' ', '　', '・', '-', '‐', '‑', '–', '−', '.', ',', '\'', '&', '!', '?')

    /**
     * 検索用正規化: NFKC(全半角統一) → 小文字化 → 記号除去 → カタカナをひらがなへ。
     * 「セブン-イレブン」「ｾﾌﾞﾝｲﾚﾌﾞﾝ」「せぶんいれぶん」がすべて同じ文字列になる。
     */
    fun normalize(s: String): String {
        val nfkc = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
        val sb = StringBuilder(nfkc.length)
        for (ch in nfkc) {
            if (ch in ignoredChars) continue
            val code = ch.code
            sb.append(if (code in 0x30A1..0x30F6) (code - 0x60).toChar() else ch)
        }
        return sb.toString()
    }
}
