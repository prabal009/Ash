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
) {
    fun sameWindow(other: WindowContext): Boolean =
        packageName == other.packageName && windowId == other.windowId &&
            bounds == other.bounds && title == other.title
}

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

    /** Active foreground application window, tolerating passive OEM/system overlays (e.g. OnePlus Smart Sidebar, nav pill). */
    fun foreground(service: AccessibilityService, epoch: Long): Foreground? {
        val activeRoot = service.rootInActiveWindow ?: return null
        val windows = runCatching { service.windows }.getOrDefault(emptyList())
        val window = windows.firstOrNull {
            it.id == activeRoot.windowId && it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                (it.isActive || it.isFocused)
        } ?: windows.firstOrNull {
            it.id == activeRoot.windowId && it.type == AccessibilityWindowInfo.TYPE_APPLICATION
        }
        val root = window?.root ?: activeRoot
        val pkg = root.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return null
        if (pkg == service.packageName || SensitiveActions.isProtectedPackage(pkg) || isProtectedNode(root)) return null
        val title = window?.title?.toString()
        if (title != null && SensitiveActions.isProtectedLabel(title)) return null
        val bounds = Rect().also { if (window != null) window.getBoundsInScreen(it) else root.getBoundsInScreen(it) }
        if (bounds.isEmpty) return null
        val windowId = window?.id ?: root.windowId
        if (window != null) {
            for (cover in windows) {
                if (cover.layer <= window.layer || cover.id == window.id) continue
                val coverBounds = Rect().also(cover::getBoundsInScreen)
                if (!Rect.intersects(bounds, coverBounds)) continue
                val coverRoot = cover.root
                val coverPackage = coverRoot?.packageName?.toString()
                if (cover.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                if (cover.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD && !cover.isActive) continue
                // Passive OEM/SystemUI bars, gesture pills, and sidebars are never active or focused.
                if (cover.type == AccessibilityWindowInfo.TYPE_SYSTEM && !cover.isActive && !cover.isFocused) continue
                if (coverPackage != null && SensitiveActions.isProtectedPackage(coverPackage)) return null
                // Block only if another active/focused window actually took focus over the foreground app.
                if (cover.isActive || cover.isFocused) return null
            }
        }
        return Foreground(WindowContext(pkg, windowId, bounds.flattenToString(), title, epoch), root, bounds)
    }

    /** Scan visible UI hierarchy, blocking password/sensitive screens while skipping transient null children gracefully. */
    fun capture(foreground: Foreground, maxElements: Int, render: Boolean): ScreenSnapshot? {
        val context = foreground.context
        val elements = LinkedHashMap<Int, AccessibilityNodeInfo>()
        val stamps = LinkedHashMap<Int, NodeStamp>()
        val out = if (render) StringBuilder("Foreground app: ${context.packageName}\n") else null
        val digest = MessageDigest.getInstance("SHA-256")
        var visited = 0
        var meaningful = false

        fun walk(node: AccessibilityNodeInfo, depth: Int): Boolean {
            if (++visited > MAX_NODES || depth > MAX_DEPTH) return true
            if (!node.isVisibleToUser) return true
            if (isProtectedNode(node)) return false
            val stamp = NodeStamp.of(node)
            val fields = listOf(stamp.text, stamp.description, stamp.hint, stamp.resource, stamp.className)
            // Block editable/credential screens explicitly; for non-editable protected labels, omit that node instead of aborting the whole app.
            if (node.isEditable && fields.any { it != null && SensitiveActions.isProtectedLabel(it) }) return false
            val skipNode = fields.any { it != null && (it.length > MAX_NODE_TEXT || SensitiveActions.isProtectedLabel(it)) }

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

            val interesting = !skipNode && (node.isClickable || node.isCheckable || node.isEditable || node.isScrollable ||
                !stamp.text.isNullOrBlank() || !stamp.description.isNullOrBlank())
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
                        if (node.isClickable || node.isCheckable) out.append(" {click}")
                        if (node.isEditable) out.append(" {edit}")
                        if (node.isScrollable) out.append(" {scroll}")
                        if (node.isChecked) out.append(" {checked}")
                        if (!node.isEnabled) out.append(" {disabled}")
                        out.append('\n')
                    }
                }
            }
            for (i in 0 until stamp.childCount) {
                val child = node.getChild(i) ?: continue
                if (!walk(child, depth + 1)) return false
            }
            return true
        }

        if (!walk(foreground.root, 0) || !meaningful) return null
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
}
