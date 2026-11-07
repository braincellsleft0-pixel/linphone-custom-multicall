/*
 * Copyright (c) 2010-2024 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.ui.call.bubble

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewPropertyAnimator
import android.view.WindowManager
import android.widget.TextView
import androidx.annotation.UiThread
import androidx.appcompat.view.ContextThemeWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.core.AudioDevice
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Reason
import org.linphone.core.tools.Log
import org.linphone.utils.LinphoneUtils
import java.util.Locale
import kotlin.math.abs

/**
 * Manages the floating call bubble (Messenger Chat Head style).
 * Floats over the user's browser/CRM without taking over fullscreen.
 * Tapping the bubble directly launches the real Linphone CallActivity
 * in a floating/overlay window (no custom UI!).
 */
object FloatingBubbleManager {
    private const val TAG = "[Floating Bubble Manager]"

    private enum class Phase { IDLE, SHOWING, VISIBLE, ENDING, HIDING }

    @Volatile
    private var phase = Phase.IDLE

    val isShowing: Boolean
        get() = phase != Phase.IDLE

    var isCallActivityVisible: Boolean = false
        private set

    private val isDismissing: Boolean
        get() = phase == Phase.ENDING || phase == Phase.HIDING

    enum class BubbleState(
        val label: String,
        val dotColor: Int,
        val statusColor: Int,
        val showTimer: Boolean,
        val showEndButton: Boolean
    ) {
        CALLING("Calling...", 0xFFF59E0B.toInt(), 0xFFFBBF24.toInt(), false, true),
        RINGING("Ringing...", 0xFFF59E0B.toInt(), 0xFFFBBF24.toInt(), false, true),
        CONNECTED("Connected", 0xFF10B981.toInt(), 0xFF34D399.toInt(), true, true),
        HOLD("On Hold", 0xFFF97316.toInt(), 0xFFFB923C.toInt(), true, true),
        ENDED("Ended", 0xFFEF4444.toInt(), 0xFF9CA3AF.toInt(), false, false)
    }

    private var windowManager: WindowManager? = null
    private var bubbleRootView: View? = null          // = bubble_window_root
    private var chipView: View? = null                // = bubble_collapsed_root
    private var layoutParams: WindowManager.LayoutParams? = null

    private var tickerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var endedRunnable: Runnable? = null
    private var currentFallbackNumber: String? = null
    private var lastKnownDisplayName: String = "Call"
    private var lastDurationSeconds: Int = 0
    private var lastAppliedState: BubbleState? = null
    private var pulseAnimator: ObjectAnimator? = null

    private var dockedRight: Boolean = true
    private var lastDockedY: Int? = null

    @Volatile
    private var isInitialized = false

    private val terminatingCalls = java.util.Collections.synchronizedSet(java.util.HashSet<Call>())

    // Coalesce bursts of call events into a single bubble refresh
    private var uiUpdateScheduled = false
    private val uiUpdateRunnable = Runnable {
        uiUpdateScheduled = false
        updateUI()
    }

    private fun scheduleUpdateUI() {
        if (uiUpdateScheduled) return
        uiUpdateScheduled = true
        mainHandler.postDelayed(uiUpdateRunnable, 150)
    }

    private val coreListener = object : CoreListenerStub() {
        override fun onCallStateChanged(core: Core, call: Call, state: Call.State, message: String) {
            Log.i("$TAG Call state changed: [$state] for [${call.remoteAddress?.asStringUriOnly()}]")
            terminatingCalls.removeAll { LinphoneUtils.isCallEnding(it.state, considerReleasedAsEnding = true) }
            val hasActiveCalls = core.calls.any {
                it !in terminatingCalls && !LinphoneUtils.isCallEnding(it.state, considerReleasedAsEnding = true)
            }
            mainHandler.post {
                if (!hasActiveCalls) {
                    Log.i("$TAG No active calls remaining, calling onAllCallsEnded()")
                    onAllCallsEnded()
                } else {
                    reviveIfEnding()
                    if (!isCallActivityVisible && canDrawOverlays(coreContext.context)) {
                        if (!isShowing) {
                            show()
                        } else {
                            bubbleRootView?.visibility = View.VISIBLE
                            scheduleUpdateUI()
                        }
                    } else if (isShowing) {
                        scheduleUpdateUI()
                    }
                }
            }
        }

        override fun onAudioDeviceChanged(core: Core, audioDevice: AudioDevice) {
            mainHandler.post {
                if (isShowing) {
                    updateUI()
                }
            }
        }
    }

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true
        Log.i("$TAG Initializing FloatingBubbleManager")
        coreContext.postOnCoreThread { core ->
            core.removeListener(coreListener)
            core.addListener(coreListener)
        }
    }

    fun canDrawOverlays(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun requestOverlayPermission(context: Context) {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + context.packageName)
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("$TAG Failed to launch overlay settings: $e")
        }
    }

    var activeCallActivity: java.lang.ref.WeakReference<org.linphone.ui.call.CallActivity>? = null

    fun setCallActivity(activity: org.linphone.ui.call.CallActivity) {
        activeCallActivity = java.lang.ref.WeakReference(activity)
        isCallActivityVisible = true
        Log.i("$TAG CallActivity registered as active window")
    }

    fun clearCallActivity(activity: org.linphone.ui.call.CallActivity) {
        if (activeCallActivity?.get() == activity) {
            activeCallActivity = null
            isCallActivityVisible = false
            Log.i("$TAG CallActivity unregistered")
        }
    }

    fun onCallActivityResumed(activity: org.linphone.ui.call.CallActivity? = null) {
        Log.i("$TAG CallActivity resumed, hiding floating bubble smoothly")
        if (activity != null) {
            setCallActivity(activity)
        } else {
            isCallActivityVisible = true
        }
        stopDurationTicker()
        mainHandler.post {
            val chip = chipView ?: return@post
            chip.animateSafe(
                120,
                android.view.animation.AccelerateInterpolator(),
                {
                    alpha(0f)
                    scaleX(0.85f)
                    scaleY(0.85f)
                },
                {
                    hideTemporarily()
                    chip.alpha = 1f
                    chip.scaleX = 1f
                    chip.scaleY = 1f
                }
            )
        }
    }

    fun onCallActivityPaused(activity: org.linphone.ui.call.CallActivity? = null) {
        Log.i("$TAG CallActivity paused, checking if bubble should be restored")
        if (activity != null) {
            clearCallActivity(activity)
        } else {
            isCallActivityVisible = false
        }
        coreContext.postOnCoreThread { core ->
            terminatingCalls.removeAll { LinphoneUtils.isCallEnding(it.state, considerReleasedAsEnding = true) }
            val hasActiveCalls = core.calls.any {
                it !in terminatingCalls && !LinphoneUtils.isCallEnding(it.state, considerReleasedAsEnding = true)
            }
            mainHandler.post {
                if (hasActiveCalls && canDrawOverlays(coreContext.context)) {
                    reviveIfEnding()
                    if (isShowing && bubbleRootView != null) {
                        val chip = chipView
                        if (chip != null) {
                            bubbleRootView?.visibility = View.VISIBLE
                            if (chip.visibility != View.VISIBLE || chip.alpha < 0.95f) {
                                chip.visibility = View.VISIBLE
                                playEnterAnimation(chip)
                            }
                            startDurationTicker()
                            updateUI()
                        } else {
                            show()
                        }
                    } else {
                        show()
                    }
                } else if (!hasActiveCalls) {
                    onAllCallsEnded()
                } else {
                    removeBubbleNow()
                }
            }
        }
    }

    @UiThread
    fun revealForMinimize() {
        if (!canDrawOverlays(coreContext.context)) return
        isCallActivityVisible = false
        reviveIfEnding()
        val root = bubbleRootView
        val chip = chipView
        if (isShowing && root != null && chip != null) {
            root.visibility = View.VISIBLE
            if (chip.visibility != View.VISIBLE || chip.alpha < 0.95f) {
                chip.visibility = View.VISIBLE
                playEnterAnimation(chip)
            }
            startDurationTicker()
            updateUI()
        } else {
            show()
        }
    }

    @UiThread
    fun show(fallbackNumber: String? = null) {
        val context = coreContext.context
        if (!canDrawOverlays(context)) {
            Log.w("$TAG Cannot show floating bubble: overlay permission not granted")
            return
        }

        if (fallbackNumber != null) {
            currentFallbackNumber = fallbackNumber
        }

        if (!isInitialized) {
            init(context)
        }

        reviveIfEnding()

        if (isShowing && bubbleRootView != null) {
            val chip = chipView
            if (chip != null && chip.visibility == View.VISIBLE && chip.alpha >= 0.95f) {
                updateUI()
                return
            }
            if (chip != null) {
                bubbleRootView?.visibility = View.VISIBLE
                chip.visibility = View.VISIBLE
                playEnterAnimation(chip)
            }
            updateUI()
            return
        }

        try {
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val themedContext = ContextThemeWrapper(context, R.style.Theme_Linphone)
            val inflater = LayoutInflater.from(themedContext)
            val view = inflater.inflate(R.layout.floating_call_bubble, null)
            val chip = view.findViewById<View>(R.id.bubble_collapsed_root)
            bubbleRootView = view
            chipView = chip

            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val b = screenBounds()
            val initialY = lastDockedY?.coerceIn(b.top, (b.bottom - dp(64f)).coerceAtLeast(b.top))
                ?: (b.top + (b.height() * 0.18f).toInt())

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = if (dockedRight) (Gravity.TOP or Gravity.END) else (Gravity.TOP or Gravity.START)
                x = edgeMargin
                y = initialY
                windowAnimations = 0
            }
            layoutParams = params

            chip.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                v.pivotX = v.width / 2f
                v.pivotY = v.height / 2f
            }

            setupTouchAndGestures(view, params)
            setupClickListeners(view)

            // Populate UI BEFORE adding to WindowManager
            updateUI()

            windowManager?.addView(view, params)
            phase = Phase.VISIBLE

            playEnterAnimation(chip)
            startDurationTicker()
            Log.i("$TAG Floating call bubble successfully displayed on screen")
        } catch (e: Exception) {
            Log.e("$TAG Failed to add floating bubble to WindowManager: $e", e)
        }
    }

    private fun playEnterAnimation(chip: View) {
        chip.alpha = 0f
        chip.scaleX = 0.85f
        chip.scaleY = 0.85f
        chip.translationY = -dp(8f).toFloat()
        chip.animateSafe(
            220,
            android.view.animation.OvershootInterpolator(0.9f),
            {
                alpha(1f)
                scaleX(1f)
                scaleY(1f)
                translationY(0f)
            }
        )
    }

    /**
     * The ONLY function that handles "all calls are over".
     * Never terminates calls. Idempotent.
     */
    @UiThread
    fun onAllCallsEnded() {
        if (phase == Phase.ENDING || phase == Phase.HIDING) return
        val root = bubbleRootView
        if (root == null || phase == Phase.IDLE) {
            removeBubbleNow()
            return
        }

        phase = Phase.ENDING
        stopDurationTicker()
        org.linphone.ui.call.CallHandlerActivity.cancelAllPendingCalls()

        // Same size, same position: only colours/text change (End button is INVISIBLE, not GONE)
        applyBubbleState(root, BubbleState.ENDED, lastKnownDisplayName, lastDurationSeconds, 0)

        endedRunnable = Runnable { fadeOutAndRemove(180L) }
        mainHandler.postDelayed(endedRunnable!!, 900)
    }

    private fun fadeOutAndRemove(duration: Long) {
        val chip = chipView ?: run {
            removeBubbleNow()
            return
        }
        phase = Phase.HIDING
        chip.pivotX = chip.width / 2f
        chip.pivotY = chip.height / 2f
        chip.animateSafe(
            duration,
            android.view.animation.DecelerateInterpolator(),
            {
                alpha(0f)
                scaleX(0.9f)
                scaleY(0.9f)
            },
            {
                removeBubbleNow()
            }
        )
    }

    /**
     * Idempotent hard removal. Safe to call any number of times.
     */
    @UiThread
    private fun removeBubbleNow() {
        endedRunnable?.let { mainHandler.removeCallbacks(it) }
        endedRunnable = null
        stopDurationTicker()
        stopStatusDotPulse()
        val root = bubbleRootView
        val chip = chipView
        bubbleRootView = null
        chipView = null
        phase = Phase.IDLE
        currentFallbackNumber = null
        chip?.animate()?.cancel()
        if (root != null && root.isAttachedToWindow) {
            try {
                windowManager?.removeViewImmediate(root)
            } catch (e: Exception) {
                Log.w("$TAG removeView: $e")
            }
        }
        Log.i("$TAG Floating call bubble completely removed")
    }

    /**
     * A new call arrived while the bubble was ending/hiding -> reuse the same view, no second bubble.
     */
    @UiThread
    private fun reviveIfEnding() {
        if (phase != Phase.ENDING && phase != Phase.HIDING) return
        endedRunnable?.let { mainHandler.removeCallbacks(it) }
        endedRunnable = null
        chipView?.let {
            it.animate().cancel()
            it.alpha = 1f
            it.scaleX = 1f
            it.scaleY = 1f
            it.translationY = 0f
        }
        phase = Phase.VISIBLE
        startDurationTicker()
    }

    @UiThread
    fun dismiss() {
        stopDurationTicker()
        org.linphone.ui.call.CallHandlerActivity.cancelAllPendingCalls()
        coreContext.postOnCoreThread { core ->
            for (call in core.calls) {
                try {
                    if (call.dir == Call.Dir.Incoming && LinphoneUtils.isCallIncoming(call.state)) {
                        call.decline(Reason.Declined)
                    } else {
                        call.terminate()
                    }
                } catch (_: Exception) {}
            }
            core.terminateAllCalls()
            terminatingCalls.clear()
        }
        if (chipView == null || phase == Phase.IDLE) {
            removeBubbleNow()
            return
        }
        if (phase == Phase.HIDING) return // already fading, do nothing
        endedRunnable?.let { mainHandler.removeCallbacks(it) }
        endedRunnable = null
        fadeOutAndRemove(160L)
    }

    @UiThread
    private fun hideTemporarily() {
        bubbleRootView?.visibility = View.GONE
    }

    private inline fun View.animateSafe(
        duration: Long,
        interpolator: TimeInterpolator,
        crossinline configure: ViewPropertyAnimator.() -> Unit,
        noinline onFinished: (() -> Unit)? = null
    ) {
        animate().cancel()
        val a = animate().setDuration(duration).setInterpolator(interpolator)
        a.configure()
        a.setListener(object : AnimatorListenerAdapter() {
            private var cancelled = false

            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                a.setListener(null)
                if (!cancelled) onFinished?.invoke()
            }
        })
        a.start()
    }

    private fun dp(v: Float): Int = (v * coreContext.context.resources.displayMetrics.density).toInt()

    private val edgeMargin: Int
        get() = dp(2f)

    private fun screenBounds(): Rect {
        val wm = windowManager ?: return Rect(0, 0, 1080, 1920)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val m = wm.currentWindowMetrics
            val i = m.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()
            )
            Rect(m.bounds.left + i.left, m.bounds.top + i.top, m.bounds.right - i.right, m.bounds.bottom - i.bottom)
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            val res = coreContext.context.resources
            val sb = res.getIdentifier("status_bar_height", "dimen", "android").let {
                if (it > 0) res.getDimensionPixelSize(it) else dp(24f)
            }
            Rect(0, sb, dm.widthPixels, dm.heightPixels)
        }
    }

    private fun formatDuration(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        val hrs = s / 3600
        val mins = (s % 3600) / 60
        val secs = s % 60
        return if (hrs > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hrs, mins, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", mins, secs)
        }
    }

    private fun stateOf(call: Call): BubbleState {
        return when (call.state) {
            Call.State.Connected, Call.State.StreamsRunning -> BubbleState.CONNECTED
            Call.State.Paused, Call.State.PausedByRemote, Call.State.Pausing -> BubbleState.HOLD
            Call.State.OutgoingRinging, Call.State.OutgoingEarlyMedia -> BubbleState.RINGING
            Call.State.OutgoingInit, Call.State.OutgoingProgress -> BubbleState.CALLING
            else -> BubbleState.CONNECTED
        }
    }

    private fun startStatusDotPulse(dot: View) {
        if (pulseAnimator != null) return
        pulseAnimator = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.3f).apply {
            duration = 700
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopStatusDotPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
    }

    private fun applyBubbleState(
        view: View,
        state: BubbleState,
        displayName: String?,
        durationSeconds: Int = 0,
        activeCallCount: Int = 1
    ) {
        val txtCaller = view.findViewById<TextView>(R.id.bubble_collapsed_caller)
        val txtStatus = view.findViewById<TextView>(R.id.bubble_collapsed_status)
        val txtTimer = view.findViewById<TextView>(R.id.bubble_collapsed_timer)
        val badge = view.findViewById<TextView>(R.id.bubble_collapsed_count_badge)
        val iconContainer = view.findViewById<View>(R.id.bubble_collapsed_icon_container)
        val statusDot = view.findViewById<View>(R.id.bubble_collapsed_status_dot)
        val endBtn = view.findViewById<View>(R.id.bubble_collapsed_quick_end)

        val name = displayName ?: lastKnownDisplayName
        if (txtCaller?.text != name) {
            txtCaller?.text = name
        }

        val statusLabel = if (state == BubbleState.ENDED) "Call ended" else state.label
        if (txtStatus?.text != statusLabel) {
            txtStatus?.text = statusLabel
        }
        if (lastAppliedState?.statusColor != state.statusColor) {
            txtStatus?.setTextColor(state.statusColor)
        }

        if (lastAppliedState?.dotColor != state.dotColor) {
            statusDot?.background?.mutate()?.setTint(state.dotColor)
            val containerBg = iconContainer?.background?.mutate()
            if (containerBg is GradientDrawable) {
                containerBg.setStroke(dp(1.5f), state.dotColor)
            }
        }

        if (statusDot != null) {
            if (state == BubbleState.CALLING || state == BubbleState.RINGING) {
                startStatusDotPulse(statusDot)
            } else {
                stopStatusDotPulse()
                statusDot.alpha = 1f
            }
        }

        if (state.showTimer) {
            val formatted = formatDuration(durationSeconds)
            if (txtTimer?.text != formatted) {
                txtTimer?.text = formatted
            }
            if (txtTimer?.visibility != View.VISIBLE) {
                txtTimer?.visibility = View.VISIBLE
            }
            txtTimer?.setTextColor(0xFF34D399.toInt())
        } else if (state == BubbleState.ENDED) {
            if (durationSeconds > 0) {
                val formatted = formatDuration(durationSeconds)
                if (txtTimer?.text != formatted) {
                    txtTimer?.text = formatted
                }
                if (txtTimer?.visibility != View.VISIBLE) {
                    txtTimer?.visibility = View.VISIBLE
                }
                txtTimer?.setTextColor(0xFF9CA3AF.toInt())
            } else {
                if (txtTimer?.visibility != View.GONE) {
                    txtTimer?.visibility = View.GONE
                }
            }
        } else {
            if (txtTimer?.visibility != View.GONE) {
                txtTimer?.visibility = View.GONE
            }
        }

        if (state != BubbleState.ENDED && activeCallCount > 1) {
            val countText = "$activeCallCount calls"
            if (badge?.text != countText) {
                badge?.text = countText
            }
            if (badge?.visibility != View.VISIBLE) {
                badge?.visibility = View.VISIBLE
            }
        } else {
            if (badge?.visibility != View.GONE) {
                badge?.visibility = View.GONE
            }
        }

        val targetEndVisibility = if (state.showEndButton) View.VISIBLE else View.INVISIBLE
        if (endBtn?.visibility != targetEndVisibility) {
            endBtn?.visibility = targetEndVisibility
        }
        endBtn?.isEnabled = state.showEndButton

        lastAppliedState = state
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchAndGestures(
        view: View,
        params: WindowManager.LayoutParams
    ) {
        val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isMoving = false

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val b = screenBounds()
                    if ((params.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.END) {
                        params.gravity = Gravity.TOP or Gravity.START
                        params.x = b.right - v.width - edgeMargin
                        try {
                            windowManager?.updateViewLayout(v, params)
                        } catch (_: Exception) {}
                    }
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isMoving = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        if (!isMoving) {
                            isMoving = true
                            chipView?.animateSafe(
                                100,
                                android.view.animation.DecelerateInterpolator(),
                                { scaleX(1.04f).scaleY(1.04f) }
                            )
                        }
                    }
                    if (isMoving) {
                        val b = screenBounds()
                        val currentW = if (v.width > 0) v.width else dp(220f)
                        val currentH = if (v.height > 0) v.height else dp(64f)
                        params.x = (initialX + dx).coerceIn(b.left, (b.right - currentW).coerceAtLeast(b.left))
                        params.y = (initialY + dy).coerceIn(b.top, (b.bottom - currentH).coerceAtLeast(b.top))
                        try {
                            windowManager?.updateViewLayout(v, params)
                        } catch (e: Exception) {
                            Log.e("$TAG Error updating layout on drag: $e")
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isMoving) {
                        if (isDismissing) return@setOnTouchListener true
                        Log.i("$TAG Bubble tapped -> launching real CallActivity as floating overlay window")
                        v.performClick()
                        onBubbleTapped(v.context)
                        chipView?.let { chip ->
                            chip.animateSafe(
                                70,
                                android.view.animation.DecelerateInterpolator(),
                                { scaleX(0.94f).scaleY(0.94f) },
                                {
                                    chip.animateSafe(
                                        90,
                                        android.view.animation.DecelerateInterpolator(),
                                        { scaleX(1f).scaleY(1f) }
                                    )
                                }
                            )
                        }
                    } else {
                        // Drag released -> Glide to nearest screen edge
                        val b = screenBounds()
                        val currentW = if (v.width > 0) v.width else dp(220f)
                        val isRight = (params.x + currentW / 2) > b.centerX()
                        dockedRight = isRight
                        lastDockedY = params.y

                        val targetX = if (isRight) (b.right - currentW - edgeMargin) else (b.left + edgeMargin)
                        val startX = params.x

                        val anim = ValueAnimator.ofInt(startX, targetX)
                        anim.duration = 220
                        anim.interpolator = android.view.animation.OvershootInterpolator(0.8f)
                        anim.addUpdateListener { va ->
                            if (v.isAttachedToWindow) {
                                params.x = va.animatedValue as Int
                                try {
                                    windowManager?.updateViewLayout(v, params)
                                } catch (_: Exception) {}
                            }
                        }
                        anim.addListener(object : AnimatorListenerAdapter() {
                            private var cancelled = false

                            override fun onAnimationCancel(a: Animator) {
                                cancelled = true
                            }

                            override fun onAnimationEnd(a: Animator) {
                                if (!cancelled && v.isAttachedToWindow) {
                                    params.gravity = if (isRight) (Gravity.TOP or Gravity.END) else (Gravity.TOP or Gravity.START)
                                    params.x = edgeMargin
                                    try {
                                        windowManager?.updateViewLayout(v, params)
                                    } catch (_: Exception) {}
                                }
                            }
                        })
                        anim.start()

                        chipView?.animateSafe(
                            120,
                            android.view.animation.DecelerateInterpolator(),
                            { scaleX(1.0f).scaleY(1.0f) }
                        )
                        v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (isMoving) {
                        chipView?.animateSafe(
                            120,
                            android.view.animation.DecelerateInterpolator(),
                            { scaleX(1.0f).scaleY(1.0f) }
                        )
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun setupClickListeners(view: View) {
        val endBtn = view.findViewById<View>(R.id.bubble_collapsed_quick_end)
        val endCircle = view.findViewById<View>(R.id.bubble_collapsed_quick_end_circle) ?: endBtn

        endBtn?.setOnClickListener {
            Log.i("$TAG [End] button clicked on bubble -> terminating call")
            endCircle?.animateSafe(
                60,
                android.view.animation.DecelerateInterpolator(),
                { scaleX(0.85f).scaleY(0.85f) },
                {
                    endCircle.animateSafe(
                        80,
                        android.view.animation.DecelerateInterpolator(),
                        { scaleX(1.0f).scaleY(1.0f) }
                    )
                }
            )
            terminateCurrentCall()
        }
        endBtn?.setOnLongClickListener {
            Log.i("$TAG [End] button long-clicked on bubble -> terminating ALL calls immediately")
            endCircle?.animateSafe(
                80,
                android.view.animation.DecelerateInterpolator(),
                { scaleX(0.8f).scaleY(0.8f) },
                {
                    endCircle.animateSafe(
                        80,
                        android.view.animation.DecelerateInterpolator(),
                        { scaleX(1.0f).scaleY(1.0f) }
                    )
                }
            )
            terminateAllCalls()
            true
        }
    }

    /**
     * Tapping the bubble directly launches Linphone's real CallActivity in a floating window.
     * Uses PendingIntent with background activity launch options for Android 14+ support!
     */
    fun onBubbleTapped(context: Context) {
        val launchContext = if (context is ContextThemeWrapper) context.baseContext else context
        val intent = LinphoneUtils.getCallActivityIntent(launchContext).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra("ActiveCall", true)
            putExtra("FromBubbleTap", true)
        }

        // 1) Direct start: the app owns a visible TYPE_APPLICATION_OVERLAY window (SYSTEM_ALERT_WINDOW granted),
        //    which is an allowed background-activity-start exemption on Android 10-16.
        val started = try {
            launchContext.startActivity(intent)
            Log.i("$TAG Direct startActivity succeeded for CallActivity")
            true
        } catch (e: Exception) {
            Log.e("$TAG startActivity failed: $e")
            false
        }
        if (started) return

        // 2) Fallback: PendingIntent with BAL options
        try {
            val options = org.linphone.compatibility.Compatibility.getPendingIntentActivityOptions(true)
            val pendingIntent = android.app.PendingIntent.getActivity(
                launchContext,
                1001,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                options.toBundle()
            )
            val senderOptions = org.linphone.compatibility.Compatibility.getPendingIntentActivityOptions(false)
            org.linphone.compatibility.Compatibility.sendPendingIntent(pendingIntent, senderOptions.toBundle())
            Log.i("$TAG PendingIntent sent successfully to launch CallActivity")
        } catch (e: Exception) {
            Log.e("$TAG PendingIntent launch failed: $e")
        }
    }

    private fun updateTimersOnly() {
        if (!isShowing || bubbleRootView == null || isDismissing || isCallActivityVisible) return
        coreContext.postOnCoreThread { core ->
            if (isDismissing) return@postOnCoreThread
            terminatingCalls.removeAll { LinphoneUtils.isCallEnding(it.state, considerReleasedAsEnding = true) }
            val activeCalls = core.calls.filter {
                it !in terminatingCalls && !LinphoneUtils.isCallEnding(it.state, considerReleasedAsEnding = true)
            }
            if (activeCalls.isEmpty()) {
                mainHandler.post {
                    if (!isDismissing) onAllCallsEnded()
                }
                return@postOnCoreThread
            }

            val currentCall = core.currentCall?.takeIf {
                it !in terminatingCalls && !LinphoneUtils.isCallEnding(it.state, considerReleasedAsEnding = true)
            } ?: activeCalls.firstOrNull() ?: return@postOnCoreThread

            val duration = currentCall.duration
            lastDurationSeconds = duration
            val state = stateOf(currentCall)
            val formatted = formatDuration(duration)

            mainHandler.post {
                if (isDismissing) return@post
                val root = bubbleRootView ?: return@post
                val txtTimer = root.findViewById<TextView>(R.id.bubble_collapsed_timer)
                if (state.showTimer) {
                    if (txtTimer?.text != formatted) {
                        txtTimer?.text = formatted
                    }
                    if (txtTimer?.visibility != View.VISIBLE) {
                        txtTimer?.visibility = View.VISIBLE
                    }
                } else {
                    if (txtTimer?.visibility != View.GONE) {
                        txtTimer?.visibility = View.GONE
                    }
                }
            }
        }
    }

    fun updateUI() {
        if (!isShowing || bubbleRootView == null || isDismissing) return
        coreContext.postOnCoreThread { core ->
            if (isDismissing) return@postOnCoreThread
            terminatingCalls.removeAll { LinphoneUtils.isCallEnding(it.state, considerReleasedAsEnding = true) }
            val activeCalls = core.calls.filter {
                it !in terminatingCalls && !LinphoneUtils.isCallEnding(it.state, considerReleasedAsEnding = true)
            }

            if (activeCalls.isEmpty()) {
                Log.i("$TAG No active calls remaining in updateUI, ending bubble")
                mainHandler.post {
                    if (!isDismissing) onAllCallsEnded()
                }
                return@postOnCoreThread
            }

            val currentCall = core.currentCall?.takeIf {
                it !in terminatingCalls && !LinphoneUtils.isCallEnding(it.state, considerReleasedAsEnding = true)
            } ?: activeCalls.firstOrNull() ?: return@postOnCoreThread

            val remoteAddress = runCatching { currentCall.callLog?.remoteAddress ?: currentCall.remoteAddress }.getOrNull()
            val displayName = (
                remoteAddress?.username?.takeIf { it.isNotEmpty() }
                ?: LinphoneUtils.getDisplayName(remoteAddress).takeIf { it.isNotEmpty() }
                ?: currentFallbackNumber ?: "Call"
            )
            lastKnownDisplayName = displayName

            val duration = currentCall.duration
            lastDurationSeconds = duration
            val state = stateOf(currentCall)

            mainHandler.post {
                if (isDismissing) return@post
                val root = bubbleRootView ?: return@post

                // Ensure bubble remains on-screen across rotations
                val lp = layoutParams
                if (lp != null && root.isAttachedToWindow) {
                    val b = screenBounds()
                    val maxH = if (root.height > 0) root.height else dp(64f)
                    val clampedY = lp.y.coerceIn(b.top, (b.bottom - maxH).coerceAtLeast(b.top))
                    if (clampedY != lp.y) {
                        lp.y = clampedY
                        lastDockedY = clampedY
                        try {
                            windowManager?.updateViewLayout(root, lp)
                        } catch (_: Exception) {}
                    }
                }

                applyBubbleState(
                    view = root,
                    state = state,
                    displayName = displayName,
                    durationSeconds = duration,
                    activeCallCount = activeCalls.size
                )
            }
        }
    }

    fun terminateAllCalls() {
        Log.i("$TAG Terminating ALL calls from floating bubble")
        org.linphone.ui.call.CallHandlerActivity.cancelAllPendingCalls()
        coreContext.postOnCoreThread { core ->
            for (call in core.calls) {
                try {
                    if (call.dir == Call.Dir.Incoming && LinphoneUtils.isCallIncoming(call.state)) {
                        call.decline(Reason.Declined)
                    } else {
                        call.terminate()
                    }
                } catch (e: Exception) {
                    Log.e("$TAG Error terminating call: $e")
                }
            }
            core.terminateAllCalls()
            terminatingCalls.clear()
            mainHandler.post {
                onAllCallsEnded()
            }
        }
    }

    private fun terminateCurrentCall() {
        coreContext.postOnCoreThread { core ->
            terminatingCalls.removeAll { LinphoneUtils.isCallEnding(it.state, considerReleasedAsEnding = true) }

            val activeCalls = core.calls.filter {
                it !in terminatingCalls && !LinphoneUtils.isCallEnding(it.state, considerReleasedAsEnding = true)
            }
            val call = core.currentCall?.takeIf {
                it !in terminatingCalls && !LinphoneUtils.isCallEnding(it.state, considerReleasedAsEnding = true)
            } ?: activeCalls.firstOrNull()

            if (call != null) {
                Log.i("$TAG Terminating active call [${call.remoteAddress.asStringUriOnly()}] from bubble")
                terminatingCalls.add(call)
                coreContext.terminateCall(call)

                val targetNumber = call.remoteAddress.username
                if (!targetNumber.isNullOrEmpty()) {
                    val duplicateLegs = activeCalls.filter {
                        it != call && it.remoteAddress.username == targetNumber
                    }
                    for (dup in duplicateLegs) {
                        Log.i("$TAG Terminating duplicate leg to same number [${dup.remoteAddress.asStringUriOnly()}]")
                        terminatingCalls.add(dup)
                        coreContext.terminateCall(dup)
                    }
                }
            } else {
                Log.i("$TAG No active call found to terminate, terminating all core calls")
                org.linphone.ui.call.CallHandlerActivity.cancelAllPendingCalls()
                for (c in core.calls) {
                    try {
                        if (c.dir == Call.Dir.Incoming && LinphoneUtils.isCallIncoming(c.state)) {
                            c.decline(Reason.Declined)
                        } else {
                            c.terminate()
                        }
                    } catch (_: Exception) {}
                }
                core.terminateAllCalls()
            }

            val remainingCalls = core.calls.filter {
                it !in terminatingCalls && !LinphoneUtils.isCallEnding(it.state, considerReleasedAsEnding = true)
            }

            if (remainingCalls.isEmpty()) {
                Log.i("$TAG No remaining active calls, terminating all core calls and ending bubble")
                org.linphone.ui.call.CallHandlerActivity.cancelAllPendingCalls()
                for (c in core.calls) {
                    try {
                        if (c.dir == Call.Dir.Incoming && LinphoneUtils.isCallIncoming(c.state)) {
                            c.decline(Reason.Declined)
                        } else {
                            c.terminate()
                        }
                    } catch (_: Exception) {}
                }
                core.terminateAllCalls()
                terminatingCalls.clear()
                mainHandler.post {
                    onAllCallsEnded()
                }
            } else {
                val nextCall = remainingCalls.first()
                Log.i(
                    "$TAG [${remainingCalls.size}] active call(s) remaining, switching bubble to next call [${nextCall.remoteAddress.asStringUriOnly()}]"
                )
                if (nextCall.state == Call.State.Paused) {
                    Log.i("$TAG Auto-resuming next call [${nextCall.remoteAddress.asStringUriOnly()}]")
                    nextCall.resume()
                }
                mainHandler.post {
                    reviveIfEnding()
                    if (isShowing && bubbleRootView != null && !isCallActivityVisible) {
                        bubbleRootView?.visibility = View.VISIBLE
                        updateUI()
                    }
                }
            }
        }
    }

    private fun startDurationTicker() {
        stopDurationTicker()
        tickerJob = scope.launch {
            while (isActive) {
                delay(1000)
                updateTimersOnly()
            }
        }
    }

    private fun stopDurationTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }
}
