package com.jarvis.assistant.agent

/** Conservative policy for the small set of UI affordances the assistant can identify. */
object SensitiveActions {
    private val sensitive = Rx(
        """\b(send|call|dial|post|tweet|publish|share|reply|pay|transfer|place order|buy|purchase|checkout|delete|remove|submit|accept|agree|authorize|confirm|grant|allow|save|install)\b""",
    )
    private val protected = Rx(
        """\b(password|passwd|passcode|passkey|otp|pin|cvv|cvc|2fa|mfa|authentication|biometric(?:s)?|credential(?:s)?|(?:one[ -]time|security|verification|backup|recovery) (?:passcode|code|password)|sign[ -]?in|log[ -]?in|unlock|verify (?:your )?identity|payment|checkout|(?:credit|debit|bank) card|card number|billing address|wire transfer)\b""",
    )
    private val protectedPackage = Rx(
        """(?:^|[._])(?:bank(?:ing)?|wallet|payment(?:s)?|paytm|phonepe|paisa|authenticator|credential(?:s|manager)?|permissioncontroller|packageinstaller)(?:$|[._])""",
    )
    private val navigation = setOf(
        "back", "navigate up", "close search", "open search", "search", "show more", "show less", "expand", "collapse",
        "next page", "previous page", "home", "library", "inbox", "messages", "conversations", "contacts", "all apps",
        "compose", "new message", "new chat",
    )
    private val editable = setOf(
        "search", "search query", "search text", "search this page", "find in page", "type a message",
        "write a message", "message", "new message", "text message", "compose message", "subject", "title", "note",
    )
    private val yes = setOf(
        "yes", "yeah", "yep", "yup", "yes please", "yeah go ahead", "sure", "ok", "okay", "confirm", "go ahead",
        "do it", "send it", "ok send it", "haan", "han", "ha", "theek hai", "thik hai", "kar do", "bhej do",
        "हाँ", "हां", "ठीक है", "कर दो", "भेज दो", "हां कर दो",
    )

    fun needsConfirmation(label: String): Boolean = sensitive.containsMatchIn(normalize(label))
    fun isProtectedLabel(label: String): Boolean = protected.containsMatchIn(normalize(label))
    fun isProtectedPackage(packageName: String): Boolean = protectedPackage.containsMatchIn(packageName)
    fun isNavigationLabel(label: String): Boolean = normalize(label) in navigation
    fun isEditableLabel(label: String): Boolean = normalize(label) in editable
    fun isSearchLabel(label: String): Boolean = normalize(label) in setOf("search", "search query", "search text")

    /** Match a complete answer, not an affirmative word embedded in another utterance. */
    fun isYes(answer: String?): Boolean =
        answer?.trim()?.trimEnd('.', '!', '।')?.lowercase(java.util.Locale.ROOT) in yes

    /** Text quoted in a spoken review must be short and unambiguous, not truncated or invisibly formatted. */
    fun canQuote(text: String, limit: Int): Boolean = text.isNotBlank() && text.length <= limit &&
        text.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() ||
            Character.getType(it) == Character.LINE_SEPARATOR.toInt() || Character.getType(it) == Character.PARAGRAPH_SEPARATOR.toInt() }

    private fun normalize(label: String): String =
        java.text.Normalizer.normalize(label, java.text.Normalizer.Form.NFKC)
            .replace(camelBoundary, " ").replace('_', ' ').lowercase(java.util.Locale.ROOT).trim()

    private fun Rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)
    private val camelBoundary = Regex("(?<=[a-z])(?=[A-Z])")
}

/** The grant is local to the synchronous step that asked the question, never model state. */
internal class OneUseApproval<T : Any>(
    private val binding: T,
    private val askedAt: Long,
) {
    private var used = false

    fun consume(current: T, answer: String?, now: Long, cancelled: Boolean): Boolean {
        if (used) return false
        used = true
        return !cancelled && now >= askedAt && now - askedAt < EXPIRY_MS &&
            current == binding && SensitiveActions.isYes(answer)
    }

    private companion object {
        const val EXPIRY_MS = 10_000L
    }
}
