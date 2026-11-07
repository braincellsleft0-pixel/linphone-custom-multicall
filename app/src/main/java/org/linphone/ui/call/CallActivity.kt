/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
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
package org.linphone.ui.call

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.Menu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.UiThread
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.compatibility.Compatibility
import org.linphone.core.tools.Log
import org.linphone.databinding.CallActivityBinding
import org.linphone.telecom.RedirectionHandler
import org.linphone.ui.GenericActivity
import org.linphone.ui.call.conference.fragment.ActiveConferenceCallFragmentDirections
import org.linphone.ui.call.conference.fragment.ConferenceLayoutMenuDialogFragment
import org.linphone.ui.call.fragment.ActiveCallFragmentDirections
import org.linphone.ui.call.fragment.AudioDevicesMenuDialogFragment
import org.linphone.ui.call.fragment.CallsListFragmentDirections
import org.linphone.ui.call.fragment.IncomingCallFragmentDirections
import org.linphone.ui.call.fragment.OutgoingCallFragmentDirections
import org.linphone.ui.call.model.AudioDeviceModel
import org.linphone.ui.call.viewmodel.CallsViewModel
import org.linphone.utils.LinphoneUtils
import org.linphone.ui.call.viewmodel.CurrentCallViewModel
import org.linphone.ui.call.viewmodel.SharedCallViewModel
import org.linphone.utils.AppUtils

@UiThread
class CallActivity : GenericActivity() {
    companion object {
        private const val TAG = "[Call Activity]"
    }

    private lateinit var binding: CallActivityBinding

    private lateinit var sharedViewModel: SharedCallViewModel
    private lateinit var callsViewModel: CallsViewModel
    private lateinit var callViewModel: CurrentCallViewModel

    private var bottomSheetDialog: BottomSheetDialogFragment? = null

    private var isPipSupported = false

    private var keepActivityAliveEvenIfNoCall = false

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i("$TAG CAMERA permission has been granted, enabling video")
            callViewModel.toggleVideo()
        } else {
            Log.e("$TAG CAMERA permission has been denied")
        }
    }

    private val requestRecordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i("$TAG RECORD_AUDIO permission has been granted, un-muting microphone")
            callViewModel.toggleMuteMicrophone()
        } else {
            Log.e("$TAG RECORD_AUDIO permission has been denied")
        }
    }

    override fun getTheme(): Resources.Theme {
        val mainColor = corePreferences.themeMainColor
        val theme = super.getTheme()
        when (mainColor) {
            "terracotta" -> theme.applyStyle(R.style.Theme_LinphoneInCallTerracotta, true)
            "lavender" -> theme.applyStyle(R.style.Theme_LinphoneInCallLavender, true)
            "honey" -> theme.applyStyle(R.style.Theme_LinphoneInCallHoney, true)
            "burgundy" -> theme.applyStyle(R.style.Theme_LinphoneInCallBurgundy, true)
            "mint" -> theme.applyStyle(R.style.Theme_LinphoneInCallMint, true)
            "coral" -> theme.applyStyle(R.style.Theme_LinphoneInCallCoral, true)
            "plum" -> theme.applyStyle(R.style.Theme_LinphoneInCallPlum, true)
            "titanium" -> theme.applyStyle(R.style.Theme_LinphoneInCallTitanium, true)
            "mineral_blue" -> theme.applyStyle(R.style.Theme_LinphoneInCallMineralBlue, true)
            else -> theme.applyStyle(R.style.Theme_LinphoneInCall, true)
        }
        return theme
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val fromBubbleTap = intent.getBooleanExtra("FromBubbleTap", false)
        val isExplicitTap = fromBubbleTap || intent.getBooleanExtra("ActiveCall", false) || intent.hasExtra("IncomingCall")

        if (!isExplicitTap && org.linphone.ui.call.bubble.FloatingBubbleManager.canDrawOverlays(this)) {
            Log.i(TAG, "Call started in background, showing bubble and staying out of the way")
            org.linphone.ui.call.bubble.FloatingBubbleManager.show()
            finish()
            overridePendingTransition(0, 0)
            return
        }

        binding = DataBindingUtil.setContentView(this, R.layout.call_activity)
        binding.lifecycleOwner = this
        setUpToastsArea(binding.toastsArea)

        // Configure compact floating overlay window dimensions & smooth animations (like Messenger!)
        if (org.linphone.ui.call.bubble.FloatingBubbleManager.canDrawOverlays(this)) {
            configureFloatingWindow()
        }

        binding.otherCallsTopBar.btnMinimizeTopBar.setOnClickListener {
            Log.i("$TAG Minimize chevron tapped, closing floating window smoothly")
            minimizeFloatingWindow()
        }

        if (!org.linphone.ui.call.bubble.FloatingBubbleManager.canDrawOverlays(this)) {
            ViewCompat.setOnApplyWindowInsetsListener(binding.otherCallsTopBar.root) { v, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.updatePadding(0, insets.top, 0, 0)
                windowInsets
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.callNavContainer) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val keyboard = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            if (org.linphone.ui.call.bubble.FloatingBubbleManager.canDrawOverlays(this)) {
                v.updatePadding(0, 0, 0, 0)
            } else {
                v.updatePadding(insets.left, 0, insets.right, max(insets.bottom, keyboard.bottom))
            }
            WindowInsetsCompat.CONSUMED
        }

        lifecycleScope.launch(Dispatchers.Main) {
            WindowInfoTracker
                .getOrCreate(this@CallActivity)
                .windowLayoutInfo(this@CallActivity)
                .collect { newLayoutInfo ->
                    updateCurrentLayout(newLayoutInfo)
                }
        }

        isPipSupported = packageManager.hasSystemFeature(
            PackageManager.FEATURE_PICTURE_IN_PICTURE
        )
        Log.i("$TAG Is PiP supported [$isPipSupported]")

        sharedViewModel = run {
            ViewModelProvider(this)[SharedCallViewModel::class.java]
        }

        callViewModel = run {
            ViewModelProvider(this)[CurrentCallViewModel::class.java]
        }
        binding.callViewModel = callViewModel

        callsViewModel = run {
            ViewModelProvider(this)[CallsViewModel::class.java]
        }
        binding.callsViewModel = callsViewModel

        callViewModel.showAudioDevicesListEvent.observe(this) {
            it.consume { devices ->
                showAudioRoutesMenu(devices)
            }
        }

        callViewModel.conferenceModel.showLayoutMenuEvent.observe(this) {
            it.consume {
                showConferenceLayoutMenu()
            }
        }

        callViewModel.isVideoEnabled.observe(this) { enabled ->
            if (isPipSupported) {
                // Only enable PiP if video is enabled
                Compatibility.enableAutoEnterPiP(this, enabled)
            }
        }

        callViewModel.transferInProgressEvent.observe(this) {
            it.consume {
                showGreenToast(
                    getString(R.string.call_transfer_in_progress_toast),
                    R.drawable.phone_transfer
                )
            }
        }

        callViewModel.transferFailedEvent.observe(this) {
            it.consume {
                showRedToast(
                    getString(R.string.call_transfer_failed_toast),
                    R.drawable.warning_circle
                )
            }
        }

        callViewModel.goToEndedCallEvent.observe(this) {
            it.consume { message ->
                if (message.isNotEmpty()) {
                    showRedToast(message, R.drawable.warning_circle)
                }

                val action = ActiveCallFragmentDirections.actionGlobalEndedCallFragment()
                findNavController(R.id.call_nav_container).navigate(action)
            }
        }

        callViewModel.finishActivityEvent.observe(this) {
            it.consume {
                Log.i("$TAG Finishing activity")
                finish()
            }
        }

        callViewModel.requestRecordAudioPermission.observe(this) {
            it.consume {
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
                    Log.w("$TAG Asking for RECORD_AUDIO permission")
                    requestRecordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    Log.i("$TAG Permission request for RECORD_AUDIO will be automatically denied, go to android app settings instead")
                    goToAndroidPermissionSettings()
                }
            }
        }

        callViewModel.requestCameraPermission.observe(this) {
            it.consume {
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
                    Log.w("$TAG Asking for CAMERA permission")
                    requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                } else {
                    Log.i("$TAG Permission request for CAMERA will be automatically denied, go to android app settings instead")
                    goToAndroidPermissionSettings()
                }
            }
        }

        callViewModel.proximitySensorEnabled.observe(this) { enabled ->
            Log.i("$TAG ${if (enabled) "Enabling" else "Disabling"} proximity sensor")
            coreContext.enableProximitySensor(enabled)
        }

        callViewModel.goToCallEvent.observe(this) {
            it.consume {
                navigateToActiveCall(true)
            }
        }

        callViewModel.goToOutgoingCallEvent.observe(this) {
            it.consume {
                val navController = findNavController(R.id.call_nav_container)
                if (navController.currentDestination?.id != R.id.outgoingCallFragment) {
                    val action = OutgoingCallFragmentDirections.actionGlobalOutgoingCallFragment()
                    navController.navigate(action)
                }
            }
        }

        callViewModel.goToConferenceEvent.observe(this) {
            it.consume {
                navigateToActiveCall(false)
            }
        }

        callsViewModel.showIncomingCallEvent.observe(this) {
            it.consume {
                val action = IncomingCallFragmentDirections.actionGlobalIncomingCallFragment()
                findNavController(R.id.call_nav_container).navigate(action)
            }
        }

        callsViewModel.showOutgoingCallEvent.observe(this) {
            it.consume {
                val navController = findNavController(R.id.call_nav_container)
                val currentDest = navController.currentDestination?.id
                if (currentDest == R.id.outgoingCallFragment) {
                    CallsViewModel.focusedCall?.let { newCall ->
                        coreContext.postOnCoreThread {
                            callViewModel.configureCall(newCall)
                        }
                    }
                } else {
                    val action = OutgoingCallFragmentDirections.actionGlobalOutgoingCallFragment()
                    navController.navigate(action)
                }
            }
        }

        callsViewModel.goToActiveCallEvent.observe(this) {
            it.consume { singleCall ->
                CallsViewModel.focusedCall?.let { target ->
                    coreContext.postOnCoreThread {
                        callViewModel.configureCall(target)
                    }
                }
                navigateToActiveCall(singleCall)
            }
        }

        callsViewModel.noCallFoundEvent.observe(this) {
            it.consume {
                if (keepActivityAliveEvenIfNoCall) {
                    Log.w("$TAG No call was found but we were asked to keep Activity alive, doing nothing")
                } else {
                    Log.w("$TAG No call was found, finishing Activity")
                    finish()
                }
            }
        }

        callsViewModel.goToCallsListEvent.observe(this) {
            it.consume {
                val navController = findNavController(R.id.call_nav_container)
                if (navController.currentDestination?.id == R.id.activeCallFragment) {
                    val action =
                        ActiveCallFragmentDirections.actionActiveCallFragmentToCallsListFragment()
                    navController.navigate(action)
                } else if (navController.currentDestination?.id == R.id.activeConferenceCallFragment) {
                    val action =
                        ActiveConferenceCallFragmentDirections.actionActiveConferenceCallFragmentToCallsListFragment()
                    navController.navigate(action)
                } else if (navController.currentDestination?.id == R.id.outgoingCallFragment) {
                    val action =
                        OutgoingCallFragmentDirections.actionOutgoingCallFragmentToCallsListFragment()
                    navController.navigate(action)
                }
            }
        }

        sharedViewModel.toggleFullScreenEvent.observe(this) {
            it.consume { hide ->
                hideUI(hide)
            }
        }

        coreContext.refreshMicrophoneMuteStateEvent.observe(this) {
            it.consume {
                Log.i(
                    "$TAG Refreshing microphone mute state, probably to sync with Android Auto action"
                )
                callViewModel.refreshMicrophoneState()
            }
        }

        if (intent.extras?.getBoolean("AnswerIncomingCall", false) == true) {
            val caller = intent.extras?.getString("Caller")
            Log.i("$TAG Answering incoming call from [$caller]")
            callViewModel.answer()
        } else if (intent.extras?.getBoolean("DoCallRedirection", false) == true) {
            Log.i("$TAG User allowed GSM call redirection through Linphone, doing it")
            keepActivityAliveEvenIfNoCall = true
            RedirectionHandler.useLinphone = true
            RedirectionHandler.responseLatch?.countDown()
        }
    }

    override fun onStart() {
        super.onStart()

        findNavController(R.id.call_nav_container).addOnDestinationChangedListener { _, _, _ ->
            callsViewModel.showTopBar.postValue(false)
        }

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("$TAG RECORD_AUDIO permission isn't granted")
            val message = R.string.call_audio_record_permission_not_granted_toast
            val icon = R.drawable.warning_circle
            showRedToast(getString(message), icon)
        }

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("$TAG CAMERA permission isn't granted")
            val message = R.string.call_camera_permission_not_granted_toast
            val icon = R.drawable.warning_circle
            showRedToast(getString(message), icon)
        }
    }

    override fun onResume() {
        super.onResume()
        org.linphone.ui.call.bubble.FloatingBubbleManager.onCallActivityResumed(this)

        val isInPipMode = isInPictureInPictureMode
        Log.i("$TAG onResume: is in PiP mode? [$isInPipMode]")
        if (::callViewModel.isInitialized) {
            callViewModel.pipMode.value = isInPipMode
        }

        if (callsViewModel.callsCount.value == 0) {
            coreContext.postOnCoreThread { core ->
                val hasActiveCalls = core.calls.any {
                    !LinphoneUtils.isCallEnding(it.state, considerReleasedAsEnding = true)
                }
                if (!hasActiveCalls) {
                    runOnUiThread {
                        if (callsViewModel.callsCount.value == 0) {
                            Log.w("$TAG Call activity is being resumed but no call was found, finishing activity")
                            finish()
                        }
                    }
                }
            }
        }

        coreContext.postOnCoreThread {
            coreContext.notificationsManager.showInCallForegroundServiceNotificationIfNeeded()
            callViewModel.updateProximitySensor()
        }
    }

    override fun onPause() {
        coreContext.enableProximitySensor(false)
        val isInPip = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            isInPictureInPictureMode
        } else {
            false
        }
        if (!isInPip) {
            org.linphone.ui.call.bubble.FloatingBubbleManager.onCallActivityPaused(this)
        }

        super.onPause()

        bottomSheetDialog?.dismiss()
        bottomSheetDialog = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (org.linphone.ui.call.bubble.FloatingBubbleManager.canDrawOverlays(this)) {
            minimizeFloatingWindow()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        org.linphone.ui.call.bubble.FloatingBubbleManager.clearCallActivity(this)
        coreContext.enableProximitySensor(false)

        super.onDestroy()

        coreContext.postOnCoreThread { core ->
            Log.i("$TAG Clearing native video window ID")
            core.nativeVideoWindowId = null
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        if (org.linphone.ui.call.bubble.FloatingBubbleManager.canDrawOverlays(this)) {
            configureFloatingWindow()
        }

        val uri = intent.data?.toString()
        if (!uri.isNullOrEmpty() && (uri.startsWith("tel:") || uri.startsWith("sip:") || uri.startsWith("callto:"))) {
            Log.i("$TAG CallActivity received direct call intent with URI [$uri]")
            val sipUriToCall = when {
                uri.startsWith("tel:") -> uri.substring("tel:".length)
                uri.startsWith("callto:") -> uri.substring("callto:".length)
                uri.startsWith("sip-linphone:") -> uri.replace("sip-linphone:", "sip:")
                uri.startsWith("linphone-sip:") -> uri.replace("linphone-sip:", "sip:")
                uri.startsWith("sips-linphone:") -> uri.replace("sips-linphone:", "sips:")
                uri.startsWith("linphone-sips:") -> uri.replace("linphone-sips:", "sips:")
                else -> uri.replace("%40", "@")
            }
            coreContext.postOnCoreThread { core ->
                val address = core.interpretUrl(
                    sipUriToCall,
                    LinphoneUtils.applyInternationalPrefix()
                )
                if (address != null) {
                    coreContext.startAudioCall(address, skipNetworkReachabilityTest = true)
                }
            }
        }

        if (intent.extras?.getBoolean("AnswerIncomingCall", false) == true) {
            val caller = intent.extras?.getString("Caller")
            Log.i("$TAG Answering incoming call from [$caller]")
            callViewModel.answer()
        } else if (intent.extras?.getBoolean("ActiveCall", false) == true) {
            navigateToActiveCall(
                callViewModel.conferenceModel.isCurrentCallInConference.value == false
            )
        } else if (intent.extras?.getBoolean("IncomingCall", false) == true) {
            val action = IncomingCallFragmentDirections.actionGlobalIncomingCallFragment()
            findNavController(R.id.call_nav_container).navigate(action)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()

        if (::callViewModel.isInitialized) {
            if (isPipSupported) {
                Log.i("$TAG User leave hint, try entering PiP mode")
                val pipMode = Compatibility.enterPipMode(this)
                if (!pipMode) {
                    callViewModel.pipMode.value = false
                }
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        Log.i("$TAG onPictureInPictureModeChanged: [$isInPictureInPictureMode]")
        if (::callViewModel.isInitialized) {
            callViewModel.pipMode.value = isInPictureInPictureMode
        }
        if (isInPictureInPictureMode) {
            org.linphone.ui.call.bubble.FloatingBubbleManager.onCallActivityResumed()
        } else {
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                org.linphone.ui.call.bubble.FloatingBubbleManager.onCallActivityResumed()
            } else {
                org.linphone.ui.call.bubble.FloatingBubbleManager.onCallActivityPaused()
            }
        }
    }

    override fun onProvideKeyboardShortcuts(
        data: MutableList<KeyboardShortcutGroup?>?,
        menu: Menu?,
        deviceId: Int
    ) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)

        val keyboardShortcutGroup = KeyboardShortcutGroup(
            "Answer/Decline incoming call",
            listOf(
                KeyboardShortcutInfo(
                    AppUtils.getString(R.string.call_action_answer),
                    KeyEvent.KEYCODE_A,
                    KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON
                ),
                KeyboardShortcutInfo(
                    AppUtils.getString(R.string.call_action_decline),
                    KeyEvent.KEYCODE_D,
                    KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON
                )
            )
        )
        data?.add(keyboardShortcutGroup)
        Log.i("$TAG Incoming call answer/decline shortcuts added")
    }

    override fun onKeyShortcut(keyCode: Int, event: KeyEvent?): Boolean {
        if (event?.isCtrlPressed == true && event.isShiftPressed) {
            val navController = findNavController(R.id.call_nav_container)
            if (navController.currentDestination?.id == R.id.incomingCallFragment) {
                when (keyCode) {
                    KeyEvent.KEYCODE_A -> {
                        Log.i("$TAG Answer incoming call shortcut triggered")
                        callViewModel.answer()
                    }
                    KeyEvent.KEYCODE_D -> {
                        Log.i("$TAG Decline incoming call shortcut triggered")
                        callViewModel.hangUp()
                    }
                }
            }
        }
        return true
    }

    fun goToMainActivity() {
        Log.i("$TAG User requested minimize / return, finishing CallActivity so Chrome stays in front")
        if (org.linphone.ui.call.bubble.FloatingBubbleManager.canDrawOverlays(this)) {
            minimizeFloatingWindow()
        } else {
            finish()
        }
    }

    /**
     * The window itself is full screen & transparent (a "scrim"), the visible card is sized in code.
     * - tapping the transparent area around the card collapses to the bubble (reliable on every Android version,
     *   unlike FLAG_WATCH_OUTSIDE_TOUCH / setFinishOnTouchOutside which depend on the theme)
     * - window never resizes after creation, only the card is animated, with a hardware layer
     *   so the heavy call UI is not redrawn on every animation frame.
     * NOTE: Theme.LinphoneInCall must be translucent (windowIsTranslucent=true, transparent windowBackground).
     */
    private fun configureFloatingWindow() {
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
        setFinishOnTouchOutside(false)

        val metrics = resources.displayMetrics
        val cardWidth = (metrics.widthPixels * 0.88).toInt().coerceAtMost(metrics.widthPixels - 32)
        val cardHeight = (metrics.heightPixels * 0.62).toInt()
        val card = binding.floatingCardContainer
        card.layoutParams = android.widget.FrameLayout.LayoutParams(cardWidth, cardHeight).apply {
            gravity = android.view.Gravity.CENTER
        }
        // Swallow touches inside the card (no ripple) so only the outside area collapses the window
        card.isClickable = true
        card.rippleColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        binding.floatingRoot.setOnClickListener {
            Log.i("$TAG Tapped outside the floating card, collapsing to bubble")
            minimizeFloatingWindow()
        }

        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }

        card.alpha = 0f
        card.scaleX = 0.9f
        card.scaleY = 0.9f
        card.translationY = 24f
        card.post {
            card.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(200)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f))
                .withLayer()
                .start()
        }
    }

    fun minimizeFloatingWindow() {
        if (isFinishing || isDestroyed) return
        if (org.linphone.ui.call.bubble.FloatingBubbleManager.canDrawOverlays(this)) {
            binding.floatingCardContainer.animate()
                .alpha(0f)
                .scaleX(0.75f)
                .scaleY(0.75f)
                .translationY(30f)
                .setDuration(150)
                .setInterpolator(android.view.animation.AccelerateInterpolator(1.5f))
                .withLayer()
                .withEndAction {
                    finish()
                    if (android.os.Build.VERSION.SDK_INT >= 34) {
                        overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
                    } else {
                        @Suppress("DEPRECATION")
                        overridePendingTransition(0, 0)
                    }
                }
                .start()
        } else {
            finish()
        }
    }

    override fun finish() {
        super.finish()
        if (org.linphone.ui.call.bubble.FloatingBubbleManager.canDrawOverlays(this)) {
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                overrideActivityTransition(
                    android.app.Activity.OVERRIDE_TRANSITION_CLOSE,
                    0,
                    0
                )
            } else {
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
        }
    }

    private fun updateCurrentLayout(newLayoutInfo: WindowLayoutInfo) {
        if (newLayoutInfo.displayFeatures.isNotEmpty()) {
            for (feature in newLayoutInfo.displayFeatures) {
                val foldingFeature = feature as? FoldingFeature
                if (foldingFeature != null) {
                    Log.i(
                        "$TAG Folding feature state changed: ${foldingFeature.state}, orientation is ${foldingFeature.orientation}"
                    )
                    sharedViewModel.foldingState.value = foldingFeature
                }
            }
        }
    }

    private fun navigateToActiveCall(notInConference: Boolean) {
        val navController = findNavController(R.id.call_nav_container)
        val action = when (navController.currentDestination?.id) {
            R.id.outgoingCallFragment -> {
                if (notInConference) {
                    Log.i("$TAG Going from outgoing call fragment to call fragment")
                    OutgoingCallFragmentDirections.actionOutgoingCallFragmentToActiveCallFragment()
                } else {
                    Log.i(
                        "$TAG Going from outgoing call fragment to conference call fragment"
                    )
                    OutgoingCallFragmentDirections.actionOutgoingCallFragmentToActiveConferenceCallFragment()
                }
            }
            R.id.incomingCallFragment -> {
                if (notInConference) {
                    Log.i("$TAG Going from incoming call fragment to call fragment")
                    IncomingCallFragmentDirections.actionIncomingCallFragmentToActiveCallFragment()
                } else {
                    Log.i(
                        "$TAG Going from incoming call fragment to conference call fragment"
                    )
                    IncomingCallFragmentDirections.actionIncomingCallFragmentToActiveConferenceCallFragment()
                }
            }
            R.id.activeCallFragment -> {
                if (notInConference) {
                    Log.i("$TAG Already on active call fragment")
                    return
                } else {
                    Log.i("$TAG Going from call fragment to conference call fragment")
                    ActiveCallFragmentDirections.actionActiveCallFragmentToActiveConferenceCallFragment()
                }
            }
            R.id.activeConferenceCallFragment -> {
                if (notInConference) {
                    Log.i("$TAG Going from conference call fragment to call fragment")
                    ActiveConferenceCallFragmentDirections.actionActiveConferenceCallFragmentToActiveCallFragment()
                } else {
                    Log.i(
                        "$TAG Going from conference call fragment to conference call fragment"
                    )
                    ActiveConferenceCallFragmentDirections.actionGlobalActiveConferenceCallFragment()
                }
            }
            R.id.callsListFragment -> {
                if (notInConference) {
                    Log.i("$TAG Going calls list fragment to active call fragment")
                    CallsListFragmentDirections.actionCallsListFragmentToActiveCallFragment()
                } else {
                    Log.i("$TAG Going calls list fragment to conference fragment")
                    CallsListFragmentDirections.actionCallsListFragmentToActiveConferenceCallFragment()
                }
            }
            else -> {
                if (notInConference) {
                    Log.i("$TAG Going from call fragment to call fragment")
                    ActiveCallFragmentDirections.actionGlobalActiveCallFragment()
                } else {
                    Log.i("$TAG Going from call fragment to conference call fragment")
                    ActiveConferenceCallFragmentDirections.actionGlobalActiveConferenceCallFragment()
                }
            }
        }
        navController.navigate(action)
    }

    private fun hideUI(hide: Boolean) {
        Log.i("$TAG Switching full screen mode to [${if (hide) "ON" else "OFF"}]")
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (hide) {
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showAudioRoutesMenu(devicesList: List<AudioDeviceModel>) {
        val modalBottomSheet = AudioDevicesMenuDialogFragment(devicesList)
        modalBottomSheet.show(supportFragmentManager, AudioDevicesMenuDialogFragment.TAG)
        bottomSheetDialog = modalBottomSheet
    }

    private fun showConferenceLayoutMenu() {
        val modalBottomSheet = ConferenceLayoutMenuDialogFragment(callViewModel.conferenceModel)
        modalBottomSheet.show(supportFragmentManager, ConferenceLayoutMenuDialogFragment.TAG)
        bottomSheetDialog = modalBottomSheet
    }
}
