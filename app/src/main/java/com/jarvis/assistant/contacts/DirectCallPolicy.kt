package com.jarvis.assistant.contacts

import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager

/**
 * Policy gate for opt-in Direct Calling Mode (`Settings.directContactCalls`).
 *
 * Enforces:
 * - Explicit user opt-in in Settings + granted `CALL_PHONE` and `READ_CONTACTS` permissions.
 * - Explicit live spoken command (`spokenRequest` non-blank).
 * - Single high-confidence contact match (`exact`, `words`, or `kin`) — fuzzy/sound-alike matches
 *   still require confirmation.
 * - Ordinary non-emergency, non-USSD phone number (>= 7 digits).
 * - Duplicate-call cooldown (`COOLDOWN_MS`) so accidental double wakes or uncertain failures never redial.
 */
object DirectCallPolicy {
    const val COOLDOWN_MS = 10_000L
    private val HIGH_CONFIDENCE_TIERS = setOf("exact", "words", "kin")
    private val KNOWN_EMERGENCY = setOf("112", "911", "999", "000", "100", "101", "102", "108", "110", "119", "181", "1091")

    fun isEmergencyOrSpecial(number: String, context: Context? = null): Boolean {
        val canonical = ContactMatcher.dialableNumber(number) ?: return true
        val digits = canonical.removePrefix("+")
        if (digits.length < 7 || digits in KNOWN_EMERGENCY) return true
        if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val isEmergency = runCatching {
                context.getSystemService(TelephonyManager::class.java)?.isEmergencyNumber(canonical) == true
            }.getOrDefault(false)
            if (isEmergency) return true
        }
        return false
    }

    fun canPlaceDirectly(
        directEnabled: Boolean,
        hasCallPermission: Boolean,
        spokenRequest: String?,
        match: ContactMatch,
        number: String,
        nowMs: Long,
        lastAttemptMs: Long,
        context: Context? = null,
    ): Boolean {
        if (!directEnabled || !hasCallPermission || spokenRequest.isNullOrBlank()) return false
        if (match.matches.size != 1 || match.how !in HIGH_CONFIDENCE_TIERS) return false
        if (isEmergencyOrSpecial(number, context)) return false
        if (lastAttemptMs != Long.MIN_VALUE && nowMs - lastAttemptMs < COOLDOWN_MS) return false
        return true
    }

    fun canPlaceAfterClarification(
        directEnabled: Boolean,
        hasCallPermission: Boolean,
        spokenRequest: String?,
        number: String,
        nowMs: Long,
        lastAttemptMs: Long,
        context: Context? = null,
    ): Boolean {
        if (!directEnabled || !hasCallPermission || spokenRequest.isNullOrBlank()) return false
        if (isEmergencyOrSpecial(number, context)) return false
        if (lastAttemptMs != Long.MIN_VALUE && nowMs - lastAttemptMs < COOLDOWN_MS) return false
        return true
    }
}
