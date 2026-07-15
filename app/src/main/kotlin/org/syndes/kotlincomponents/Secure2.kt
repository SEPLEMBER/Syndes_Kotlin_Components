package org.syndes.kotlincomponents

object Secure2 {

    // 95 kanji symbols for ASCII 32..126
    private val KANJI = arrayOf(
        "日", "本", "人", "大", "小", "中", "山", "川", "田", "目",
        "耳", "口", "手", "足", "力", "水", "火", "土", "風", "空",
        "海", "天", "心", "愛", "学", "校", "言", "語", "文", "書",
        "話", "行", "来", "見", "食", "飲", "車", "電", "駅", "家",
        "男", "女", "子", "年", "時", "分", "秒", "新", "古", "長",
        "短", "高", "低", "明", "暗", "赤", "青", "白", "黒", "金",
        "銀", "銅", "王", "魚", "鳥", "犬", "猫", "虫", "花", "草",
        "林", "森", "石", "走", "立", "座", "起", "泳", "歩", "歌",
        "泣", "笑", "喜", "怒", "怖", "旅", "宿", "室", "庭", "店",
        "村", "町", "都", "市", "県"
    )

    private val CHAR_TO_KANJI: Map<Char, String>
    private val KANJI_TO_CHAR: Map<String, Char>

    init {
        val forward = LinkedHashMap<Char, String>(95)
        val backward = LinkedHashMap<String, Char>(95)

        var ascii = 32
        for (kanji in KANJI) {
            val ch = ascii.toChar()
            forward[ch] = kanji
            backward[kanji] = ch
            ascii++
        }

        CHAR_TO_KANJI = forward.toMap()
        KANJI_TO_CHAR = backward.toMap()
    }

    @JvmStatic
    fun Coding(input: String?): String? {
        if (input.isNullOrEmpty()) return input ?: ""

        val sb = StringBuilder(input.length * 2)
        val unsupported = LinkedHashSet<Char>()

        for (c in input) {
            val mapped = CHAR_TO_KANJI[c]
            if (mapped != null) {
                sb.append(mapped)
            } else {
                unsupported.add(c)
            }
        }

        if (unsupported.isNotEmpty()) {
            throw IllegalArgumentException(
                "Secure2.Coding: unsupported characters: ${buildSample(unsupported)}"
            )
        }

        return sb.toString()
    }

    @JvmStatic
    fun Uncoding(input: String?): String? {
        if (input.isNullOrEmpty()) return input ?: ""

        val sb = StringBuilder(input.length)
        val unsupported = LinkedHashSet<String>()

        for (ch in input) {
            val mapped = KANJI_TO_CHAR[ch.toString()]
            if (mapped != null) {
                sb.append(mapped)
            } else {
                unsupported.add(ch.toString())
            }
        }

        if (unsupported.isNotEmpty()) {
            throw IllegalArgumentException(
                "Secure2.Uncoding: unsupported symbols: ${buildSample(unsupported)}"
            )
        }

        return sb.toString()
    }

    private fun buildSample(items: Set<*>): String {
        val sample = StringBuilder()
        var count = 0

        for (item in items) {
            if (count++ > 20) {
                sample.append("…")
                break
            }
            if (sample.isNotEmpty()) sample.append(' ')
            sample.append(item.toString())
        }

        return sample.toString()
    }
}
