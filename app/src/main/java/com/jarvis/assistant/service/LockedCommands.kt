package com.jarvis.assistant.service

import com.jarvis.assistant.router.FastCommand
import com.jarvis.assistant.router.QueryKind

/** Same gate at intake and at the executor's final authorization check. */
internal object LockedCommands {
    fun allows(command: FastCommand?): Boolean = when (command) {
        is FastCommand.Volume -> true
        is FastCommand.Ask -> command.kind == QueryKind.TIME
        is FastCommand.Media -> command.app == null
        else -> false
    }
}

/** Main-thread, memory-only. Store only the parsed safe operation, never a transcript or approval. */
internal class PendingUnlock(private val now: () -> Long) {
    private var command: FastCommand? = null
    private var deadline = 0L

    fun offer(value: FastCommand?): Boolean {
        clear()
        if (value !is FastCommand.SetTimer && (value !is FastCommand.Ask || value.kind == QueryKind.TIME)) return false
        command = value
        deadline = now() + 30_000L
        return true
    }

    fun take(unlocked: Boolean): FastCommand? {
        val value = command?.takeIf { unlocked && now() < deadline }
        clear()
        return value
    }

    fun clear() { command = null }
}
