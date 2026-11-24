package it.mdv.dttchannels

import java.text.Normalizer

/**
 * Finds the channel meant by a spoken phrase: "Metti Rai2", "metti rai due", "metti il canale 5", "guarda la sette",
 * "metti il 21", "Rai Radio 3 per favore".
 *
 * Phrase and channel names get the same normalisation (lowercase, no accents, letters split from digits, Italian
 * number words as digits, "HD" dropped), so "Canale5 HD" and "canale cinque" are both `canale 5`. Command words need
 * no list: word spans of the phrase are tried longest first, so "metti", "il", "per favore" simply fall away.
 * For each span length, in order:
 *  1. a bare number is a channel number (LCN): "metti il 7" is La7, not 7 Gold;
 *  2. name equal to the span or starting with it, lowest LCN first: "rai 3" -> 3 "Rai 3 TGR Marche", not the
 *     other regions' "Rai 3" at 8xx;
 *  3. name containing it ("movie" -> "Rai Movie");
 *  4. same letters ignoring spaces ("di max" -> "DMAX", "etv marche" -> "e'TV MARCHE").
 * Ties go to the lowest LCN (TV before radio). Last resort: one or two letters off, never different digits.
 */
object VoiceMatch {
    /** Channel names tokenised once per channel list (≈300 names): a search then only tokenises the phrase. */
    private class Index(val channels: List<Channel>) {
        val byLcn = channels.sortedWith(compareBy({ it.lcnInt }, { it.lcn }))
        val names = byLcn.map { it to tokens(it.name) }.filter { it.second.isNotEmpty() }
        val words: Set<String> = names.flatMapTo(HashSet()) { it.second }
    }

    @Volatile private var index: Index? = null

    private fun index(channels: List<Channel>): Index =
        index?.takeIf { it.channels === channels } ?: Index(channels).also { index = it }

    /** Drops the index (≈100 KB) when the app is trimmed in the background. */
    fun release() { index = null }

    fun find(phrase: String, channels: List<Channel>): Channel? {
        if (channels.isEmpty()) return null
        val ix = index(channels)
        val byLcn = ix.byLcn
        val names = ix.names
        val q = unglue(tokens(phrase), ix.words)
        if (q.isEmpty()) return null

        for (len in q.size downTo 1) {
            val spans = (0..q.size - len).map { q.subList(it, it + len) }
                .filter { len > 1 || it[0].isNumber() || it[0].length >= 3 }   // "la", "il", "tv" alone mean nothing
            spans.firstOrNull { len == 1 && it[0].isNumber() }?.let { s ->
                byLcn.firstOrNull { it.lcn.trimStart('0') == s[0] }?.let { return it }
            }
            for (tier in 1..3) for (s in spans) {
                val hit = names.firstOrNull { (_, n) ->
                    when (tier) {
                        1 -> n.size >= s.size && n.subList(0, s.size) == s
                        2 -> n.size > s.size && (1..n.size - s.size).any { n.subList(it, it + s.size) == s }
                        else -> s.joinToString("").let { c -> c.length >= 4 && n.joinToString("").let { it == c || it.startsWith(c) } }
                    }
                }
                if (hit != null) return hit.first
            }
        }
        // speech-to-text spelling slips ("real taim", "dimax"): small edit distance on spans of 4+ letters
        for (len in q.size downTo 1) for (i in 0..q.size - len) {
            val s = q.subList(i, i + len)
            val c = s.joinToString("")
            if (c.count { it.isLetter() } < 4) continue
            val digits = s.filter { it.isNumber() }
            val best = names.filter { (_, n) -> n.filter { it.isNumber() } == digits }
                .map { (ch, n) -> ch to distance(c, n.joinToString("")) }
                .filter { (_, d) -> d <= if (c.length <= 6) 1 else 2 }
                .minByOrNull { it.second }
            if (best != null) return best.first
        }
        return null
    }

    private fun String.isNumber() = isNotEmpty() && all { it.isDigit() }

    /**
     * The recogniser sometimes glues a number word to the name: "Raiuno", "italiauno", "retequattro". Split such a
     * word when its head is a word of some channel name and its tail a number word ("prodotto" stays whole).
     */
    private fun unglue(q: List<String>, nameWords: Set<String>): List<String> = q.flatMap { w ->
        if (w.isNumber() || w in nameWords) return@flatMap listOf(w)
        (2 until w.length).firstNotNullOfOrNull { i ->
            val head = w.substring(0, i)
            val n = number(w.substring(i))
            if (n != null && head in nameWords) listOf(head, n.toString()) else null
        } ?: listOf(w)
    }

    /** "Metti Rai2 HD!" -> [metti, rai, 2]; "settecentodue" -> [702]. */
    private val MARKS = Regex("\\p{M}+")
    private val SPLIT = Regex("[^a-z0-9]+|(?<=[a-z])(?=[0-9])|(?<=[0-9])(?=[a-z])")

    fun tokens(text: String): List<String> {
        val low = text.lowercase()
        val plain = if (low.all { it.code < 128 }) low else Normalizer.normalize(low, Normalizer.Form.NFD).replace(MARKS, "")
        return plain.split(SPLIT)
            .filter { it.isNotEmpty() && it != "hd" }
            .map { number(it)?.toString() ?: it }
    }

    /** 0..9999 in words: the table below plus "mille"/"duemila"... in front ("duemila", "milleottocento"). */
    private fun number(w: String): Int? {
        NUMBERS[w]?.let { return it }
        val (k, rest) = when {
            w.startsWith("mille") -> 1 to w.removePrefix("mille")
            w.contains("mila") -> (NUMBERS[w.substringBefore("mila")]?.takeIf { it in 2..9 } ?: return null) to w.substringAfter("mila")
            else -> return null
        }
        val r = if (rest.isEmpty()) 0 else NUMBERS[rest] ?: return null
        return k * 1000 + r
    }

    /** Italian number words 0..999 ("ventuno", "ventotto", "centotto", "settecentouno"), plus "un"/"una". */
    private val NUMBERS: Map<String, Int> = HashMap<String, Int>().apply {
        val units = listOf("zero", "uno", "due", "tre", "quattro", "cinque", "sei", "sette", "otto", "nove")
        val teens = listOf("dieci", "undici", "dodici", "tredici", "quattordici", "quindici", "sedici", "diciassette", "diciotto", "diciannove")
        val tens = listOf("", "", "venti", "trenta", "quaranta", "cinquanta", "sessanta", "settanta", "ottanta", "novanta")
        fun below100(n: Int): List<String> = when {
            n < 10 -> listOf(units[n])
            n < 20 -> listOf(teens[n - 10])
            n % 10 == 0 -> listOf(tens[n / 10])
            n % 10 == 1 || n % 10 == 8 -> listOf(tens[n / 10].dropLast(1) + units[n % 10], tens[n / 10] + units[n % 10])
            else -> listOf(tens[n / 10] + units[n % 10])
        }
        for (n in 0..99) below100(n).forEach { put(it, n) }
        for (h in 1..9) {
            val head = if (h == 1) "cento" else units[h] + "cento"
            put(head, h * 100)
            for (r in 1..99) below100(r).forEach { w ->
                put(head + w, h * 100 + r)
                if (w.startsWith("o")) put(head.dropLast(1) + w, h * 100 + r)   // centotto
            }
        }
        put("un", 1); put("una", 1)
    }

    private fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }
}
