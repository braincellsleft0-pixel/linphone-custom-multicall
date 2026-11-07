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

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
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
import kotlin.math.abs

/**
 * Manages the floating call bubble (Messenger Chat Head style).
 * Floats over the user's browser/CRM without taking over fullscreen.
 * Tapping the bubble directly launches the real Linphone CallActivity
 * in a floating/overlay window (no custom UI!).
 */
object FloatingBubbleManager {
    private const val TAG = "[Floating Bubble Manager]"

    var isShowing: Boolean = false
        private set

    var isCallActivityVisible: Boolean = false
        private set

    private var windowManager: WindowManager? = null
    private var bubbleRootView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var tickerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentFallbackNumber: String? = null

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

    private var lastKnownDisplayName: String = "Call"

    @Volatile
    private var isInitialized = false

    @Volatile
    private var isDismissing = false

    private val terminatingCalls = java.util.Collections.synchronizedSet(java.util.HashSet<Call>())

    private var dismissRunnable: Runnable? = null

    private fun cancelPendingDismiss() {
        dismissRunnable?.let {
            mainHandler.removeCallbacks(it)
            dismissRunnable = null
        }
        isDismissing = false
        bubbleRootView?.let { v ->
            v.animate().cancel()
            v.alpha = 1f
            v.scaleX = 1f
            v.scaleY = 1f
            v.translationY = 0f
            val endBtn = v.findViewById<View>(R.id.bubble_collapsed_quick_end)
            endBtn?.visibility = View.VISIBLE
        }
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
        val statusDot = view.findViewById<View>(R.id.bubble_collapsed_status_dot)
        val endBtn = view.findViewById<View>(R.id.bubble_collapsed_quick_end)

        val name = displayName ?: lastKnownDisplayName
        if (txtCaller?.text != name) {
            txtCaller?.text = name
        }

        if (txtStatus?.text != state.label) {
            txtStatus?.text = state.label
        }
        txtStatus?.setTextColor(state.statusColor)

        statusDot?.background?.mutate()?.setTint(state.dotColor)

        if (state.showTimer) {
            val mins = durationSeconds / 60
            val secs = durationSeconds % 60
            val durationFormatted = String.format("%02d:%02d", mins, secs)
            if (txtTimer?.text != durationFormatted) {
                txtTimer?.text = durationFormatted
            }
            if (txtTimer?.visibility != View.VISIBLE) {
                txtTimer?.visibility = View.VISIBLE
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

        val targetEndVisibility = if (state.showEndButton) View.VISIBLE else View.GONE
        if (endBtn?.visibility != targetEndVisibility) {
            endBtn?.visibility = targetEndVisibility
        }
    }

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
                    Log.i("$TAG No active calls remaining, showing call ended and dismissing bubble")
                    showCallEndedAndDismiss()
                } else {
                    cancelPendingDismiss()
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
            bubbleRootView?.let { v ->
                v.animate()
                    .alpha(0f)
                    .scaleX(0.75f)
                    .scaleY(0.75f)
                    .setDuration(120)
                    .withEndAction {
                        hideTemporarily()
                        v.alpha = 1f
                        v.scaleX = 1f
                        v.scaleY = 1f
                    }
                    .start()
            }
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
                    cancelPendingDismiss()
                    if (isShowing && bubbleRootView != null) {
                        bubbleRootView?.let { v ->
                            v.visibility = View.VISIBLE
                            val density = coreContext.context.resources.displayMetrics.density
                            v.pivotX = if (v.width > 0) v.width / 2f else (110f * density)
                            v.pivotY = if (v.height > 0) v.height / 2f else (21f * density)
                            v.alpha = 0f
                            v.scaleX = 0.5f
                            v.scaleY = 0.5f
                            v.translationY = -15f
                            v.animate()
                                .alpha(1f)
                                .scaleX(1f)
                                .scaleY(1f)
                                .translationY(0f)
                                .setDuration(220)
                                .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
                                .start()
                        }
                        startDurationTicker()
                        updateUI()
                    } else {
                        show()
                    }
                } else {
                    dismiss()
                }
            }
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

        cancelPendingDismiss()
        isDismissing = false

        if (isShowing && bubbleRootView != null) {
            val v = bubbleRootView
            if (v != null && v.visibility == View.VISIBLE && v.alpha == 1f) {
                // Bubble is ALREADY showing and fully visible on screen!
                // DO NOT re-animate or bounce on repeated tel: clicks!
                // Just update the UI (number and call count badge) smoothly!
                updateUI()
                return
            }
            if (v != null) {
                v.visibility = View.VISIBLE
                val density = context.resources.displayMetrics.density
                v.pivotX = if (v.width > 0) v.width / 2f else (110f * density)
                v.pivotY = if (v.height > 0) v.height / 2f else (21f * density)
                v.alpha = 0f
                v.scaleX = 0.5f
                v.scaleY = 0.5f
                v.translationY = -15f
                v.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationY(0f)
                    .setDuration(220)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
                    .start()
            }
            updateUI()
            return
        }

        try {
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val themedContext = ContextThemeWrapper(context, R.style.Theme_Linphone)
            val inflater = LayoutInflater.from(themedContext)
            val view = inflater.inflate(R.layout.floating_call_bubble, null)
            bubbleRootView = view

            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val metrics = context.resources.displayMetrics
            val screenWidth = metrics.widthPixels
            val screenHeight = metrics.heightPixels

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (screenWidth - (220 * metrics.density).toInt()).coerceAtLeast(16)
                y = (screenHeight * 0.18).toInt()
            }
            layoutParams = params

            setupTouchAndGestures(view, params, screenWidth, screenHeight)
            setupClickListeners(view)

            // Populate UI BEFORE adding to WindowManager so dimensions measure accurately!
            updateUI()

            view.alpha = 0f
            view.scaleX = 0.5f
            view.scaleY = 0.5f
            view.translationY = -15f
            windowManager?.addView(view, params)
            isShowing = true

            view.post {
                view.pivotX = view.width / 2f
                view.pivotY = view.height / 2f
                view.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationY(0f)
                    .setDuration(240)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
                    .start()
            }

            startDurationTicker()
            Log.i("$TAG Floating call bubble successfully displayed on screen")
        } catch (e: Exception) {
            Log.e("$TAG Failed to add floating bubble to WindowManager: $e", e)
        }
    }

    @UiThread
    fun dismiss() {
        isDismissing = true
        cancelPendingDismiss()
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
                } catch (e: Exception) {}
            }
            core.terminateAllCalls()
            terminatingCalls.clear()
        }
        val viewToRemove = bubbleRootView
        bubbleRootView = null
        isShowing = false
        currentFallbackNumber = null

        if (viewToRemove != null) {
            try {
                viewToRemove.animate().cancel()
                if (viewToRemove.alpha <= 0.05f) {
                    try {
                        windowManager?.removeView(viewToRemove)
                    } catch (e: Exception) {}
                    isDismissing = false
                    return
                }
                val density = coreContext.context.resources.displayMetrics.density
                viewToRemove.pivotX = if (viewToRemove.width > 0) viewToRemove.width / 2f else (110f * density)
                viewToRemove.pivotY = if (viewToRemove.height > 0) viewToRemove.height / 2f else (21f * density)
                viewToRemove.animate()
                    .alpha(0f)
                    .scaleX(0.88f)
                    .scaleY(0.88f)
                    .translationY(8f)
                    .setDuration(160)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .withEndAction {
                        try {
                            windowManager?.removeView(viewToRemove)
                        } catch (e: Exception) {
                            Log.e("$TAG Failed to remove bubble view: $e")
                        }
                        isDismissing = false
                    }
                    .start()
            } catch (e: Exception) {
                try {
                    windowManager?.removeView(viewToRemove)
                } catch (e2: Exception) {}
                isDismissing = false
            }
        } else {
            isDismissing = false
        }
        Log.i("$TAG Floating call bubble dismissed")
    }

    @UiThread
    private fun hideTemporarily() {
        bubbleRootView?.visibility = View.GONE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchAndGestures(
        view: View,
        params: WindowManager.LayoutParams,
        screenWidth: Int,
        screenHeight: Int
    ) {
        val quickEndBtn = view.findViewById<View>(R.id.bubble_collapsed_quick_end)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isMoving = false
        val touchSlop = 16

        view.setOnTouchListener { v, event ->
            val rawX = event.rawX.toInt()
            val rawY = event.rawY.toInt()

            if (quickEndBtn != null) {
                val quickEndRect = Rect()
                quickEndBtn.getGlobalVisibleRect(quickEndRect)
                quickEndRect.inset(-10, -10)
                if (quickEndRect.contains(rawX, rawY)) {
                    return@setOnTouchListener false
                }
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
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
                            v.animate().scaleX(1.03f).scaleY(1.03f).setDuration(100).start()
                        }
                    }
                    if (isMoving) {
                        val currentW = if (v.width > 0) v.width else 200
                        val currentH = if (v.height > 0) v.height else 60
                        params.x = (initialX + dx).coerceIn(0, (screenWidth - currentW).coerceAtLeast(0))
                        params.y = (initialY + dy).coerceIn(30, (screenHeight - currentH - 50).coerceAtLeast(30))
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
                        if (isDismissing) {
                            return@setOnTouchListener true
                        }
                        // Tapped bubble -> Tactile spring response + launch CallActivity smoothly
                        Log.i("$TAG Bubble tapped -> launching real CallActivity as floating overlay window")
                        v.pivotX = v.width / 2f
                        v.pivotY = v.height / 2f
                        v.animate()
                            .scaleX(0.92f)
                            .scaleY(0.92f)
                            .setDuration(70)
                            .withEndAction {
                                v.animate()
                                    .scaleX(1.0f)
                                    .scaleY(1.0f)
                                    .setDuration(90)
                                    .start()
                                onBubbleTapped(v.context)
                            }
                            .start()
                    } else {
                        // Drag released -> restore scale + Snap to left or right screen edge
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                        val currentW = if (v.width > 0) v.width else 200
                        val midPoint = screenWidth / 2
                        params.x = if (params.x + currentW / 2 < midPoint) {
                            16
                        } else {
                            (screenWidth - currentW - 16).coerceAtLeast(16)
                        }
                        try {
                            windowManager?.updateViewLayout(v, params)
                        } catch (e: Exception) {
                            Log.e("$TAG Error updating layout on snap: $e")
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (isMoving) {
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                    }
                    false
                }
                else -> false
            }
        }
    }

    private fun setupClickListeners(view: View) {
        val endBtn = view.findViewById<View>(R.id.bubble_collapsed_quick_end)
        endBtn?.setOnClickListener {
            Log.i("$TAG [End] button clicked on bubble -> terminating call")
            endBtn.animate().scaleX(0.85f).scaleY(0.85f).setDuration(60).withEndAction {
                endBtn.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start()
                terminateCurrentCall()
            }.start()
        }
        endBtn?.setOnLongClickListener {
            Log.i("$TAG [End] button long-clicked on bubble -> terminating ALL calls immediately")
            endBtn.animate().scaleX(0.8f).scaleY(0.8f).setDuration(80).withEndAction {
                endBtn.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start()
                terminateAllCalls()
            }.start()
            true
        }
    }

    /**
     * Tapping the bubble directly launches Linphone's real CallActivity in a floating window.
     * Uses PendingIntent with background activity launch options for Android 14+ support!
     */
    fun onBubbleTapped(context: Context) {
        Log.i("$TAG Bubble tapped → launching real CallActivity via PendingIntent")

        val intent = LinphoneUtils.getCallActivityIntent(context).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra("ActiveCall", true)
            putExtra("FromBubbleTap", true)
        }

        try {
            val options = org.linphone.compatibility.Compatibility.getPendingIntentActivityOptions(true)
            val pendingIntent = android.app.PendingIntent.getActivity(
                context,
                1001,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                options.toBundle()
            )
            val senderOptions = org.linphone.compatibility.Compatibility.getPendingIntentActivityOptions(false)
            org.linphone.compatibility.Compatibility.sendPendingIntent(pendingIntent, senderOptions.toBundle())
            Log.i("$TAG PendingIntent sent successfully to launch CallActivity")
        } catch (e: Exception) {
            Log.e("$TAG Failed to launch CallActivity via PendingIntent: $e, trying direct startActivity")
            try {
                context.startActivity(intent)
            } catch (e2: Exception) {
                Log.e("$TAG Direct startActivity also failed: $e2")
            }
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
                    if (!isDismissing) showCallEndedAndDismiss()
                }
                return@postOnCoreThread
            }

            val currentCall = core.currentCall?.takeIf {
                it !in terminatingCalls && !LinphoneUtils.isCallEnding(it.state, considerReleasedAsEnding = true)
            } ?: activeCalls.firstOrNull() ?: return@postOnCoreThread
            val duration = currentCall.duration
            val mins = duration / 60
            val secs = duration % 60
            val durationFormatted = String.format("%02d:%02d", mins, secs)

            val state = when (currentCall.state) {
                Call.State.Connected, Call.State.StreamsRunning -> BubbleState.CONNECTED
                Call.State.Paused, Call.State.PausedByRemote, Call.State.Pausing -> BubbleState.HOLD
                Call.State.OutgoingRinging, Call.State.OutgoingEarlyMedia -> BubbleState.RINGING
                Call.State.OutgoingInit, Call.State.OutgoingProgress -> BubbleState.CALLING
                else -> BubbleState.CONNECTED
            }

            mainHandler.post {
                if (isDismissing) return@post
                val view = bubbleRootView ?: return@post
                val txtTimer = view.findViewById<TextView>(R.id.bubble_collapsed_timer)
                if (state.showTimer) {
                    if (txtTimer?.text != durationFormatted) {
                        txtTimer?.text = durationFormatted
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
                Log.i("$TAG No active calls remaining in updateUI, dismissing bubble")
                mainHandler.post {
                    if (!isDismissing) showCallEndedAndDismiss()
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
            val state = when (currentCall.state) {
                Call.State.Connected, Call.State.StreamsRunning -> BubbleState.CONNECTED
                Call.State.Paused, Call.State.PausedByRemote, Call.State.Pausing -> BubbleState.HOLD
                Call.State.OutgoingRinging, Call.State.OutgoingEarlyMedia -> BubbleState.RINGING
                Call.State.OutgoingInit, Call.State.OutgoingProgress -> BubbleState.CALLING
                else -> BubbleState.CONNECTED
            }

            mainHandler.post {
                if (isDismissing) return@post
                val view = bubbleRootView ?: return@post
                applyBubbleState(
                    view = view,
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
                showCallEndedAndDismiss()
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

                // Terminate any duplicate legs to the exact same username/number
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
                    } catch (e: Exception) {}
                }
                core.terminateAllCalls()
            }

            val remainingCalls = core.calls.filter {
                it !in terminatingCalls && !LinphoneUtils.isCallEnding(it.state, considerReleasedAsEnding = true)
            }

            if (remainingCalls.isEmpty()) {
                Log.i("$TAG No remaining active calls, terminating all core calls and dismissing bubble")
                org.linphone.ui.call.CallHandlerActivity.cancelAllPendingCalls()
                for (c in core.calls) {
                    try {
                        if (c.dir == Call.Dir.Incoming && LinphoneUtils.isCallIncoming(c.state)) {
                            c.decline(Reason.Declined)
                        } else {
                            c.terminate()
                        }
                    } catch (e: Exception) {}
                }
                core.terminateAllCalls()
                terminatingCalls.clear()
                mainHandler.post {
                    showCallEndedAndDismiss()
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
                    cancelPendingDismiss()
                    if (isShowing && bubbleRootView != null) {
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

    private fun showCallEndedAndDismiss() {
        if (isDismissing) return
        isDismissing = true
        cancelPendingDismiss()
        isDismissing = true
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
                } catch (e: Exception) {}
            }
            core.terminateAllCalls()
            terminatingCalls.clear()
        }
        val view = bubbleRootView
        if (view != null && isShowing) {
            applyBubbleState(
                view = view,
                state = BubbleState.ENDED,
                displayName = lastKnownDisplayName,
                durationSeconds = 0,
                activeCallCount = 0
            )

            val runnable = Runnable {
                view.animate()
                    .alpha(0f)
                    .scaleX(0.8f)
                    .scaleY(0.8f)
                    .setDuration(220)
                    .withLayer()
                    .withEndAction {
                        dismiss()
                        dismissRunnable = null
                    }
                    .start()
            }
            dismissRunnable = runnable
            mainHandler.postDelayed(runnable, 1250)
        } else {
            dismiss()
        }
    }
}
