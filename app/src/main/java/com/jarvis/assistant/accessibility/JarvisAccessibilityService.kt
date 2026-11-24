package com.jarvis.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import androidx.annotation.RequiresApi
import com.jarvis.assistant.agent.OneUseApproval
import com.jarvis.assistant.agent.SensitiveActions
import com.jarvis.assistant.apps.ScreenItem
import com.jarvis.assistant.apps.YouTubeAction
import com.jarvis.assistant.router.AppEntry
import com.jarvis.assistant.router.AppMatcher
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        foreground().also { if (it.context != expected) throw ManualScreenStep(CHANGED) }

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
        repeat(2) { attempt ->
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
                if (SystemClock.elapsedRealtime() >= deadline) return false
            }
            if (result == CAPTURE_ALLOWED) return true
            if (result != ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT || attempt != 0) return false
            // The platform enforces a 333 ms per-window interval.
            Thread.sleep(350)
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
    )

    private data class ApprovalBinding(val turn: Any, val screen: ScreenContext, val plan: Plan)

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
            if (live.context != context) throw ManualScreenStep(CHANGED)
            val plan = plan(live, request)
            for (id in listOfNotNull(plan.id, plan.otherId)) {
                if (live.elements[id] != screen.elements[id] || live.stamps[id] != screen.stamps[id]) {
                    throw ManualScreenStep(CHANGED)
                }
            }
            return live to plan
        }

        var (live, plan) = qualify()
        val app = context.window.packageName
        val question = when (request) {
            is ScreenAction.Edit -> if (explicitEdit) null else "Replace ${plan.target} in $app with “${plan.payload}”? Say yes to type it."
            is ScreenAction.Search -> "Submit the search “${plan.payload}” in $app? Say yes to search."
            is ScreenAction.Read -> "Read the selected on-screen text from $app aloud? Say yes to read it."
            else -> null
        }
        val approval = question?.let { OneUseApproval(ApprovalBinding(turn, context, plan), SystemClock.elapsedRealtime()) }
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
        sameForeground(context.window)
        if (approval != null &&
            !approval.consume(ApprovalBinding(turn, context, plan), answer, SystemClock.elapsedRealtime(), cancelled())) {
            throw ManualScreenStep("That approval expired or the action changed. I stopped without doing it.")
        }
        checkRunning(cancelled)
        if (request is ScreenAction.Read) return ScreenEffect("The selected text says: “${plan.payload}”", verified = true)

        // Once dispatch is possible, a provider error must not replay the step through a fallback.
        dispatching(request is ScreenAction.Search)
        checkRunning(cancelled)
        val accepted = when (request) {
            is ScreenAction.Open -> startLauncher(plan.app ?: throw ManualScreenStep(UNAVAILABLE))
            is ScreenAction.Navigate -> performGlobalAction(plan.action ?: throw ManualScreenStep(UNAVAILABLE))
            is ScreenAction.Edit -> {
                val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, plan.payload) }
                checkNotNull(node).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
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
                val verified = sameForeground(context.window).context == context.window && field.refresh() &&
                    field.isVisibleToUser && !ScreenReader.isProtectedNode(field) && field.text?.toString() == plan.payload
                if (verified) ScreenEffect("Typed the text in the selected field. I didn’t submit it.", true)
                else ScreenEffect("Requested text entry, but couldn’t verify the field afterwards.")
            }
            is ScreenAction.Search -> ScreenEffect("Requested the search. I haven’t verified its results.")
            is ScreenAction.Tap -> ScreenEffect("Tapped the selected control.")
            is ScreenAction.Scroll -> ScreenEffect(if (request.page) "Requested a page swipe." else "Requested a scroll.")
            is ScreenAction.Navigate -> ScreenEffect("Requested ${request.key.name.lowercase().replace('_', ' ')}.")
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
            val label = listOfNotNull(info.hint, info.description).firstOrNull(SensitiveActions::isEditableLabel)
            if (!field.isEditable || label == null || !SensitiveActions.canQuote(label, 40) ||
                info.inputType and android.text.InputType.TYPE_MASK_CLASS != android.text.InputType.TYPE_CLASS_TEXT ||
                AccessibilityNodeInfo.ACTION_SET_TEXT !in info.actions) {
                throw ManualScreenStep("I can’t verify that this is a non-secret text field. Type into it manually.")
            }
            return label
        }
        return when (request) {
            is ScreenAction.Edit -> {
                if (!SensitiveActions.canQuote(request.text, 160)) throw ManualScreenStep("I can’t review that text safely. Type it manually.")
                Plan(request, id = request.id, target = editableLabel(request.id), payload = request.text)
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
                Plan(request, request.buttonId, request.fieldId, AccessibilityNodeInfo.ACTION_CLICK, label, query)
            }
            is ScreenAction.Tap -> {
                val node = target(request.id)
                val info = stamp(request.id)
                val labels = listOfNotNull(info.text.takeUnless { node.isEditable }, info.description, info.hint).filter { it.isNotBlank() }
                val resource = info.resource.orEmpty().substringAfter(":id/")
                val youtubeVideo = screen.foregroundPackage == YouTubeAction.packageName &&
                    info.description.orEmpty().trim().endsWith("play video", ignoreCase = true) &&
                    YouTubeAction.pickResult(listOf(ScreenItem(request.id, info.description.orEmpty(), true))) != null
                val navigation = labels.isNotEmpty() && labels.all(SensitiveActions::isNavigationLabel)
                val emptySearch = !hasOnlySearchLabel(info) ||
                    screen.elements.none { (id, elem) -> elem.isEditable && !elem.isShowingHintText && !stamp(id).text.isNullOrBlank() }
                if (!isDirectClick(node, info) || labels.any(SensitiveActions::needsConfirmation) ||
                    SensitiveActions.needsConfirmation(resource) ||
                    !(youtubeVideo || (navigation && emptySearch) || (node.isEditable && editableLabel(request.id).isNotBlank()))) {
                    throw ManualScreenStep("I can’t verify what that tap would do. Use the visible control yourself.")
                }
                Plan(request, id = request.id, action = AccessibilityNodeInfo.ACTION_CLICK)
            }
            is ScreenAction.Scroll -> {
                val action = scrollAction(request.direction, request.page)
                val id = request.id ?: screen.elements.keys.singleOrNull {
                    screen.elements[it]?.let { node -> node.isEnabled && node.isScrollable } == true &&
                        action in stamp(it).actions
                }
                if (id == null || !target(id).isScrollable || action !in stamp(id).actions) {
                    throw ManualScreenStep("I can’t verify a scrollable control for that direction. Move the screen manually.")
                }
                Plan(request, id = id, action = action)
            }
            is ScreenAction.Read -> {
                val node = target(request.id)
                val info = stamp(request.id)
                val text = info.text?.takeIf(String::isNotBlank) ?: info.description.orEmpty()
                if (node.isEditable || !SensitiveActions.canQuote(text, 500)) {
                    throw ManualScreenStep("I can’t verify text I can safely read from that element.")
                }
                Plan(request, id = request.id, payload = text)
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
        private const val CAPTURE_ALLOWED = 0
        private const val UNAVAILABLE = "I can’t verify that this screen is safe to control. Use the phone yourself."
        private const val CHANGED = "The screen changed before I could act. I stopped so nothing would go to the wrong place."

        @Volatile var instance: JarvisAccessibilityService? = null
            private set
    }
}
