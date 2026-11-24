package com.jarvis.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.app.SearchManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import androidx.annotation.RequiresApi
import com.jarvis.assistant.agent.OneUseApproval
import com.jarvis.assistant.agent.SensitiveActions
import com.jarvis.assistant.apps.YouTubeAction
import com.jarvis.assistant.router.AppEntry
import com.jarvis.assistant.router.AppMatcher
import com.jarvis.assistant.router.FastCommand
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal enum class Direction { UP, DOWN, LEFT, RIGHT }
internal enum class NavigationKey { BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS }

internal sealed interface ScreenAction {
    data class Tap(val id: Int) : ScreenAction
    data class Edit(val id: Int, val text: String) : ScreenAction
    data class Search(val buttonId: Int, val fieldId: Int) : ScreenAction
    data class Scroll(val id: Int?, val direction: Direction, val page: Boolean = false) : ScreenAction
    data class Read(val id: Int) : ScreenAction
    data class Navigate(val key: NavigationKey) : ScreenAction
    data class Open(val name: String) : ScreenAction
}

internal class ManualScreenStep(message: String) : IllegalStateException(message)

internal data class ScreenEffect(val summary: String, val verified: Boolean = false)

/** Accessibility is optional; connecting it does not start or keep the voice service alive. */
class JarvisAccessibilityService : AccessibilityService() {

    @Volatile private var snapshot: ScreenSnapshot? = null
    private val windowEpoch = AtomicLong()

    /** The task island shown at the top of the screen while Jarvis works. */
    val island by lazy { com.jarvis.assistant.ui.TaskIsland(this) }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName?.toString() == packageName) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) windowEpoch.incrementAndGet()
    }

    override fun onInterrupt() {
        windowEpoch.incrementAndGet()
    }

    override fun onDestroy() {
        snapshot = null
        windowEpoch.incrementAndGet()
        if (instance === this) instance = null
        runCatching { island.hide() }
        super.onDestroy()
    }

    internal fun observe(maxElements: Int, cancelled: () -> Boolean): ScreenSnapshot {
        if (Build.VERSION.SDK_INT < 34) {
            throw ManualScreenStep("Phone control needs Android 14 or later to check for protected windows.")
        }
        return verifiedSnapshot(maxElements, true, cancelled)
    }

    fun readScreen(maxElements: Int = 300): ScreenSnapshot =
        (try { observe(maxElements) { Thread.currentThread().isInterrupted } }
        catch (e: ManualScreenStep) { ScreenSnapshot(e.message.orEmpty()) })
            .also { snapshot = it }

    private fun unlocked(): Boolean {
        if (instance !== this || getSystemService(PowerManager::class.java)?.isInteractive != true) return false
        val keyguard = getSystemService(KeyguardManager::class.java) ?: return false
        return !keyguard.isKeyguardLocked && !keyguard.isDeviceLocked
    }

    private fun foreground(): ScreenReader.Foreground {
        if (!unlocked()) throw ManualScreenStep("Unlock your phone before using phone control.")
        return ScreenReader.foreground(this, windowEpoch.get()) ?: throw ManualScreenStep(UNAVAILABLE)
    }

    private fun sameForeground(expected: WindowContext): ScreenReader.Foreground =
        foreground().also { if (!it.context.sameWindow(expected)) throw ManualScreenStep(CHANGED) }

    private fun verifiedSnapshot(maxElements: Int, render: Boolean, cancelled: () -> Boolean): ScreenSnapshot {
        checkRunning(cancelled)
        if (Build.VERSION.SDK_INT < 34 || Looper.myLooper() == Looper.getMainLooper()) throw ManualScreenStep(UNAVAILABLE)
        val before = foreground().context
        if (!canCaptureWindow(before, cancelled)) throw ManualScreenStep(UNAVAILABLE)
        val current = sameForeground(before)
        val result = ScreenReader.capture(current, maxElements, render) ?: throw ManualScreenStep(UNAVAILABLE)
        sameForeground(before)
        checkRunning(cancelled)
        return result
    }

    /**
     * There is no public secure flag in AccessibilityWindowInfo. Android 14's per-window API
     * fails on a secure window. Use it only as a local security check; discard every buffer.
     */
    @RequiresApi(34)
    private fun canCaptureWindow(window: WindowContext, cancelled: () -> Boolean): Boolean {
        repeat(3) { attempt ->
            checkRunning(cancelled)
            sameForeground(window)
            val done = CountDownLatch(1)
            var result = ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR
            takeScreenshotOfWindow(window.windowId, { it.run() }, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    screenshot.hardwareBuffer.close()
                    result = CAPTURE_ALLOWED
                    done.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    result = errorCode
                    done.countDown()
                }
            })
            val deadline = SystemClock.elapsedRealtime() + 1_500
            while (!done.await(50, TimeUnit.MILLISECONDS)) {
                checkRunning(cancelled)
                if (SystemClock.elapsedRealtime() >= deadline) {
                    Log.w(TAG, "Window capture timed out for ${window.packageName}#${window.windowId}")
                    return false
                }
            }
            if (result == CAPTURE_ALLOWED) return true
            if (result == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) {
                Log.w(TAG, "Blocked FLAG_SECURE window ${window.packageName}#${window.windowId}")
                return false
            }
            if (result != ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT || attempt == 2) {
                Log.w(TAG, "Window capture failed (code=$result) for ${window.packageName}#${window.windowId}")
                return false
            }
            // The platform enforces a per-window screenshot interval (333 ms+); pause briefly and retry.
            val waitEnd = SystemClock.elapsedRealtime() + 400L * (attempt + 1)
            while (SystemClock.elapsedRealtime() < waitEnd) {
                checkRunning(cancelled)
                Thread.sleep(50)
            }
        }
        return false
    }

    private data class Plan(
        val request: ScreenAction,
        val id: Int? = null,
        val otherId: Int? = null,
        val action: Int? = null,
        val target: String = "",
        val payload: String = "",
        val app: AppEntry? = null,
        val needsConfirm: Boolean = false,
        val targetStamp: NodeStamp? = null,
        val otherStamp: NodeStamp? = null,
    )

    private data class ApprovalBinding(
        val turn: Any,
        val window: WindowContext,
        val action: Int?,
        val target: String,
        val payload: String,
        val app: AppEntry?,
        val needsConfirm: Boolean,
        val targetStamp: NodeStamp?,
        val otherStamp: NodeStamp?,
    ) {
        companion object {
            fun of(turn: Any, window: WindowContext, plan: Plan) = ApprovalBinding(
                turn = turn,
                window = window.copy(epoch = 0L),
                action = plan.action,
                target = plan.target,
                payload = plan.payload,
                app = plan.app,
                needsConfirm = plan.needsConfirm,
                targetStamp = plan.targetStamp,
                otherStamp = plan.otherStamp,
            )
        }
    }

    internal fun execute(
        screen: ScreenSnapshot,
        maxElements: Int,
        request: ScreenAction,
        turn: Any,
        cancelled: () -> Boolean,
        ask: (String) -> String?,
        dispatching: (irreversible: Boolean) -> Unit,
    ): ScreenEffect = perform(screen, maxElements, request, turn, cancelled, ask, dispatching)

    private fun perform(
        screen: ScreenSnapshot,
        maxElements: Int,
        request: ScreenAction,
        turn: Any,
        cancelled: () -> Boolean,
        ask: (String) -> String?,
        dispatching: (irreversible: Boolean) -> Unit,
        explicitEdit: Boolean = false,
    ): ScreenEffect {
        val context = screen.context ?: throw ManualScreenStep(UNAVAILABLE)
        fun qualify(): Pair<ScreenSnapshot, Plan> {
            val live = verifiedSnapshot(maxElements, false, cancelled)
            val liveCtx = live.context ?: throw ManualScreenStep(CHANGED)
            if (!liveCtx.window.sameWindow(context.window)) throw ManualScreenStep(CHANGED)
            fun resolveId(origId: Int): Int {
                val origStamp = screen.stamps[origId] ?: throw ManualScreenStep(CHANGED)
                if (live.stamps[origId] == origStamp) return origId
                return live.stamps.entries.singleOrNull { it.value == origStamp }?.key
                    ?: throw ManualScreenStep(CHANGED)
            }
            val remapped = when (request) {
                is ScreenAction.Tap -> ScreenAction.Tap(resolveId(request.id))
                is ScreenAction.Edit -> ScreenAction.Edit(resolveId(request.id), request.text)
                is ScreenAction.Search -> ScreenAction.Search(resolveId(request.buttonId), resolveId(request.fieldId))
                is ScreenAction.Read -> ScreenAction.Read(resolveId(request.id))
                is ScreenAction.Scroll -> ScreenAction.Scroll(request.id?.let(::resolveId), request.direction, request.page)
                is ScreenAction.Navigate, is ScreenAction.Open -> request
            }
            val plan = plan(live, remapped)
            for (id in listOfNotNull(plan.id, plan.otherId)) {
                if (live.elements[id] == null || live.stamps[id] == null) throw ManualScreenStep(CHANGED)
            }
            return live to plan
        }

        var (live, plan) = qualify()
        val app = context.window.packageName
        val question = when (request) {
            is ScreenAction.Edit -> if (explicitEdit) null else "Replace ${plan.target} in $app with “${plan.payload}”? Say yes to type it."
            is ScreenAction.Search -> "Submit the search “${plan.payload}” in $app? Say yes to search."
            is ScreenAction.Read -> "Read the selected on-screen text from $app aloud? Say yes to read it."
            is ScreenAction.Tap -> if (plan.needsConfirm) "Tap “${plan.target}” in $app? Say yes to confirm." else null
            else -> null
        }
        val approval = question?.let {
            OneUseApproval(ApprovalBinding.of(turn, context.window, plan), SystemClock.elapsedRealtime())
        }
        val answer = if (question != null) {
            checkRunning(cancelled)
            ask(question)
        } else null
        checkRunning(cancelled)
        if (approval != null) {
            if (!SensitiveActions.isYes(answer)) throw ManualScreenStep("I didn’t get a clear yes, so I didn’t do that.")
            val qualified = qualify()
            live = qualified.first
            plan = qualified.second
        }

        sameForeground(context.window)
        val node = plan.id?.let { refreshTarget(live, it) }
        plan.otherId?.let { refreshTarget(live, it) }
        val fg = sameForeground(context.window)
        if (approval != null &&
            !approval.consume(ApprovalBinding.of(turn, context.window, plan), answer, SystemClock.elapsedRealtime(), cancelled())) {
            throw ManualScreenStep("That approval expired or the action changed. I stopped without doing it.")
        }
        checkRunning(cancelled)
        if (request is ScreenAction.Read) return ScreenEffect("The selected text says: “${plan.payload}”", verified = true)

        // Once dispatch is possible, a provider error must not replay the step through a fallback.
        dispatching(request is ScreenAction.Search || (request is ScreenAction.Tap && plan.needsConfirm))
        checkRunning(cancelled)
        val accepted = when (request) {
            is ScreenAction.Open -> startLauncher(plan.app ?: throw ManualScreenStep(UNAVAILABLE))
            is ScreenAction.Navigate -> performGlobalAction(plan.action ?: throw ManualScreenStep(UNAVAILABLE))
            is ScreenAction.Edit -> {
                val field = checkNotNull(node)
                if (!field.isFocused) {
                    field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    field.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, plan.payload) }
                field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
            is ScreenAction.Tap -> {
                val targetNode = checkNotNull(node)
                val clickNode = findClickableAncestor(targetNode)
                val clicked = clickNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                clicked || dispatchTapGesture(targetNode, fg.bounds, cancelled)
            }
            is ScreenAction.Scroll -> {
                val scrolled = if (node != null && plan.action != null) node.performAction(plan.action) else false
                scrolled || dispatchSwipeGesture(
                    node?.let { Rect().also(it::getBoundsInScreen) }?.takeUnless { it.isEmpty } ?: fg.bounds,
                    fg.bounds,
                    request.direction,
                    cancelled,
                )
            }
            else -> checkNotNull(node).performAction(checkNotNull(plan.action))
        }
        if (!accepted) throw ManualScreenStep("Android didn’t confirm that action. I stopped rather than trying it again.")
        return when (request) {
            is ScreenAction.Open -> {
                val target = checkNotNull(plan.app)
                if (waitForForeground(target.packageName, 2_500, cancelled)) ScreenEffect("Opened ${target.label}.", true)
                else ScreenEffect("Asked Android to open the app, but couldn’t confirm it opened.")
            }
            is ScreenAction.Edit -> {
                val field = checkNotNull(node)
                val verified = sameForeground(context.window).context.sameWindow(context.window) && field.refresh() &&
                    field.isVisibleToUser && !ScreenReader.isProtectedNode(field) && field.text?.toString() == plan.payload
                if (verified) ScreenEffect("Typed the text in the selected field. I didn’t submit it.", true)
                else ScreenEffect("Requested text entry, but couldn’t verify the field afterwards.")
            }
            is ScreenAction.Search -> ScreenEffect("Requested the search. I haven’t verified its results.")
            is ScreenAction.Tap -> ScreenEffect("Tapped ${plan.target}.", verified = true)
            is ScreenAction.Scroll -> ScreenEffect(
                if (request.page) "Swiped ${request.direction.name.lowercase()}." else "Scrolled ${request.direction.name.lowercase()}.",
                verified = true,
            )
            is ScreenAction.Navigate -> ScreenEffect("Pressed ${request.key.name.lowercase().replace('_', ' ')}.", verified = true)
            is ScreenAction.Read -> error("Read does not dispatch a platform action")
        }
    }

    private fun plan(screen: ScreenSnapshot, request: ScreenAction): Plan {
        fun target(id: Int) = screen.elements[id]?.takeIf { it.isEnabled }
            ?: throw ManualScreenStep("That element is unavailable. Use the screen to continue.")
        fun stamp(id: Int) = screen.stamps[id] ?: throw ManualScreenStep(CHANGED)
        fun editableLabel(id: Int): String {
            val field = target(id)
            val info = stamp(id)
            val resource = info.resource.orEmpty().substringAfter(":id/")
            val protectedField = ScreenReader.isProtectedNode(field) ||
                listOfNotNull(info.hint, info.description, resource).any(SensitiveActions::isProtectedLabel)
            if (!field.isEditable || protectedField || AccessibilityNodeInfo.ACTION_SET_TEXT !in info.actions) {
                throw ManualScreenStep("I can’t verify that this is a non-secret text field. Type into it manually.")
            }
            return listOfNotNull(info.hint, info.description)
                .firstOrNull { it.isNotBlank() && SensitiveActions.canQuote(it, 60) }
                ?: "text field"
        }
        return when (request) {
            is ScreenAction.Edit -> {
                if (!SensitiveActions.canQuote(request.text, 160)) throw ManualScreenStep("I can’t review that text safely. Type it manually.")
                Plan(request, id = request.id, target = editableLabel(request.id), payload = request.text, targetStamp = stamp(request.id))
            }
            is ScreenAction.Search -> {
                val search = target(request.buttonId)
                val button = stamp(request.buttonId)
                val field = stamp(request.fieldId)
                val label = editableLabel(request.fieldId)
                val query = field.text.orEmpty()
                val searchFields = screen.stamps.count { (id, info) ->
                    screen.elements[id]?.isEditable == true &&
                        listOfNotNull(info.hint, info.description).any(SensitiveActions::isSearchLabel)
                }
                if (!isDirectClick(search, button) || !hasOnlySearchLabel(button) || !SensitiveActions.isSearchLabel(label) ||
                    searchFields != 1 || !SensitiveActions.canQuote(query, 160) || target(request.fieldId).isShowingHintText) {
                    throw ManualScreenStep("I can’t verify which search the button would submit. Submit it manually.")
                }
                Plan(
                    request, request.buttonId, request.fieldId, AccessibilityNodeInfo.ACTION_CLICK, label, query,
                    targetStamp = button, otherStamp = field,
                )
            }
            is ScreenAction.Tap -> {
                val node = target(request.id)
                val info = stamp(request.id)
                val clickTarget = findClickableAncestor(node)
                val labels = buildList {
                    info.text.takeUnless { node.isEditable }?.takeIf { it.isNotBlank() }?.let(::add)
                    info.description?.takeIf { it.isNotBlank() }?.let(::add)
                    info.hint?.takeIf { it.isNotBlank() }?.let(::add)
                    if (clickTarget != null && clickTarget !== node) {
                        clickTarget.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
                val resources = listOfNotNull(
                    info.resource?.substringAfter(":id/")?.takeIf { it.isNotBlank() },
                    clickTarget?.takeIf { it !== node }?.viewIdResourceName?.substringAfter(":id/")?.takeIf { it.isNotBlank() },
                )
                if (ScreenReader.isProtectedNode(node) || (clickTarget != null && ScreenReader.isProtectedNode(clickTarget)) ||
                    labels.any(SensitiveActions::isProtectedLabel) || resources.any(SensitiveActions::isProtectedLabel)) {
                    throw ManualScreenStep("I won’t tap a protected or credential control. Use the screen yourself.")
                }
                val bounds = Rect().also(node::getBoundsInScreen)
                if (clickTarget == null && bounds.isEmpty) {
                    throw ManualScreenStep("I can’t find an interactive area for that element. Use the screen yourself.")
                }
                val needsConfirm = labels.any(SensitiveActions::needsConfirmation) || resources.any(SensitiveActions::needsConfirmation)
                val targetLabel = labels.firstOrNull { SensitiveActions.canQuote(it, 40) }
                    ?: labels.firstOrNull()?.filterNot { it.isISOControl() }?.trim()?.take(40)?.takeIf { it.isNotBlank() }
                    ?: "that control"
                Plan(
                    request = request,
                    id = request.id,
                    action = if (clickTarget != null) AccessibilityNodeInfo.ACTION_CLICK else null,
                    target = targetLabel,
                    needsConfirm = needsConfirm,
                    targetStamp = info,
                )
            }
            is ScreenAction.Scroll -> {
                val action = scrollAction(request.direction, request.page)
                if (request.id != null) {
                    val node = target(request.id)
                    val info = stamp(request.id)
                    val resolvedAction = if (node.isScrollable && action in info.actions) action else null
                    Plan(request, id = request.id, action = resolvedAction, targetStamp = info)
                } else {
                    val bestId = screen.elements.entries
                        .filter { (id, node) -> node.isEnabled && node.isScrollable && action in stamp(id).actions }
                        .maxByOrNull { (_, node) ->
                            val r = Rect().also(node::getBoundsInScreen)
                            r.width().toLong().coerceAtLeast(0L) * r.height().toLong().coerceAtLeast(0L)
                        }?.key
                    if (bestId != null) {
                        Plan(request, id = bestId, action = action, targetStamp = stamp(bestId))
                    } else {
                        // Fall back to a foreground-window swipe gesture for Compose/WebView/launcher screens.
                        Plan(request, id = null, action = null)
                    }
                }
            }
            is ScreenAction.Read -> {
                val node = target(request.id)
                val info = stamp(request.id)
                val text = info.text?.takeIf(String::isNotBlank) ?: info.description.orEmpty()
                if (node.isEditable || !SensitiveActions.canQuote(text, 500)) {
                    throw ManualScreenStep("I can’t verify text I can safely read from that element.")
                }
                Plan(request, id = request.id, payload = text, targetStamp = info)
            }
            is ScreenAction.Navigate -> Plan(request, action = globalAction(request.key))
            is ScreenAction.Open -> {
                val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val apps = packageManager.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
                    .map { AppEntry(it.loadLabel(packageManager).toString(), it.activityInfo.packageName, it.activityInfo.name) }
                    .distinctBy { it.packageName }
                val app = AppMatcher.match(request.name, apps).app
                    ?.takeIf { SensitiveActions.canQuote(it.label, 80) && !SensitiveActions.isProtectedLabel(it.label) &&
                        !SensitiveActions.isProtectedPackage(it.packageName) && it.packageName != packageName }
                    ?: throw ManualScreenStep("I can’t identify an allowed installed app for that name. Open it yourself.")
                Plan(request, app = app)
            }
        }
    }

    private fun refreshTarget(screen: ScreenSnapshot, id: Int): AccessibilityNodeInfo {
        val node = screen.elements[id] ?: throw ManualScreenStep(CHANGED)
        val window = checkNotNull(screen.context).window
        if (!node.refresh() || !node.isVisibleToUser || !node.isEnabled || node.windowId != window.windowId ||
            node.packageName?.toString() != window.packageName || ScreenReader.isProtectedNode(node) ||
            NodeStamp.of(node) != screen.stamps[id]) throw ManualScreenStep(CHANGED)
        return node
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        repeat(5) {
            val candidate = cur ?: return null
            if (candidate.isEnabled && candidate.isVisibleToUser && !ScreenReader.isProtectedNode(candidate) &&
                (candidate.isClickable || candidate.isCheckable) &&
                candidate.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
                return candidate
            }
            cur = candidate.parent
        }
        return null
    }

    private fun dispatchTapGesture(node: AccessibilityNodeInfo, windowBounds: Rect, cancelled: () -> Boolean): Boolean {
        val rect = Rect().also(node::getBoundsInScreen)
        if (!rect.intersect(windowBounds) || rect.isEmpty) return false
        val path = Path().apply { moveTo(rect.exactCenterX(), rect.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 50L))
            .build()
        return dispatchGestureSync(gesture, cancelled)
    }

    private fun dispatchSwipeGesture(
        targetBounds: Rect,
        windowBounds: Rect,
        direction: Direction,
        cancelled: () -> Boolean,
    ): Boolean {
        val rect = Rect(targetBounds)
        if (!rect.intersect(windowBounds) || rect.width() < 40 || rect.height() < 40) {
            rect.set(windowBounds)
        }
        if (rect.width() < 40 || rect.height() < 40) return false
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        val dx = rect.width() * 0.30f
        val dy = rect.height() * 0.28f
        val minX = (windowBounds.left + 24).toFloat()
        val maxX = (windowBounds.right - 24).toFloat().coerceAtLeast(minX)
        val minY = (windowBounds.top + 48).toFloat()
        val maxY = (windowBounds.bottom - 48).toFloat().coerceAtLeast(minY)
        val (sx, sy, ex, ey) = when (direction) {
            Direction.DOWN -> listOf(cx, (cy + dy).coerceIn(minY, maxY), cx, (cy - dy).coerceIn(minY, maxY))
            Direction.UP -> listOf(cx, (cy - dy).coerceIn(minY, maxY), cx, (cy + dy).coerceIn(minY, maxY))
            Direction.RIGHT -> listOf((cx + dx).coerceIn(minX, maxX), cy, (cx - dx).coerceIn(minX, maxX), cy)
            Direction.LEFT -> listOf((cx - dx).coerceIn(minX, maxX), cy, (cx + dx).coerceIn(minX, maxX), cy)
        }
        val path = Path().apply {
            moveTo(sx, sy)
            lineTo(ex, ey)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 260L))
            .build()
        return dispatchGestureSync(gesture, cancelled)
    }

    private fun dispatchGestureSync(gesture: GestureDescription, cancelled: () -> Boolean): Boolean {
        val done = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                completed.set(true)
                done.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                completed.set(false)
                done.countDown()
            }
        }, null)
        if (!dispatched) return false
        val deadline = SystemClock.elapsedRealtime() + 1_500
        while (!done.await(50, TimeUnit.MILLISECONDS)) {
            checkRunning(cancelled)
            if (SystemClock.elapsedRealtime() >= deadline) return false
        }
        return completed.get()
    }

    private fun isDirectClick(node: AccessibilityNodeInfo, stamp: NodeStamp) =
        node.isClickable && !node.isCheckable && AccessibilityNodeInfo.ACTION_CLICK in stamp.actions

    private fun hasOnlySearchLabel(stamp: NodeStamp): Boolean =
        listOfNotNull(stamp.text, stamp.description).filter(String::isNotBlank)
            .let { it.isNotEmpty() && it.all(SensitiveActions::isSearchLabel) }

    private fun startLauncher(app: AppEntry): Boolean {
        val activity = app.activity ?: return false
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(app.packageName, activity).addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return launch(intent)
    }

    private fun globalAction(key: NavigationKey) = when (key) {
        NavigationKey.BACK -> GLOBAL_ACTION_BACK
        NavigationKey.HOME -> GLOBAL_ACTION_HOME
        NavigationKey.RECENTS -> GLOBAL_ACTION_RECENTS
        NavigationKey.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
        NavigationKey.QUICK_SETTINGS -> GLOBAL_ACTION_QUICK_SETTINGS
    }

    private fun scrollAction(direction: Direction, page: Boolean): Int {
        val usePage = page && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        return when (direction) {
            Direction.UP -> if (usePage) AccessibilityAction.ACTION_PAGE_UP.id else AccessibilityAction.ACTION_SCROLL_UP.id
            Direction.DOWN -> if (usePage) AccessibilityAction.ACTION_PAGE_DOWN.id else AccessibilityAction.ACTION_SCROLL_DOWN.id
            Direction.LEFT -> if (usePage) AccessibilityAction.ACTION_PAGE_LEFT.id else AccessibilityAction.ACTION_SCROLL_LEFT.id
            Direction.RIGHT -> if (usePage) AccessibilityAction.ACTION_PAGE_RIGHT.id else AccessibilityAction.ACTION_SCROLL_RIGHT.id
        }
    }

    /** Direct user voice screen-control actions routed through the same live node & security checks. */
    internal fun userControl(
        cmd: FastCommand,
        asker: ((String) -> String?)?,
        check: () -> Unit,
    ): ScreenEffect = try {
        check()
        val cancelled = {
            if (interrupted()) true else try { check(); false } catch (_: RuntimeException) { true }
        }
        when (cmd) {
            is FastCommand.Scroll -> {
                val dir = Direction.entries.singleOrNull { it.name.equals(cmd.direction, true) }
                    ?: throw ManualScreenStep(UNAVAILABLE)
                val screen = observe(300, cancelled)
                val effect = perform(screen, 300, ScreenAction.Scroll(null, dir, page = cmd.page), Any(), cancelled, { null }, {})
                ScreenEffect(effect.summary, verified = true)
            }
            is FastCommand.TapText -> {
                val screen = observe(300, cancelled)
                val exact = screen.stamps.filterValues {
                    it.text.equals(cmd.text, true) || it.description.equals(cmd.text, true) || it.hint.equals(cmd.text, true)
                }.keys
                val candidates = exact.ifEmpty {
                    val partial = screen.stamps.filterValues {
                        it.text?.contains(cmd.text, true) == true || it.description?.contains(cmd.text, true) == true
                    }.keys
                    if (partial.size > 1) {
                        partial.filter { id -> screen.elements[id]?.let { findClickableAncestor(it) != null } == true }.ifEmpty { partial }
                    } else partial
                }
                val id = candidates.singleOrNull()
                    ?: throw ManualScreenStep(
                        if (candidates.isEmpty()) "I couldn’t find ${cmd.text} on this screen."
                        else "There is more than one match for ${cmd.text} on this screen."
                    )
                val effect = perform(
                    screen, 300, ScreenAction.Tap(id), Any(), cancelled,
                    ask = { q -> asker?.invoke(q) }, dispatching = {},
                )
                ScreenEffect(effect.summary, verified = true)
            }
            is FastCommand.TypeText -> {
                val screen = observe(300, cancelled)
                val target = screen.elements.keys.singleOrNull {
                    screen.elements[it]?.let { n -> n.isFocused && n.isEditable } == true
                } ?: screen.elements.keys.singleOrNull { screen.elements[it]?.isEditable == true }
                    ?: throw ManualScreenStep("I couldn’t find an active text field to type into.")
                perform(screen, 300, ScreenAction.Edit(target, cmd.text), Any(), cancelled, { null }, {}, explicitEdit = true)
            }
            FastCommand.ClearText -> {
                val screen = observe(300, cancelled)
                val target = screen.elements.keys.singleOrNull { screen.elements[it]?.isFocused == true }
                    ?: throw ManualScreenStep("I can’t verify a focused text field to clear.")
                val node = refreshTarget(screen, target)
                val stamp = screen.stamps[target] ?: throw ManualScreenStep(CHANGED)
                if (!node.isEditable || AccessibilityNodeInfo.ACTION_SET_TEXT !in stamp.actions || ScreenReader.isProtectedNode(node)) {
                    throw ManualScreenStep("I can’t safely clear that field.")
                }
                check()
                val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "") }
                if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    throw ManualScreenStep("Android didn’t confirm clearing the text field.")
                }
                val verified = node.refresh() && node.text.isNullOrEmpty()
                ScreenEffect(
                    if (verified) "Cleared the text field." else "Requested clearing the text field, but couldn't verify it.",
                    verified = verified,
                )
            }
            FastCommand.SubmitSearch -> {
                val screen = observe(300, cancelled)
                val buttonId = screen.stamps.entries.singleOrNull { (id, info) ->
                    val node = screen.elements[id]
                    node != null && node.isEnabled && isDirectClick(node, info) && hasOnlySearchLabel(info)
                }?.key ?: throw ManualScreenStep("I couldn’t find a unique Search button on this screen.")
                val fieldId = screen.stamps.entries.singleOrNull { (id, info) ->
                    val node = screen.elements[id]
                    node != null && node.isEditable && !node.isShowingHintText &&
                        listOfNotNull(info.hint, info.description).any(SensitiveActions::isSearchLabel)
                }?.key ?: throw ManualScreenStep("I couldn’t verify the search field for that button.")
                val effect = perform(
                    screen, 300, ScreenAction.Search(buttonId, fieldId), Any(), cancelled,
                    ask = { q -> asker?.invoke(q) }, dispatching = {},
                )
                ScreenEffect(effect.summary, verified = true)
            }
            is FastCommand.YouTubeSearch -> {
                val searchIntent = Intent(Intent.ACTION_SEARCH)
                    .setPackage(YouTubeAction.packageName)
                    .putExtra(SearchManager.QUERY, cmd.query)
                val encoded = URLEncoder.encode(cmd.query, "UTF-8")
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded"))
                val sent = launch(searchIntent) || launch(fallbackIntent)
                if (!sent) throw ManualScreenStep("I couldn’t open YouTube search.")
                val shown = waitForForeground(YouTubeAction.packageName, 3_000, cancelled)
                ScreenEffect(
                    if (shown) "Opened YouTube search for ${cmd.query}." else "I asked Android to search YouTube for ${cmd.query}.",
                    verified = shown,
                )
            }
            else -> throw ManualScreenStep(UNAVAILABLE)
        }
    } catch (e: ManualScreenStep) {
        ScreenEffect(e.message.orEmpty(), verified = false)
    }

    /** Legacy fast paths have a real user request, but still go through the same live node gate. */
    fun tap(id: Int): String = directResult { direct(snapshot ?: throw ManualScreenStep(CHANGED), ScreenAction.Tap(id)) }

    fun tapByText(text: String): String = directResult {
        val screen = observe(300, ::interrupted)
        val ids = screen.stamps.filterValues { it.text.equals(text, true) || it.description.equals(text, true) }.keys
        direct(screen, ScreenAction.Tap(ids.singleOrNull() ?: throw ManualScreenStep("There isn’t exactly one visible match.")))
    }

    fun typeText(id: Int?, text: String, submit: Boolean): String = directResult {
        if (submit) throw ManualScreenStep("Type and submit are separate actions. Submit that manually.")
        val screen = observe(300, ::interrupted)
        val target = id ?: screen.elements.keys.singleOrNull { screen.elements[it]?.isFocused == true }
            ?: throw ManualScreenStep("I can’t verify a focused text field.")
        direct(screen, ScreenAction.Edit(target, text), explicitEdit = true)
    }

    fun scroll(id: Int?, direction: String): String = try {
        val dir = Direction.entries.singleOrNull { it.name.equals(direction, true) } ?: throw ManualScreenStep(UNAVAILABLE)
        val screen = if (id == null) observe(300, ::interrupted) else snapshot ?: throw ManualScreenStep(CHANGED)
        direct(screen, ScreenAction.Scroll(id, dir))
        "Scrolled $direction."
    } catch (e: ManualScreenStep) {
        "Scroll failed. ${e.message}"
    }

    fun pressKey(key: String): String = directResult {
        val action = when (key) {
            "screenshot" -> GLOBAL_ACTION_TAKE_SCREENSHOT
            "lock" -> GLOBAL_ACTION_LOCK_SCREEN
            else -> null
        }
        if (action != null) {
            if (!unlocked() || Build.VERSION.SDK_INT < 28) throw ManualScreenStep("Unlock your phone and use the system control.")
            if (!performGlobalAction(action)) throw ManualScreenStep("Android didn’t accept that action.")
        } else {
            val nav = NavigationKey.entries.singleOrNull { it.name.equals(key, true) } ?: throw ManualScreenStep(UNAVAILABLE)
            if (!unlocked()) throw ManualScreenStep("Unlock your phone before using phone control.")
            if (!performGlobalAction(globalAction(nav))) throw ManualScreenStep("Android didn’t accept that action.")
        }
        "Pressed $key."
    }

    private fun direct(screen: ScreenSnapshot, action: ScreenAction, explicitEdit: Boolean = false): String =
        perform(screen, 300, action, Any(), ::interrupted, { null }, {}, explicitEdit).summary

    private inline fun directResult(block: () -> String): String =
        try { block() } catch (e: ManualScreenStep) { e.message.orEmpty() }

    /** Launches a scoped, locally constructed intent; callers verify results where possible. */
    fun launch(intent: Intent): Boolean = try {
        if (!unlocked() || interrupted()) false
        else {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }
    } catch (_: RuntimeException) {
        false
    }

    fun openPackage(packageName: String, activity: String? = null): Boolean {
        if (activity != null && startLauncher(AppEntry(packageName, packageName, activity))) return true
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return launch(intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
    }

    /** Only observes the foreground package, not window contents. Must not be called on the main thread. */
    fun waitForForeground(packageName: String, timeoutMs: Long): Boolean =
        waitForForeground(packageName, timeoutMs, ::interrupted)

    private fun waitForForeground(packageName: String, timeoutMs: Long, cancelled: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (unlocked() && !cancelled() && SystemClock.elapsedRealtime() < deadline) {
            if (rootInActiveWindow?.packageName?.toString() == packageName) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun checkRunning(cancelled: () -> Boolean) {
        if (interrupted() || cancelled()) throw ManualScreenStep("Okay, I stopped.")
    }

    private fun interrupted(): Boolean = Thread.currentThread().isInterrupted

    companion object {
        private const val TAG = "JarvisA11y"
        private const val CAPTURE_ALLOWED = 0
        private const val UNAVAILABLE = "I can’t verify that this screen is safe to control. Use the phone yourself."
        private const val CHANGED = "The screen changed before I could act. I stopped so nothing would go to the wrong place."

        @Volatile var instance: JarvisAccessibilityService? = null
            private set
    }
}
