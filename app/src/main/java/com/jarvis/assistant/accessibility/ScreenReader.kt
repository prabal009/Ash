package com.jarvis.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.jarvis.assistant.agent.SensitiveActions
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

internal data class WindowContext(
    val packageName: String,
    val windowId: Int,
    val bounds: String,
    val title: String?,
    val epoch: Long,
)

internal data class ScreenContext(val window: WindowContext, val fingerprint: String)

/** A numbered observation. IDs refer to nodes in this exact observation, not the current screen in general. */
class ScreenSnapshot internal constructor(
    val text: String,
    val elements: Map<Int, AccessibilityNodeInfo> = emptyMap(),
    internal val context: ScreenContext? = null,
    internal val stamps: Map<Int, NodeStamp> = emptyMap(),
) {
    val foregroundPackage: String? get() = context?.window?.packageName
}

internal data class NodeStamp(
    val bounds: String,
    val className: String?,
    val resource: String?,
    val text: String?,
    val description: String?,
    val hint: String?,
    val inputType: Int,
    val actions: List<Int>,
    val state: Int,
    val childCount: Int,
) {
    companion object {
        fun of(node: AccessibilityNodeInfo) = NodeStamp(
            Rect().also(node::getBoundsInScreen).flattenToString(),
            node.className?.toString(), node.viewIdResourceName,
            node.text?.toString(), node.contentDescription?.toString(), node.hintText?.toString(),
            node.inputType, node.actionList.map { it.id },
            (if (node.isEnabled) 1 else 0) or (if (node.isClickable) 2 else 0) or
                (if (node.isEditable) 4 else 0) or (if (node.isScrollable) 8 else 0) or
                (if (node.isCheckable) 16 else 0) or (if (node.isChecked) 32 else 0) or
                (if (node.isSelected) 64 else 0) or (if (node.isFocused) 128 else 0),
            node.childCount,
        )
    }
}

internal object ScreenReader {
    private const val MAX_NODES = 1_200
    private const val MAX_DEPTH = 60
    private const val MAX_NODE_TEXT = 4_096
    private const val MAX_LIST_TEXT = 100

    data class Foreground(val context: WindowContext, val root: AccessibilityNodeInfo, val bounds: Rect)

    /** Only the active app, never a fallback root, keyboard, system dialog, or other overlapping window. */
    fun foreground(service: AccessibilityService, epoch: Long): Foreground? {
        val activeRoot = service.rootInActiveWindow ?: return null
        val windows = service.windows
        val window = windows.singleOrNull {
            it.id == activeRoot.windowId && it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                (it.isActive || it.isFocused)
        } ?: return null
        val root = window.root ?: return null
        val pkg = root.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return null
        if (root != activeRoot || root.windowId != window.id || pkg == service.packageName ||
            SensitiveActions.isProtectedPackage(pkg) || isProtectedNode(root)) return null
        val title = window.title?.toString()
        if (title != null && SensitiveActions.isProtectedLabel(title)) return null
        val bounds = Rect().also(window::getBoundsInScreen)
        if (bounds.isEmpty) return null
        for (cover in windows) {
            if (cover.layer <= window.layer || cover.id == window.id) continue
            val coverBounds = Rect().also(cover::getBoundsInScreen)
            if (!Rect.intersects(bounds, coverBounds)) continue
            val coverRoot = cover.root
            val coverPackage = coverRoot?.packageName?.toString()
            if (cover.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && coverPackage == service.packageName) continue
            // We never read or target the keyboard. Android still reports the app as the active root.
            if (cover.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD && !cover.isActive) continue
            if (cover.type == AccessibilityWindowInfo.TYPE_SYSTEM && !cover.isActive && !cover.isFocused &&
                coverPackage == "com.android.systemui" &&
                coverRoot?.viewIdResourceName?.substringAfter(":id/") in systemBars) continue
            return null
        }
        return Foreground(WindowContext(pkg, window.id, bounds.flattenToString(), title, epoch), root, bounds)
    }

    /** Scan the entire visible tree for protected/opaque UI before exposing even a prefix of it. */
    fun capture(foreground: Foreground, maxElements: Int, render: Boolean): ScreenSnapshot? {
        val context = foreground.context
        val elements = LinkedHashMap<Int, AccessibilityNodeInfo>()
        val stamps = LinkedHashMap<Int, NodeStamp>()
        val out = if (render) StringBuilder("Foreground app: ${context.packageName}\n") else null
        val digest = MessageDigest.getInstance("SHA-256")
        var visited = 0
        var meaningful = false

        fun walk(node: AccessibilityNodeInfo, depth: Int): Boolean {
            if (++visited > MAX_NODES || depth > MAX_DEPTH) return false
            if (!node.isVisibleToUser) return true
            if (node.windowId != context.windowId || node.packageName?.toString() != context.packageName ||
                isProtectedNode(node)) return false
            val stamp = NodeStamp.of(node)
            val fields = listOf(stamp.text, stamp.description, stamp.hint, stamp.resource, stamp.className)
            if (fields.any { it != null && (it.length > MAX_NODE_TEXT || SensitiveActions.isProtectedLabel(it)) }) return false
            if (stamp.className == "android.webkit.WebView" && stamp.childCount == 0) return false

            // Length-prefix every field, preserving values and boundaries even for untrusted strings.
            fun add(value: String?) {
                val bytes = value?.toByteArray(Charsets.UTF_8)
                val length = bytes?.size ?: -1
                for (shift in 24 downTo 0 step 8) digest.update((length ushr shift).toByte())
                if (bytes != null) digest.update(bytes)
            }
            add(depth.toString())
            for (field in fields) add(field)
            add(stamp.bounds)
            add(stamp.inputType.toString())
            add(stamp.actions.joinToString(","))
            add(stamp.state.toString())
            add(stamp.childCount.toString())

            val interesting = node.isClickable || node.isEditable || node.isScrollable ||
                !stamp.text.isNullOrBlank() || !stamp.description.isNullOrBlank()
            if (interesting) {
                meaningful = true
                if (elements.size < maxElements) {
                    val id = elements.size + 1
                    elements[id] = node
                    stamps[id] = stamp
                    if (out != null) {
                        out.append('[').append(id).append("] ")
                            .append(stamp.className?.substringAfterLast('.') ?: "View")
                        if (!node.isEditable) appendData(out, "text", stamp.text)
                        appendData(out, "description", stamp.description)
                        appendData(out, "hint", stamp.hint)
                        if (node.isClickable) out.append(" {click}")
                        if (node.isEditable) out.append(" {edit}")
                        if (node.isScrollable) out.append(" {scroll}")
                        if (!node.isEnabled) out.append(" {disabled}")
                        out.append('\n')
                    }
                }
            }
            for (i in 0 until stamp.childCount) {
                val child = node.getChild(i) ?: return false
                if (!walk(child, depth + 1)) return false
            }
            return true
        }

        if (!foreground.root.isVisibleToUser || !walk(foreground.root, 0) || !meaningful) return null
        return ScreenSnapshot(out?.toString().orEmpty(), elements,
            ScreenContext(context, Base64.getEncoder().encodeToString(digest.digest())), stamps)
    }

    fun isProtectedNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword || (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive)) return true
        val type = node.inputType
        val variation = type and android.text.InputType.TYPE_MASK_VARIATION
        return when (type and android.text.InputType.TYPE_MASK_CLASS) {
            android.text.InputType.TYPE_CLASS_TEXT -> variation in textPasswordTypes
            android.text.InputType.TYPE_CLASS_NUMBER -> variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private fun appendData(out: StringBuilder, label: String, value: String?) {
        if (!value.isNullOrBlank()) out.append(' ').append(label).append('=')
            .append(JSONObject.quote(value.take(MAX_LIST_TEXT)))
    }

    private val textPasswordTypes = setOf(
        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
        android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
    )
    private val systemBars = setOf("status_bar", "navigation_bar_frame", "navigation_bar_view")
}
