package com.jarvis.assistant.contacts

import com.jarvis.assistant.router.AppMatcher
import com.jarvis.assistant.router.Lexicon

data class PhoneNumber(val number: String, val type: String, val primary: Boolean = false)

data class ContactEntry(val id: Long, val name: String, val numbers: List<PhoneNumber>)

/** [matches] has one entry when the person is clear, several when the name fits more than one contact, none when nobody fits. */
data class ContactMatch(val matches: List<ContactEntry>, val how: String = "") {
    val single: ContactEntry? get() = matches.singleOrNull()
    val ambiguous: Boolean get() = matches.size > 1
}

/** Turns Devanagari names into rough Roman spellings ("राहुल" -> "rahul") so they can be compared with contacts saved in English. */
object Translit {
    private val CONSONANTS = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n", 'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n", 'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "f", 'ब' to "b", 'भ' to "bh", 'म' to "m", 'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v", 'श' to "sh",
        'ष' to "sh", 'स' to "s", 'ह' to "h",
    )
    private val MATRAS = mapOf(
        'ा' to "a", 'ि' to "i", 'ी' to "i", 'ु' to "u", 'ू' to "u", 'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au",
        'ृ' to "ri", 'ॅ' to "e", 'ॉ' to "o", 'ं' to "n", 'ँ' to "n", 'ः' to "h",
    )
    private val VOWELS = mapOf(
        'अ' to "a", 'आ' to "a", 'इ' to "i", 'ई' to "i", 'उ' to "u", 'ऊ' to "u", 'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऋ' to "ri",
    )
    private const val VIRAMA = '्'
    private const val NUKTA = '़'

    fun hasDevanagari(s: String) = s.any { it in '\u0900'..'\u097F' }

    fun toRoman(text: String): String {
        val src = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC).replace("$NUKTA", "")
        val out = StringBuilder()
        var i = 0
        while (i < src.length) {
            val c = src[i]
            val cons = CONSONANTS[c]
            when {
                cons != null -> {
                    out.append(cons)
                    val next = src.getOrNull(i + 1)
                    val endOfWord = next == null || !(next in '\u0900'..'\u097F')
                    val followedByMatraOrVirama = next != null && (MATRAS.containsKey(next) || next == VIRAMA)
                    if (next == VIRAMA) i++ // consonant cluster: no vowel
                    else if (!followedByMatraOrVirama && !endOfWord) out.append('a')
                }
                (c == 'ं' || c == 'ँ') && (src.getOrNull(i + 1)?.let { it !in '\u0900'..'\u097F' } ?: true) -> Unit
                MATRAS.containsKey(c) -> out.append(MATRAS[c])
                VOWELS.containsKey(c) -> out.append(VOWELS[c])
                c == VIRAMA -> Unit
                else -> out.append(c)
            }
            i++
        }
        return out.toString().lowercase()
    }
}

/** Finds the contact a spoken name means. Pure logic, unit-tested. Never guesses between two equally good matches. */
object ContactMatcher {
    /** Words people use for family members; each row is one person, so "mom" finds a contact saved as "Mummy". */
    private val KIN = listOf(
        setOf("mom", "mother", "mummy", "mama", "maa", "ma", "mum", "ammi", "amma", "mommy", "mammi", "mummi", "mumma", "mamma"),
        setOf("dad", "father", "papa", "pappa", "daddy", "abbu", "baba", "pa"),
        setOf("brother", "bhai", "bhaiya", "bro", "bhaijaan"),
        setOf("sister", "didi", "behen", "bhen", "sis", "baji"),
        setOf("wife", "biwi", "patni"),
        setOf("husband", "pati"),
        setOf("grandmother", "nani", "dadi", "granny", "grandma"),
        setOf("grandfather", "nana", "dada", "grandpa"),
    )
    private val TITLES = setOf("mr", "mrs", "ms", "dr", "sir", "madam", "ji", "uncle", "aunty", "auntie", "shri", "the")
    private val CONFIRM_DIAL = Regex("""yes|yeah|yep|yup|sure|ok|okay|confirm|go ahead|open the dialer|haan|han|ha|theek hai|thik hai|हाँ|हां|ठीक है""")

    fun normalizeName(raw: String): String {
        val roman = if (Translit.hasDevanagari(raw)) Translit.toRoman(raw) else raw
        return roman.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }

    private fun tokens(s: String) = normalizeName(s).split(' ').filter { it.isNotEmpty() && it !in TITLES }

    private fun kinGroup(token: String) = KIN.firstOrNull { token in it }

    fun find(query: String, contacts: List<ContactEntry>): ContactMatch {
        val qTokens = tokens(query)
        if (qTokens.isEmpty() || contacts.isEmpty()) return ContactMatch(emptyList())
        val q = qTokens.joinToString(" ")
        val qc = q.replace(" ", "")
        val rows = contacts.map { it to tokens(it.name) }.filter { it.second.isNotEmpty() }

        fun tier(how: String, test: (List<String>, String) -> Boolean): ContactMatch? {
            val hits = rows.filter { (_, t) -> test(t, t.joinToString(" ")) }.map { it.first }
            return if (hits.isEmpty()) null else ContactMatch(hits, how)
        }

        tier("exact") { _, full -> full == q || full.replace(" ", "") == qc }?.let { return it }
        // every word the user said is one of the contact's words ("rahul" finds "Rahul Sharma"; "rahul sharma" needs both)
        tier("words") { t, _ -> qTokens.all { w -> w in t } }?.let { return it }
        tier("kin") { t, _ -> qTokens.size == 1 && kinGroup(qTokens[0])?.let { g -> t.any { it in g } } == true }?.let { return it }
        if (qc.length >= 3) tier("prefix") { t, _ -> qTokens.all { w -> t.any { it.startsWith(w) } } }?.let { return it }
        if (qc.length >= 4) {
            val maxEdits = if (qc.length >= 6) 2 else 1
            tier("fuzzy") { t, full -> t.any { AppMatcher.editDistance(it, qc) <= maxEdits } || AppMatcher.editDistance(full.replace(" ", ""), qc) <= maxEdits }?.let { return it }
            val qs = AppMatcher.skeleton(qc)
            if (qs.length >= 3) tier("sound") { t, _ -> t.any { AppMatcher.skeleton(it) == qs } }?.let { return it }
        }
        return ContactMatch(emptyList())
    }

    /** Which of the [candidates] the user chose by saying [answer], or null if the answer does not point to exactly one. */
    fun pick(answer: String, candidates: List<ContactEntry>): ContactEntry? {
        Lexicon.optionIndex(answer)?.let { return candidates.getOrNull(it) }
        val a = tokens(answer)
        if (a.isEmpty()) return null
        val hits = candidates.filter { c -> val t = tokens(c.name); a.any { w -> w in t || t.any { it.startsWith(w) && w.length >= 3 } } }
        if (hits.size == 1) return hits.single()
        val strict = candidates.filter { c -> val t = tokens(c.name); a.all { w -> w in t } }
        return strict.singleOrNull()
    }

    /** Accept only the number shown to the user; don't silently drop extensions, pause or service-code characters. */
    internal fun dialableNumber(raw: String): String? {
        val number = buildString(raw.length) {
            for (c in raw) {
                val digit = c.digitToIntOrNull()
                when {
                    digit != null -> append(digit)
                    c == '+' && isEmpty() -> append(c)
                    c.isWhitespace() || c in "().-" -> Unit
                    else -> return null
                }
            }
        }
        return number.takeIf { it.length - (if (it.startsWith('+')) 1 else 0) in 3..15 }
    }

    internal fun distinctNumbers(numbers: List<PhoneNumber>): List<PhoneNumber> =
        numbers.distinctBy { dialableNumber(it.number) ?: it.number }

    internal fun pickNumber(answer: String?, numbers: List<PhoneNumber>): PhoneNumber? {
        Lexicon.optionIndex(answer)?.let { return numbers.getOrNull(it) }
        val reply = answer.orEmpty().trim().lowercase().removePrefix("the ").removeSuffix(" number")
        val number = dialableNumber(reply)
        return numbers.singleOrNull { it.type == reply || (number != null && dialableNumber(it.number) == number) }
    }

    internal fun confirmsDial(answer: String?): Boolean =
        CONFIRM_DIAL.matches(Lexicon.normalize(answer.orEmpty()))
}
