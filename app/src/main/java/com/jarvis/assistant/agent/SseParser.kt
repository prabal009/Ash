package com.jarvis.assistant.agent

/** Reads one line of a streamed chat response ("data: {...}"). Pure, so it is unit-tested. */
object SseParser {
    sealed interface Event {
        data class Text(val text: String) : Event
        data object Done : Event
        data object Other : Event
    }

    fun parseLine(line: String): Event {
        if (!line.startsWith("data:")) return Event.Other
        val payload = line.removePrefix("data:").trim()
        if (payload == "[DONE]") return Event.Done
        val deltaAt = payload.indexOf("\"delta\"")
        if (deltaAt < 0) return Event.Other
        val brace = payload.indexOf('{', deltaAt + 7)
        if (brace < 0) return Event.Other
        return readDeltaContent(payload, brace + 1)
    }

    /** Walks top-level fields of the `"delta"` object so `"reasoning"` text containing `"content"` never confuses parsing. */
    private fun readDeltaContent(s: String, start: Int): Event {
        var i = start
        while (i < s.length) {
            i = skipWs(s, i)
            if (i >= s.length || s[i] == '}') return Event.Other
            if (s[i] == ',') { i++; continue }
            if (s[i] != '"') return Event.Other
            val key = readJsonStringWithEnd(s, i + 1) ?: return Event.Other
            i = skipWs(s, key.second)
            if (i >= s.length || s[i] != ':') return Event.Other
            i = skipWs(s, i + 1)
            if (i >= s.length) return Event.Other
            if (key.first == "content") {
                if (s[i] != '"') return Event.Other // null or non-string
                val value = readJsonStringWithEnd(s, i + 1)?.first ?: return Event.Other
                return if (value.isNotEmpty()) Event.Text(value) else Event.Other
            }
            i = skipJsonValue(s, i)
            if (i < 0) return Event.Other
        }
        return Event.Other
    }

    private fun skipWs(s: String, start: Int): Int {
        var i = start
        while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\r' || s[i] == '\n')) i++
        return i
    }

    private fun skipJsonValue(s: String, start: Int): Int {
        if (start >= s.length) return -1
        return when (s[start]) {
            '"' -> readJsonStringWithEnd(s, start + 1)?.second ?: -1
            '{', '[' -> {
                var depth = 0
                var i = start
                while (i < s.length) {
                    when (s[i]) {
                        '"' -> i = readJsonStringWithEnd(s, i + 1)?.second ?: return -1
                        '{', '[' -> { depth++; i++ }
                        '}', ']' -> { depth--; i++; if (depth == 0) return i }
                        else -> i++
                    }
                }
                -1
            }
            else -> {
                var i = start
                while (i < s.length && s[i] != ',' && s[i] != '}' && s[i] != ']') i++
                i
            }
        }
    }

    /** Reads a JSON string body starting right after the opening quote; returns (text, index after closing quote). */
    private fun readJsonStringWithEnd(s: String, start: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var i = start
        while (i < s.length) {
            when (val c = s[i]) {
                '"' -> return sb.toString() to (i + 1)
                '\\' -> {
                    i++
                    if (i >= s.length) return null
                    when (val e = s[i]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C'); '/' -> sb.append('/'); '\\' -> sb.append('\\'); '"' -> sb.append('"')
                        'u' -> {
                            if (i + 4 >= s.length) return null
                            sb.append(s.substring(i + 1, i + 5).toIntOrNull(16)?.toChar() ?: return null)
                            i += 4
                        }
                        else -> sb.append(e)
                    }
                }
                else -> sb.append(c)
            }
            i++
        }
        return null
    }
}
