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
package org.linphone.ui.call.viewmodel

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.AnyThread
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.core.app.ActivityCompat
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.contacts.ContactsManager.ContactsListener
import org.linphone.core.Address
import org.linphone.core.AudioDevice
import org.linphone.core.Call
import org.linphone.core.CallListenerStub
import org.linphone.core.CallStats
import org.linphone.core.ChatMessage
import org.linphone.core.ChatRoom
import org.linphone.core.ChatRoomListenerStub
import org.linphone.core.ConferenceParams
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Friend
import org.linphone.core.MediaDirection
import org.linphone.core.MediaEncryption
import org.linphone.core.SecurityLevel
import org.linphone.core.tools.Log
import org.linphone.ui.GenericViewModel
import org.linphone.ui.call.conference.viewmodel.ConferenceViewModel
import org.linphone.ui.call.model.AudioDeviceModel
import org.linphone.ui.call.model.CallMediaEncryptionModel
import org.linphone.ui.call.model.CallStatsModel
import org.linphone.ui.main.contacts.model.ContactAvatarModel
import org.linphone.ui.main.history.model.NumpadModel
import org.linphone.utils.AppUtils
import org.linphone.utils.AudioUtils
import org.linphone.utils.Event
import org.linphone.utils.LinphoneUtils

class CurrentCallViewModel
    @UiThread
    constructor() : GenericViewModel() {
    companion object {
        private const val TAG = "[Current Call ViewModel]"
        private const val VU_METER_MIN = -20f
        private const val VU_METER_MAX = 0
        private val terminatingCalls = java.util.Collections.synchronizedSet(java.util.HashSet<Call>())
    }

    val contact = MutableLiveData<ContactAvatarModel>()

    val displayedName = MutableLiveData<String>()

    val displayedAddress = MutableLiveData<String>()

    val isVideoEnabled = MutableLiveData<Boolean>()

    val isSendingVideo = MutableLiveData<Boolean>()

    val isReceivingVideo = MutableLiveData<Boolean>()

    val showSwitchCamera = MutableLiveData<Boolean>()

    val videoUpdateInProgress = MutableLiveData<Boolean>()

    val isIncomingEarlyMedia = MutableLiveData<Boolean>()

    val isOutgoing = MutableLiveData<Boolean>()

    val isOutgoingRinging = MutableLiveData<Boolean>()

    val isOutgoingEarlyMedia = MutableLiveData<Boolean>()

    val isRecordingEnabled = MutableLiveData<Boolean>()

    val isRecording = MutableLiveData<Boolean>()

    val canBePaused = MutableLiveData<Boolean>()

    val isPaused = MutableLiveData<Boolean>()

    val isPausedByRemote = MutableLiveData<Boolean>()

    val isMicrophoneMuted = MutableLiveData<Boolean>()

    val microphoneRecordingVolume = MutableLiveData<Float>()

    val playbackVolume = MutableLiveData<Float>()

    val isSpeakerEnabled = MutableLiveData<Boolean>()

    val isHeadsetEnabled = MutableLiveData<Boolean>()

    val isHearingAidEnabled = MutableLiveData<Boolean>()

    val isBluetoothEnabled = MutableLiveData<Boolean>()

    val isHdmiEnabled = MutableLiveData<Boolean>()

    val fullScreenMode = MutableLiveData<Boolean>()

    val pipMode = MutableLiveData<Boolean>()

    val halfOpenedFolded = MutableLiveData<Boolean>()

    val isZrtp = MutableLiveData<Boolean>()

    val isZrtpSasValidationRequired = MutableLiveData<Boolean>()

    val waitingForEncryptionInfo = MutableLiveData<Boolean>()

    val isMediaEncrypted = MutableLiveData<Boolean>()

    val hideVideo = MutableLiveData<Boolean>()

    var isStatsSheetVisible = false

    private var vuMeterJob: Job? = null

    val callStatsModel = CallStatsModel()

    val callMediaEncryptionModel = CallMediaEncryptionModel {
        showZrtpSasDialogIfPossible()
    }

    val incomingCallTitle: MutableLiveData<String> by lazy {
        MutableLiveData()
    }

    val qualityValue = MutableLiveData<Float>()

    val qualityIcon = MutableLiveData<Int>()

    val hideSipAddresses = MutableLiveData<Boolean>()

    var terminatedByUser = false

    val isRemoteRecordingEvent: MutableLiveData<Event<Pair<Boolean, String>>> by lazy {
        MutableLiveData()
    }

    val goToEndedCallEvent: MutableLiveData<Event<String>> by lazy {
        MutableLiveData()
    }

    val finishActivityEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val requestRecordAudioPermission: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val requestCameraPermission: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val proximitySensorEnabled = MutableLiveData<Boolean>()

    // To synchronize chronometers in UI
    val callDuration = MutableLiveData<Int>()

    val showAudioDevicesListEvent: MutableLiveData<Event<ArrayList<AudioDeviceModel>>> by lazy {
        MutableLiveData()
    }

    // ZRTP related

    val showZrtpSasDialogEvent: MutableLiveData<Event<Pair<String, List<String>>>> by lazy {
        MutableLiveData()
    }

    val showZrtpSasCacheMismatchDialogEvent: MutableLiveData<Event<Pair<String, List<String>>>> by lazy {
        MutableLiveData()
    }

    val zrtpAuthTokenVerifiedEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    var isZrtpDialogVisible: Boolean = false
    var isZrtpAlertDialogVisible: Boolean = false

    // Chat

    var currentCallConversation: ChatRoom? = null

    val unreadMessagesCount = MutableLiveData<Int>()

    val operationInProgress = MutableLiveData<Boolean>()

    val goToConversationEvent: MutableLiveData<Event<String>> by lazy {
        MutableLiveData()
    }

    // Conference

    val conferenceModel = ConferenceViewModel()

    val goToConferenceEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val goToCallEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val goToOutgoingCallEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    // Extras actions

    val toggleExtraActionsBottomSheetEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val showNumpadBottomSheetEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val transferInProgressEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val transferFailedEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val numpadModel: NumpadModel

    val appendDigitToSearchBarEvent: MutableLiveData<Event<String>> by lazy {
        MutableLiveData()
    }

    val removedCharacterAtCurrentPositionEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    val clearPressedDtmfBarEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    // Sliding answer/decline button

    val isScreenLocked = MutableLiveData<Boolean>()

    var currentCall: Call? = null

    private val contactsListener = object : ContactsListener {
        @WorkerThread
        override fun onContactsLoaded() {
        }

        @WorkerThread
        override fun onContactFoundInRemoteDirectory(friend: Friend) {
            val address = contact.value?.address
            address ?: return

            val addressMatch = friend.addresses.find {
                it.weakEqual(address)
            }
            if (addressMatch != null) {
                Log.i("$TAG Updating current call contact model")
                displayedName.postValue(friend.name)
                val model = ContactAvatarModel(friend, address)
                contact.postValue(model)
            }
        }
    }

    private val callListener = object : CallListenerStub() {
        @WorkerThread
        override fun onEncryptionChanged(call: Call, on: Boolean, authenticationToken: String?) {
            Log.i("$TAG Call encryption changed, updating...")
            updateEncryption(call)
        }

        @WorkerThread
        override fun onAuthenticationTokenVerified(call: Call, verified: Boolean) {
            Log.w(
                "$TAG Notified that authentication token is [${if (verified) "verified" else "not verified!"}]"
            )
            isZrtpSasValidationRequired.postValue(!verified)
            zrtpAuthTokenVerifiedEvent.postValue(Event(verified))
            if (verified) {
                isMediaEncrypted.postValue(true)
            }

            updateAvatarModelSecurityLevel(verified)
        }

        @WorkerThread
        override fun onRemoteRecording(call: Call, recording: Boolean) {
            Log.i("$TAG Remote recording changed: $recording")
            isRemoteRecordingEvent.postValue(Event(Pair(recording, displayedName.value.orEmpty())))
        }

        @WorkerThread
        override fun onStatsUpdated(call: Call, stats: CallStats) {
            if (isStatsSheetVisible) {
                callStatsModel.update(call, stats)
            }
        }

        @WorkerThread
        override fun onStateChanged(call: Call, state: Call.State, message: String) {
            Log.i("$TAG Call [${call.remoteAddress.asStringUriOnly()}] state changed [$state]")
            if (LinphoneUtils.isCallOutgoing(call.state)) {
                isVideoEnabled.postValue(call.params.isVideoEnabled)
                updateVideoDirection(call.params.videoDirection)
            } else if (LinphoneUtils.isCallEnding(call.state)) {
                // If current call is being terminated but there is at least one other call, switch
                val core = call.core
                val remainingCalls = core.calls.filter {
                    it != call && !LinphoneUtils.isCallEnding(it.state, considerReleasedAsEnding = true)
                }
                Log.i(
                    "$TAG Call is being ended, check for another current call (remaining [${remainingCalls.size}] calls)"
                )
                if (CallsViewModel.focusedCall == call) {
                    CallsViewModel.focusedCall = null
                }
                call.removeListener(this)
                if (remainingCalls.isNotEmpty()) {
                    val newCurrentCall = CallsViewModel.focusedCall?.takeIf {
                        it != call && !LinphoneUtils.isCallEnding(it.state, considerReleasedAsEnding = true)
                    } ?: remainingCalls.first()
                    CallsViewModel.focusedCall = newCurrentCall
                    Log.i(
                        "$TAG From now on current call will be [${newCurrentCall.remoteAddress.asStringUriOnly()}]"
                    )
                    configureCall(newCurrentCall)
                    if (newCurrentCall.state == Call.State.Paused) {
                        Log.i("$TAG Auto-resuming remaining call [${newCurrentCall.remoteAddress.asStringUriOnly()}]")
                        newCurrentCall.resume()
                    }
                    if (!LinphoneUtils.isCallOutgoing(newCurrentCall.state)) {
                        goToCallEvent.postValue(Event(newCurrentCall.conference == null))
                    }
                } else {
                    CallsViewModel.focusedCall = null
                    endCall(call)
                }
            } else {
                val videoEnabled = LinphoneUtils.isVideoEnabled(call)
                if (videoEnabled && isVideoEnabled.value == false) {
                    if (isBluetoothEnabled.value == true || isHeadsetEnabled.value == true) {
                        Log.i(
                            "$TAG Audio is routed to bluetooth or headset, do not change it to speaker because video was enabled"
                        )
                    } else if (corePreferences.routeAudioToSpeakerWhenVideoIsEnabled) {
                        Log.i("$TAG Video is now enabled, routing audio to speaker")
                        AudioUtils.routeAudioToSpeaker(call)
                    }
                }
                isVideoEnabled.postValue(videoEnabled)
                updateVideoDirection(call.currentParams.videoDirection, skipIfNotStreamsRunning = true)

                if (call.state == Call.State.Connected) {
                    updateCallDuration()
                    if (call.conference != null) {
                        Log.i(
                            "$TAG Call is in Connected state and conference isn't null, wait for StreamsRunning state to navigate to conference layout"
                        )
                    } else {
                        conferenceModel.destroy()
                    }
                } else if (call.state == Call.State.StreamsRunning) {
                    videoUpdateInProgress.postValue(false)
                    updateCallDuration()
                    if (corePreferences.automaticallyStartCallRecording) {
                        val recording = call.params.isRecording
                        isRecording.postValue(recording)
                        if (recording) {
                            showRecordingToast()
                        }
                    }

                    // MediaEncryption None & SRTP won't be notified through onEncryptionChanged callback,
                    // we have to do it manually to leave the "wait for encryption" state
                    when (call.currentParams.mediaEncryption) {
                        MediaEncryption.SRTP, MediaEncryption.None -> {
                            updateEncryption(call)
                        }
                        else -> {}
                    }

                    if (call.conference != null && !conferenceModel.conferenceConfigured) {
                        Log.i("$TAG Found conference on call but not conference model, initializing it now")
                        conferenceModel.configureFromCall(call)
                        updateMicrophoneMutedIcon()
                        goToConferenceEvent.postValue(Event(true))
                    }
                }
            }

            isPaused.postValue(isCallPaused())
            isPausedByRemote.postValue(call.state == Call.State.PausedByRemote)
            canBePaused.postValue(canCallBePaused())
        }

        @WorkerThread
        override fun onAudioDeviceChanged(call: Call, audioDevice: AudioDevice) {
            Log.i("$TAG Audio device changed [${audioDevice.id}]")
            updateOutputAudioDevice(audioDevice)
        }
    }

    private val chatRoomListener = object : ChatRoomListenerStub() {
        @WorkerThread
        override fun onStateChanged(chatRoom: ChatRoom, newState: ChatRoom.State?) {
            val state = chatRoom.state
            if (state == ChatRoom.State.Instantiated) return

            val id = LinphoneUtils.getConversationId(chatRoom)
            Log.i("$TAG Conversation [$id] (${chatRoom.subjectUtf8}) state changed: [$state]")

            if (state == ChatRoom.State.Created) {
                Log.i("$TAG Conversation [$id] successfully created")
                chatRoom.removeListener(this)
                operationInProgress.postValue(false)
                goToConversationEvent.postValue(Event(LinphoneUtils.getConversationId(chatRoom)))
            } else if (state == ChatRoom.State.CreationFailed) {
                Log.e("$TAG Conversation [$id] creation has failed!")
                chatRoom.removeListener(this)
                operationInProgress.postValue(false)
                showRedToast(R.string.conversation_failed_to_create_toast, R.drawable.warning_circle)
            }
        }
    }

    private val coreListener = object : CoreListenerStub() {
        @WorkerThread
        override fun onCallStateChanged(
            core: Core,
            call: Call,
            state: Call.State,
            message: String
        ) {
            val targetFocus = CallsViewModel.focusedCall ?: core.currentCall
            if (call == targetFocus) {
                isOutgoingRinging.postValue(call.state == Call.State.OutgoingRinging)
                isIncomingEarlyMedia.postValue(call.state == Call.State.IncomingEarlyMedia)
                isOutgoingEarlyMedia.postValue(call.state == Call.State.OutgoingEarlyMedia)
            }

            val curCall = currentCall
            if (curCall != null) {
                if (call != curCall) {
                    if (call == targetFocus && state != Call.State.Pausing) {
                        Log.w(
                            "$TAG Current focused call has changed, now is [${call.remoteAddress.asStringUriOnly()}] with state [$state]"
                        )
                        curCall.removeListener(callListener)
                        configureCall(call)
                    } else if (LinphoneUtils.isCallIncoming(call.state)) {
                        Log.w(
                            "$TAG A call is being received [${call.remoteAddress.asStringUriOnly()}], using it as current call unless declined"
                        )
                        curCall.removeListener(callListener)
                        configureCall(call)
                    }
                }
            } else {
                Log.w(
                    "$TAG There was no current call (shouldn't be possible), using [${call.remoteAddress.asStringUriOnly()}] anyway"
                )
                configureCall(call)
            }

            if (LinphoneUtils.isCallEnding(call.state)) {
                waitingForEncryptionInfo.postValue(false)
            }

            updateProximitySensor()
        }

        @WorkerThread
        override fun onTransferStateChanged(core: Core, transfered: Call, state: Call.State) {
            Log.i(
                "$TAG Transferred call [${transfered.remoteAddress.asStringUriOnly()}] state changed [$state]"
            )

            if (state == Call.State.OutgoingProgress) {
                transferInProgressEvent.postValue(Event(true))
            } else if (LinphoneUtils.isCallEnding(state)) {
                transferFailedEvent.postValue(Event(true))
            }
        }

        @WorkerThread
        override fun onMessagesReceived(
            core: Core,
            chatRoom: ChatRoom,
            messages: Array<out ChatMessage>
        ) {
            val curCall = currentCall
            if (curCall != null) {
                if (currentCallConversation == null) {
                    currentCallConversation = lookupCurrentCallConversation(curCall)
                }
                if (currentCallConversation != null && currentCallConversation == chatRoom) {
                    val unreadCount = chatRoom.unreadMessagesCount
                    Log.i(
                        "$TAG Received [${messages.size}] message(s) for current call conversation, currently [$unreadCount] unread messages"
                    )
                    unreadMessagesCount.postValue(unreadCount)
                }
            }
        }

        @WorkerThread
        override fun onChatRoomRead(core: Core, chatRoom: ChatRoom) {
            if (currentCallConversation != null && currentCallConversation == chatRoom) {
                Log.i("$TAG Current call conversation was marked as read")
                unreadMessagesCount.postValue(0)
            }
        }

        @WorkerThread
        override fun onAudioDevicesListUpdated(core: Core) {
            Log.i("$TAG Audio devices list has been updated")
        }
    }

    init {
        fullScreenMode.value = false
        operationInProgress.value = false
        proximitySensorEnabled.value = false
        videoUpdateInProgress.value = false
        microphoneRecordingVolume.value = 0f
        playbackVolume.value = 0f

        refreshKeyguardLockedStatus()

        coreContext.postOnCoreThread { core ->
            hideSipAddresses.postValue(corePreferences.hideSipAddresses)
            coreContext.contactsManager.addListener(contactsListener)

            core.addListener(coreListener)

            isRecordingEnabled.postValue(!corePreferences.disableCallRecordings)
            hideVideo.postValue(!core.isVideoEnabled)
            showSwitchCamera.postValue(coreContext.showSwitchCameraButton())

            val call = core.currentCall ?: core.calls.firstOrNull()

            if (call != null) {
                Log.i("$TAG Found call [${call.remoteAddress.asStringUriOnly()}]")
                configureCall(call)
            } else {
                Log.e("$TAG Failed to find call!")
            }
        }

        numpadModel = NumpadModel(
            true,
            { digit -> // onDigitClicked
                appendDigitToSearchBarEvent.value = Event(digit)
                coreContext.postOnCoreThread {
                    val curCall = currentCall
                    if (curCall != null) {
                        Log.i("$TAG Sending DTMF [${digit.first()}]")
                        curCall.sendDtmf(digit.first())
                    }
                }
            },
            { // onVoicemailClicked
            },
            { // OnBackspaceClicked
                removedCharacterAtCurrentPositionEvent.value = Event(true)
            },
            { // OnCallClicked
            },
            { // OnBlindTransferClicked
            },
            { // OnClearInput
                clearPressedDtmfBarEvent.value = Event(true)
            }
        )

        updateCallQualityIcon()
    }

    @UiThread
    override fun onCleared() {
        super.onCleared()
        vuMeterJob?.cancel()
        vuMeterJob = null

        coreContext.postOnCoreThread { core ->
            core.removeListener(coreListener)
            coreContext.contactsManager.removeListener(contactsListener)
            conferenceModel.destroy()
            contact.value?.destroy()

            currentCall?.removeListener(callListener)
            currentCall = null
        }
    }

    @UiThread
    fun refreshKeyguardLockedStatus() {
        val keyguardManager = coreContext.context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val secure = keyguardManager.isKeyguardLocked
        isScreenLocked.value = secure
        Log.i("$TAG Device is [${if (secure) "locked" else "unlocked"}]")
    }

    @UiThread
    fun answerCallFrom(caller: String) {
        coreContext.postOnCoreThread { core ->
            val call = core.calls.find {
                LinphoneUtils.isCallIncoming(it.state) && (caller.isEmpty() || caller == it.remoteAddress.asStringUriOnly())
            }
            if (call != null) {
                Log.i("$TAG Answering call [${call.remoteAddress.asStringUriOnly()}]")
                coreContext.answerCall(call)
            } else {
                Log.e("$TAG No call found in incoming state, can't answer any!")
                finishActivityEvent.postValue(Event(true))
            }
        }
    }

    @UiThread
    fun answer() {
        answerCallFrom("")
    }

    @UiThread
    fun hangUp() {
        Log.i("$TAG hangUp requested by user")
        terminatedByUser = true
        coreContext.postOnCoreThread { core ->
            terminatingCalls.removeAll { LinphoneUtils.isCallEnding(it.state, considerReleasedAsEnding = true) }
            val activeCalls = core.calls.filter {
                it !in terminatingCalls && !LinphoneUtils.isCallEnding(it.state, considerReleasedAsEnding = true)
            }
            val curCall = currentCall
            val callToTerminate = if (curCall != null && curCall !in terminatingCalls && !LinphoneUtils.isCallEnding(curCall.state, true)) {
                curCall
            } else {
                CallsViewModel.focusedCall?.takeIf { it !in terminatingCalls && !LinphoneUtils.isCallEnding(it.state, true) }
                    ?: core.currentCall?.takeIf { it !in terminatingCalls && !LinphoneUtils.isCallEnding(it.state, true) }
                    ?: activeCalls.firstOrNull()
            }

            if (callToTerminate != null) {
                Log.i("$TAG Terminating call [${callToTerminate.remoteAddress.asStringUriOnly()}]")
                terminatingCalls.add(callToTerminate)
                coreContext.terminateCall(callToTerminate)

                val targetNumber = callToTerminate.remoteAddress.username
                if (!targetNumber.isNullOrEmpty()) {
                    val duplicateLegs = activeCalls.filter {
                        it != callToTerminate && it.remoteAddress.username == targetNumber
                    }
                    for (dup in duplicateLegs) {
                        Log.i("$TAG Terminating duplicate leg to same number [${dup.remoteAddress.asStringUriOnly()}]")
                        terminatingCalls.add(dup)
                        coreContext.terminateCall(dup)
                    }
                }

                val remainingCalls = core.calls.filter {
                    it !in terminatingCalls && !LinphoneUtils.isCallEnding(it.state, considerReleasedAsEnding = true)
                }

                if (remainingCalls.isEmpty()) {
                    Log.i("$TAG No active calls remaining after hangup, finishing activity")
                    org.linphone.ui.call.CallHandlerActivity.cancelAllPendingCalls()
                    terminatingCalls.clear()
                    finishActivityEvent.postValue(Event(true))
                } else {
                    val nextCall = remainingCalls.first()
                    Log.i("$TAG Immediately advancing to next active call [${nextCall.remoteAddress.asStringUriOnly()}] state [${nextCall.state}]")
                    CallsViewModel.focusedCall = nextCall
                    configureCall(nextCall)
                    if (nextCall.state == Call.State.Paused) {
                        Log.i("$TAG Auto-resuming next call [${nextCall.remoteAddress.asStringUriOnly()}]")
                        nextCall.resume()
                    }
                    if (remainingCalls.size > 1) {
                        goToCallEvent.postValue(Event(nextCall.conference == null))
                    } else {
                        if (LinphoneUtils.isCallOutgoing(nextCall.state)) {
                            goToOutgoingCallEvent.postValue(Event(true))
                        } else {
                            goToCallEvent.postValue(Event(nextCall.conference == null))
                        }
                    }
                }
            } else {
                Log.w("$TAG No call to decline! Finishing activity")
                org.linphone.ui.call.CallHandlerActivity.cancelAllPendingCalls()
                terminatingCalls.clear()
                for (c in core.calls) {
                    try {
                        c.terminate()
                    } catch (e: Exception) {}
                }
                core.terminateAllCalls()
                finishActivityEvent.postValue(Event(true))
            }
        }
    }

    @UiThread
    fun skipZrtpSas() {
        coreContext.postOnCoreThread {
            val curCall = currentCall
            if (curCall != null) {
                Log.w("$TAG User skipped SAS validation in ZRTP call")
                curCall.skipZrtpAuthentication()
            }
        }
    }

    @UiThread
    fun updateZrtpSas(authTokenClicked: String) {
        coreContext.postOnCoreThread {
            val curCall = currentCall
            if (curCall != null) {
                if (authTokenClicked.isEmpty()) {
                    Log.e(
                        "$TAG Doing a fake ZRTP SAS check with empty token because user clicked on 'Not Found' button!"
                    )
                } else {
                    Log.i(
                        "$TAG Checking if ZRTP SAS auth token [$authTokenClicked] is the right one"
                    )
                }
                curCall.checkAuthenticationTokenSelected(authTokenClicked)
            }
        }
    }

    @UiThread
    fun toggleMuteMicrophone() {
        if (ActivityCompat.checkSelfPermission(
                coreContext.context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("$TAG RECORD_AUDIO permission isn't granted, requesting it")
            requestRecordAudioPermission.postValue(Event(true))
            return
        }

        coreContext.postOnCoreThread {
            val curCall = currentCall
            if (curCall != null) {
                val micMuted = if (curCall.conference != null) {
                    curCall.conference?.microphoneMuted == true
                } else {
                    curCall.microphoneMuted
                }
                if (curCall.conference != null) {
                    curCall.conference?.microphoneMuted = !micMuted
                } else {
                    curCall.microphoneMuted = !micMuted
                }
                if (micMuted) {
                    Log.w("$TAG Muting microphone")
                } else {
                    Log.i("$TAG Un-muting microphone")
                }
                isMicrophoneMuted.postValue(!micMuted)
            }
        }
    }

    @UiThread
    fun refreshMicrophoneState() {
        coreContext.postOnCoreThread {
            updateMicrophoneMutedIcon()
        }
    }

    @UiThread
    fun changeAudioOutputDevice() {
        val routeAudioToSpeaker = isSpeakerEnabled.value != true
        Log.i("$TAG Toggling audio output device at any time, routeAudioToSpeaker=$routeAudioToSpeaker")
        isSpeakerEnabled.value = routeAudioToSpeaker

        coreContext.postOnCoreThread { core ->
            val call = currentCall ?: CallsViewModel.focusedCall ?: core.currentCall ?: core.calls.firstOrNull()
            var earpieceFound = false
            var speakerFound = false
            val audioDevices = core.audioDevices
            val currentDevice = call?.outputAudioDevice ?: core.defaultOutputAudioDevice
            Log.i("$TAG Currently used output audio device is [${currentDevice?.deviceName} (${currentDevice?.type})]")

            val list = arrayListOf<AudioDeviceModel>()
            for (device in audioDevices) {
                // Only list output audio devices
                if (!device.hasCapability(AudioDevice.Capabilities.CapabilityPlay)) continue
                when (device.type) {
                    AudioDevice.Type.Earpiece -> {
                        earpieceFound = true
                    }
                    AudioDevice.Type.Speaker -> {
                        speakerFound = true
                    }
                    else -> {}
                }

                val name = LinphoneUtils.getAudioDeviceName(device)
                val isCurrentlyInUse = device.type == currentDevice?.type && device.deviceName == currentDevice?.deviceName
                val model = AudioDeviceModel(device, name, device.type, isCurrentlyInUse, true) {
                    // onSelected
                    coreContext.postOnCoreThread {
                        Log.i("$TAG Selected audio device with ID [${device.id}]")
                        if (device.type == AudioDevice.Type.Speaker) {
                            AudioUtils.rememberUserSpeakerChoice(true)
                        } else if (device.type == AudioDevice.Type.Earpiece) {
                            AudioUtils.rememberUserSpeakerChoice(false)
                        }
                        val callNow = currentCall ?: CallsViewModel.focusedCall ?: core.currentCall ?: core.calls.firstOrNull()
                        when (device.type) {
                            AudioDevice.Type.Headset, AudioDevice.Type.Headphones -> AudioUtils.routeAudioToHeadset(callNow)
                            AudioDevice.Type.Bluetooth -> AudioUtils.routeAudioToBluetooth(callNow)
                            AudioDevice.Type.HearingAid -> AudioUtils.routeAudioToHearingAid(callNow)
                            AudioDevice.Type.Speaker -> AudioUtils.routeAudioToSpeaker(callNow)
                            AudioDevice.Type.Hdmi -> AudioUtils.routeAudioToHdmi(callNow)
                            else -> AudioUtils.routeAudioToEarpiece(callNow)
                        }
                        core.calls.forEach { c ->
                            if (device.type == AudioDevice.Type.Speaker) {
                                AudioUtils.routeAudioToSpeaker(c)
                            } else if (device.type == AudioDevice.Type.Earpiece) AudioUtils.routeAudioToEarpiece(c)
                        }
                        updateOutputAudioDevice(callNow?.outputAudioDevice ?: core.defaultOutputAudioDevice)
                    }
                }
                list.add(model)
                Log.i("$TAG Found audio device [${device.id}]")
            }

            if (list.size > 2 || (list.size > 1 && (!earpieceFound || !speakerFound))) {
                Log.i("$TAG Found more than two devices (or more than 1 but no earpiece or speaker), showing list to let user choose")
                showAudioDevicesListEvent.postValue(Event(list))
            } else {
                Log.i(
                    "$TAG Switching between earpiece & speaker at any time: routeAudioToSpeaker=$routeAudioToSpeaker"
                )
                AudioUtils.rememberUserSpeakerChoice(routeAudioToSpeaker)
                if (routeAudioToSpeaker) {
                    AudioUtils.routeAudioToSpeaker(call)
                    core.calls.forEach { c -> AudioUtils.routeAudioToSpeaker(c) }
                } else {
                    AudioUtils.routeAudioToEarpiece(call)
                    core.calls.forEach { c -> AudioUtils.routeAudioToEarpiece(c) }
                }
                val activeDevice = call?.outputAudioDevice ?: core.defaultOutputAudioDevice
                updateOutputAudioDevice(activeDevice)
            }
        }
    }

    @UiThread
    fun toggleVideo() {
        if (ActivityCompat.checkSelfPermission(
                coreContext.context,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("$TAG CAMERA permission isn't granted, requesting it")
            requestCameraPermission.postValue(Event(true))
            return
        }

        coreContext.postOnCoreThread { core ->
            val curCall = currentCall
            if (curCall != null) {
                val params = core.createCallParams(curCall)
                if (curCall.conference != null) {
                    if (params?.isVideoEnabled == false) {
                        Log.i("$TAG Conference found and video disabled in params, enabling it")
                        params.isVideoEnabled = true
                        params.videoDirection = MediaDirection.SendRecv
                        conferenceModel.setNewLayout(ConferenceViewModel.ACTIVE_SPEAKER_LAYOUT)
                    } else {
                        if (params?.videoDirection == MediaDirection.SendRecv || params?.videoDirection == MediaDirection.SendOnly) {
                            Log.i(
                                "$TAG Conference found with video already enabled, changing video media direction to receive only"
                            )
                            params.videoDirection = MediaDirection.RecvOnly
                        } else {
                            Log.i(
                                "$TAG Conference found with video already enabled, changing video media direction to send & receive"
                            )
                            params?.videoDirection = MediaDirection.SendRecv
                        }
                    }

                    val sendingVideo = params?.videoDirection == MediaDirection.SendRecv || params?.videoDirection == MediaDirection.SendOnly
                    conferenceModel.localVideoStreamToggled(sendingVideo)
                } else if (params != null) {
                    params.isVideoEnabled = true
                    params.videoDirection = when (curCall.currentParams.videoDirection) {
                        MediaDirection.SendRecv, MediaDirection.SendOnly -> MediaDirection.RecvOnly
                        else -> MediaDirection.SendRecv
                    }
                    Log.i(
                        "$TAG Updating call with video enabled and media direction set to ${params.videoDirection}"
                    )
                }
                curCall.update(params)
                videoUpdateInProgress.postValue(true)
            }
        }
    }

    @UiThread
    fun switchCamera() {
        coreContext.postOnCoreThread {
            Log.i("$TAG Switching camera")
            coreContext.switchCamera()
        }
    }

    @UiThread
    fun toggleRecording() {
        coreContext.postOnCoreThread {
            val curCall = currentCall
            if (curCall != null) {
                val recording = if (curCall.params.isRecording) {
                    Log.i("$TAG Stopping call recording")
                    curCall.stopRecording()
                    false
                } else {
                    Log.i("$TAG Starting call recording")
                    curCall.startRecording()
                    true
                }

                isRecording.postValue(recording)
                if (recording) {
                    showRecordingToast()
                } else {
                    showGreenToast(R.string.call_has_been_recorded, R.drawable.record_fill)
                }
            }
        }
    }

    @UiThread
    fun togglePause() {
        coreContext.postOnCoreThread {
            val curCall = currentCall
            if (curCall != null) {
                if (curCall.conference != null) {
                    conferenceModel.togglePause()
                } else {
                    when (isCallPaused()) {
                        true -> {
                            Log.i(
                                "$TAG Resuming call [${curCall.remoteAddress.asStringUriOnly()}]"
                            )
                            curCall.resume()
                        }

                        false -> {
                            Log.i(
                                "$TAG Pausing call [${curCall.remoteAddress.asStringUriOnly()}]"
                            )
                            curCall.pause()
                        }
                    }
                }
            }
        }
    }

    @UiThread
    fun toggleFullScreen() {
        if (fullScreenMode.value == true) {
            // Always allow to switch off full screen mode
            fullScreenMode.value = false
            return
        }

        if (isVideoEnabled.value == false) {
            // Do not allow turning full screen on for audio only calls
            return
        }
        fullScreenMode.value = true
    }

    @UiThread
    fun toggleExpandActionsMenu() {
        toggleExtraActionsBottomSheetEvent.value = Event(true)
    }

    @UiThread
    fun showNumpad() {
        showNumpadBottomSheetEvent.value = Event(true)
    }

    @UiThread
    fun createConversation() {
        val curCall = currentCall
        if (curCall != null) {
            coreContext.postOnCoreThread {
                val existingConversation = currentCallConversation ?: lookupCurrentCallConversation(curCall)
                if (existingConversation != null) {
                    Log.i(
                        "$TAG Found existing conversation [${LinphoneUtils.getConversationId(existingConversation)}], going to it"
                    )
                    goToConversationEvent.postValue(Event(LinphoneUtils.getConversationId(existingConversation)))
                } else {
                    Log.i("$TAG No existing conversation was found, let's create it")
                    createCurrentCallConversation(curCall)
                }
            }
        }
    }

    @WorkerThread
    fun attendedTransferCallTo(to: Call) {
        val curCall = currentCall
        if (curCall != null) {
            val toCallState = to.state
            if (LinphoneUtils.isCallEnding(toCallState, considerReleasedAsEnding = true)) {
                Log.e("$TAG Do not attempt attended transfer to call in state [$toCallState]")
                return
            }
            val currentCallState = curCall.state
            if (LinphoneUtils.isCallEnding(currentCallState, considerReleasedAsEnding = true)) {
                Log.e("$TAG Do not attempt attended transfer of call in state [$currentCallState]")
                return
            }

            Log.i(
                "$TAG Doing an attended transfer between currently displayed call [${curCall.remoteAddress.asStringUriOnly()}] and paused call [${to.remoteAddress.asStringUriOnly()}]"
            )
            if (to.transferToAnother(curCall) == 0) {
                Log.i("$TAG Attended transfer is successful")
            } else {
                Log.e("$TAG Failed to make attended transfer!")
            }
        }
    }

    @WorkerThread
    fun blindTransferCallTo(to: Address) {
        val curCall = currentCall
        if (curCall != null) {
            val callState = curCall.state
            if (LinphoneUtils.isCallEnding(callState, considerReleasedAsEnding = true)) {
                Log.e("$TAG Do not attempt blind transfer of call in state [$callState]")
                return
            }

            Log.i(
                "$TAG Call [${curCall.remoteAddress.asStringUriOnly()}] is being blindly transferred to [${to.asStringUriOnly()}]"
            )
            if (curCall.transferTo(to) == 0) {
                Log.i("$TAG Blind call transfer is successful")
            } else {
                Log.e("$TAG Failed to make blind call transfer!")
                transferFailedEvent.postValue(Event(true))
            }
        }
    }

    @UiThread
    fun showZrtpSasDialogIfPossible() {
        coreContext.postOnCoreThread {
            val curCall = currentCall ?: return@postOnCoreThread
            if (curCall.currentParams.mediaEncryption == MediaEncryption.ZRTP) {
                val isDeviceTrusted = curCall.authenticationTokenVerified
                val cacheMismatch = curCall.zrtpCacheMismatchFlag
                Log.i(
                    "$TAG Current call media encryption is ZRTP, auth token is [${if (isDeviceTrusted) "trusted" else "not trusted yet"}]"
                )
                val tokenToRead = curCall.localAuthenticationToken
                val tokensToDisplay = curCall.remoteAuthenticationTokens.toList()
                if (!tokenToRead.isNullOrEmpty() && tokensToDisplay.size == 4) {
                    val event = Event(Pair(tokenToRead, tokensToDisplay))
                    if (cacheMismatch) {
                        showZrtpSasCacheMismatchDialogEvent.postValue(event)
                    } else {
                        showZrtpSasDialogEvent.postValue(event)
                    }
                } else {
                    Log.w(
                        "$TAG Either local auth token is null/empty or remote tokens list doesn't contains 4 elements!"
                    )
                }
            }
        }
    }

    @WorkerThread
    private fun updateEncryption(call: Call) {
        when (val mediaEncryption = call.currentParams.mediaEncryption) {
            MediaEncryption.ZRTP -> {
                val isDeviceTrusted = call.authenticationTokenVerified
                val cacheMismatch = call.zrtpCacheMismatchFlag
                Log.i(
                    "$TAG Current call media encryption is ZRTP, auth token is [${if (isDeviceTrusted) "trusted" else "not trusted yet"}], cache mismatch is [$cacheMismatch]"
                )

                updateAvatarModelSecurityLevel(isDeviceTrusted && !cacheMismatch)

                isMediaEncrypted.postValue(true)
                isZrtp.postValue(true)

                isZrtpSasValidationRequired.postValue(cacheMismatch || !isDeviceTrusted)
                if (cacheMismatch || !isDeviceTrusted) {
                    Log.i("$TAG Showing ZRTP SAS confirmation dialog")
                    val tokenToRead = call.localAuthenticationToken
                    val tokensToDisplay = call.remoteAuthenticationTokens.toList()
                    if (!tokenToRead.isNullOrEmpty() && tokensToDisplay.size == 4) {
                        val event = Event(Pair(tokenToRead, tokensToDisplay))
                        if (cacheMismatch) {
                            showZrtpSasCacheMismatchDialogEvent.postValue(event)
                        } else {
                            showZrtpSasDialogEvent.postValue(event)
                        }
                    } else {
                        Log.w(
                            "$TAG Either local auth token is null/empty or remote tokens list doesn't contains 4 elements!"
                        )
                    }
                }
            }
            MediaEncryption.SRTP, MediaEncryption.DTLS -> {
                Log.i("$TAG Current call media encryption is [$mediaEncryption]")
                isMediaEncrypted.postValue(true)
                isZrtp.postValue(false)
            }
            else -> {
                Log.w("$TAG Current call doesn't have any media encryption!")
                isMediaEncrypted.postValue(false)
                isZrtp.postValue(false)
            }
        }
        waitingForEncryptionInfo.postValue(false)
        callMediaEncryptionModel.update(call)
    }

    @WorkerThread
    fun configureCall(call: Call) {
        val cur = currentCall
        if (cur == call) {
            if (isStatsSheetVisible) {
                Log.i("$TAG Call [${call.remoteAddress.asStringUriOnly()}] is already current, updating stats only")
                callStatsModel.update(call, call.audioStats)
            }
            return
        }
        Log.i(
            "$TAG Configuring call with remote address [${call.remoteAddress.asStringUriOnly()}] as current"
        )
        cur?.removeListener(callListener)
        contact.value?.destroy()

        terminatedByUser = false
        currentCall = call
        if (isStatsSheetVisible) {
            callStatsModel.update(call, call.audioStats)
        }
        callMediaEncryptionModel.update(call)
        call.addListener(callListener)

        val state = call.state
        if (LinphoneUtils.isCallOutgoing(state) || LinphoneUtils.isCallIncoming(state)) {
            if (call.currentParams.mediaEncryption == MediaEncryption.None) {
                waitingForEncryptionInfo.postValue(true)
                isMediaEncrypted.postValue(false)
            } else {
                updateEncryption(call)
            }
        } else {
            updateEncryption(call)
        }

        val conferenceInfo = LinphoneUtils.getConferenceInfoIfAny(call)
        if (call.conference != null || conferenceInfo != null) {
            val subject = call.conference?.subject ?: conferenceInfo?.subject
            Log.i("$TAG Conference [$subject] found, going to conference fragment")
            conferenceModel.configureFromCall(call)
            goToConferenceEvent.postValue(Event(true))
        } else if (LinphoneUtils.isCallActive(call.state)) {
            Log.i("$TAG No conference attached to this call, going to call fragment")
            conferenceModel.destroy()
            goToCallEvent.postValue(Event(true))
        }

        if (call.dir == Call.Dir.Incoming) {
            val isVideo = call.remoteParams?.isVideoEnabled == true && call.remoteParams?.videoDirection != MediaDirection.Inactive
            if (call.core.accountList.size > 1) {
                val localAddress = call.callLog.toAddress
                Log.i("$TAG Local address for incoming call is [${localAddress.asStringUriOnly()}]")
                val localAccount = coreContext.core.accountList.find {
                    it.params.identityAddress?.weakEqual(localAddress) == true
                }
                val displayName = if (localAccount != null) {
                    LinphoneUtils.getDisplayName(localAccount.params.identityAddress)
                } else {
                    Log.w("$TAG Matching local account was not found, using TO address display name or username")
                    LinphoneUtils.getDisplayName(localAddress)
                }
                Log.i("$TAG Showing account being called as [$displayName]")

                if (isVideo) {
                    incomingCallTitle.postValue(
                        AppUtils.getFormattedString(
                            R.string.call_video_incoming_for_account,
                            displayName
                        )
                    )
                } else {
                    incomingCallTitle.postValue(
                        AppUtils.getFormattedString(
                            R.string.call_audio_incoming_for_account,
                            displayName
                        )
                    )
                }
            } else {
                if (isVideo) {
                    incomingCallTitle.postValue(AppUtils.getString(R.string.call_video_incoming))
                } else {
                    incomingCallTitle.postValue(AppUtils.getString(R.string.call_audio_incoming))
                }
            }
        }

        if (LinphoneUtils.isCallOutgoing(call.state)) {
            isVideoEnabled.postValue(call.params.isVideoEnabled)
            updateVideoDirection(call.params.videoDirection)
        } else if (LinphoneUtils.isCallIncoming(call.state)) {
            isVideoEnabled.postValue(
                call.remoteParams?.isVideoEnabled == true && call.remoteParams?.videoDirection != MediaDirection.Inactive
            )
        } else {
            isVideoEnabled.postValue(call.currentParams.isVideoEnabled)
            updateVideoDirection(call.currentParams.videoDirection, skipIfNotStreamsRunning = true)
        }

        if (ActivityCompat.checkSelfPermission(
                coreContext.context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(
                "$TAG RECORD_AUDIO permission wasn't granted yet, considering microphone as muted!"
            )
            isMicrophoneMuted.postValue(true)
        } else {
            val micMuted = call.conference?.microphoneMuted ?: call.microphoneMuted
            if (micMuted) {
                Log.w("$TAG Microphone is currently muted")
            }
            isMicrophoneMuted.postValue(micMuted)
        }

        val audioDevice = call.outputAudioDevice
        updateOutputAudioDevice(audioDevice)

        isOutgoing.postValue(call.dir == Call.Dir.Outgoing)
        isOutgoingRinging.postValue(state == Call.State.OutgoingRinging)
        isIncomingEarlyMedia.postValue(state == Call.State.IncomingEarlyMedia)
        isOutgoingEarlyMedia.postValue(state == Call.State.OutgoingEarlyMedia)

        isPaused.postValue(isCallPaused())
        isPausedByRemote.postValue(state == Call.State.PausedByRemote)
        canBePaused.postValue(canCallBePaused())

        val address = call.callLog.remoteAddress
        val uri = if (corePreferences.onlyDisplaySipUriUsername) {
            address.username ?: ""
        } else {
            LinphoneUtils.getAddressAsCleanStringUriOnly(address)
        }
        displayedAddress.postValue(uri)

        val model = if (conferenceInfo != null) {
            coreContext.contactsManager.getContactAvatarModelForConferenceInfo(conferenceInfo)
        } else {
            // Do not use contact avatar model from ContactsManager to be able to show
            // ZRTP verification status with the device that will answer the call
            val friend = coreContext.contactsManager.findContactByAddress(address)
            if (friend != null) {
                ContactAvatarModel(friend, address)
            } else {
                val fakeFriend = coreContext.core.createFriend()
                fakeFriend.name = LinphoneUtils.getDisplayName(address)
                fakeFriend.address = address
                val localAccount = coreContext.core.accountList.find {
                    it.params.identityAddress?.weakEqual(address) == true
                }
                if (localAccount != null) {
                    fakeFriend.photo = localAccount.params.pictureUri
                }
                ContactAvatarModel(fakeFriend, address)
            }
        }

        contact.postValue(model)
        displayedName.postValue(model.friend.name)

        val recording = call.params.isRecording
        isRecording.postValue(recording)
        if (recording) {
            showRecordingToast()
        }

        val isRemoteRecording = call.remoteParams?.isRecording == true
        if (isRemoteRecording) {
            Log.w("$TAG Remote end [${displayedName.value.orEmpty()}] is recording the call")
            isRemoteRecordingEvent.postValue(Event(Pair(true, displayedName.value.orEmpty())))
        }

        callDuration.postValue(call.duration)

        // Offload database chatroom search from the UI startup critical path
        viewModelScope.launch(Dispatchers.IO) {
            val chatRoom = lookupCurrentCallConversation(call)
            currentCallConversation = chatRoom
            if (chatRoom != null) {
                val unreadCount = chatRoom.unreadMessagesCount
                Log.i(
                    "$TAG Found existing 1-1 conversation for current call, currently [$unreadCount] unread messages in existing conversation"
                )
                unreadMessagesCount.postValue(unreadCount)
            } else {
                Log.i("$TAG Failed to find an existing 1-1 conversation for current call")
            }
        }

        vuMeterJob?.cancel()
        vuMeterJob = null
        if (corePreferences.showMicrophoneAndSpeakerVuMeters) {
            vuMeterJob = volumeVuMeterTickerFlow().onEach {
                coreContext.postOnCoreThread {
                    val call = currentCall ?: return@postOnCoreThread
                    val state = call.state
                    if (state == Call.State.End || state == Call.State.Released) return@postOnCoreThread

                    val mic = computeVuMeterValue(call.recordVolume)
                    val spk = computeVuMeterValue(call.playVolume)
                    if (microphoneRecordingVolume.value != mic) {
                        microphoneRecordingVolume.postValue(mic)
                    }
                    if (playbackVolume.value != spk) {
                        playbackVolume.postValue(spk)
                    }
                }
            }.launchIn(viewModelScope)
        }
    }

    @WorkerThread
    fun updateCallDuration() {
        val curCall = currentCall
        if (curCall != null) {
            callDuration.postValue(curCall.duration)
        }
    }

    @WorkerThread
    private fun updateMicrophoneMutedIcon() {
        val curCall = currentCall
        if (curCall != null) {
            val micMuted = if (curCall.conference != null) {
                curCall.conference?.microphoneMuted == true
            } else {
                curCall.microphoneMuted
            }
            if (micMuted != isMicrophoneMuted.value) {
                if (micMuted) {
                    Log.w("$TAG Microphone is muted, updating button state accordingly")
                } else {
                    Log.i("$TAG Microphone is not muted, updating button state accordingly")
                }
                isMicrophoneMuted.postValue(micMuted)
            }
        }
    }

    @WorkerThread
    private fun updateOutputAudioDevice(audioDevice: AudioDevice?) {
        Log.i("$TAG Output audio device updated to [${audioDevice?.deviceName} (${audioDevice?.type})]")
        isSpeakerEnabled.postValue(audioDevice?.type == AudioDevice.Type.Speaker)
        isHeadsetEnabled.postValue(
            audioDevice?.type == AudioDevice.Type.Headphones || audioDevice?.type == AudioDevice.Type.Headset
        )
        isHearingAidEnabled.postValue(audioDevice?.type == AudioDevice.Type.HearingAid)
        isBluetoothEnabled.postValue(audioDevice?.type == AudioDevice.Type.Bluetooth)
        isHdmiEnabled.postValue(audioDevice?.type == AudioDevice.Type.Hdmi)

        updateProximitySensor()
    }

    @WorkerThread
    private fun isCallPaused(): Boolean {
        val curCall = currentCall ?: return false
        return when (curCall.state) {
            Call.State.Paused, Call.State.Pausing -> true
            else -> false
        }
    }

    @WorkerThread
    private fun canCallBePaused(): Boolean {
        val curCall = currentCall ?: return false
        return !curCall.mediaInProgress() && when (curCall.state) {
            Call.State.StreamsRunning, Call.State.Pausing, Call.State.Paused -> true
            else -> false
        }
    }

    @WorkerThread
    private fun updateVideoDirection(direction: MediaDirection, skipIfNotStreamsRunning: Boolean = false) {
        val curCall = currentCall ?: return
        val state = curCall.state
        if (skipIfNotStreamsRunning && state != Call.State.StreamsRunning) {
            return
        }

        val isConnected = state == Call.State.Connected || state == Call.State.StreamsRunning
        val isSending = (state == Call.State.OutgoingEarlyMedia || isConnected) && (direction == MediaDirection.SendRecv || direction == MediaDirection.SendOnly)
        val isReceiving = (state == Call.State.IncomingEarlyMedia || isConnected) && (direction == MediaDirection.SendRecv || direction == MediaDirection.RecvOnly)

        val wasSending = isSendingVideo.value == true
        val wasReceiving = isReceivingVideo.value == true

        if (isReceiving != wasReceiving || isSending != wasSending) {
            Log.i(
                "$TAG Video is enabled in ${if (isSending && isReceiving) "both ways" else if (isSending) "upload" else "download"}"
            )
            isSendingVideo.postValue(isSending)
            isReceivingVideo.postValue(isReceiving)
        }

        if (curCall.conference == null) { // Let conference view model handle full screen while in conference
            if (isReceiving && !wasReceiving) { // Do not change full screen mode base on our video being sent when it wasn't
                if (fullScreenMode.value != true) {
                    Log.i(
                        "$TAG Video is being received or sent (and it wasn't before), switching to full-screen mode"
                    )
                    fullScreenMode.postValue(true)
                }
            } else if (!isSending && !isReceiving && fullScreenMode.value == true) {
                Log.w("$TAG Video is no longer sent nor received, leaving full screen mode")
                fullScreenMode.postValue(false)
            }
        }

        updateProximitySensor()
    }

    @AnyThread
    private fun updateCallQualityIcon() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                delay(1000)
                coreContext.postOnCoreThread {
                    val curCall = currentCall
                    if (curCall != null) {
                        val state = curCall.state
                        if (!LinphoneUtils.isCallEnding(state, true)) {
                            val quality = curCall.currentQuality
                            val icon = when {
                                quality >= 4 -> R.drawable.cell_signal_full
                                quality >= 3 -> R.drawable.cell_signal_high
                                quality >= 2 -> R.drawable.cell_signal_medium
                                quality >= 1 -> R.drawable.cell_signal_low
                                else -> R.drawable.cell_signal_none
                            }
                            qualityValue.postValue(quality)
                            qualityIcon.postValue(icon)

                            updateCallQualityIcon()
                        }
                    }
                }
            }
        }
    }

    @WorkerThread
    private fun updateAvatarModelSecurityLevel(trusted: Boolean) {
        val curCall = currentCall ?: return
        val securityLevel = if (trusted) SecurityLevel.EndToEndEncryptedAndVerified else SecurityLevel.EndToEndEncrypted
        val avatarModel = contact.value
        if (avatarModel != null && curCall.conference == null) { // Don't do it for conferences
            avatarModel.trust.postValue(securityLevel)
            contact.postValue(avatarModel)
        } else {
            Log.e("$TAG No avatar model found!")
        }

        // Also update avatar contact model if any for the rest of the app
        val address = curCall.remoteAddress
        val storedModel = coreContext.contactsManager.getContactAvatarModelForAddress(
            address
        )
        storedModel.updateSecurityLevel(address)
    }

    @WorkerThread
    private fun lookupCurrentCallConversation(call: Call): ChatRoom? {
        val localAddress = call.callLog.localAddress
        val remoteAddress = call.remoteAddress

        val existingConversation = if (call.conference != null) {
            Log.i("$TAG We're in [${remoteAddress.asStringUriOnly()}] conference, using it as chat room if possible")
            call.conference?.chatRoom
        } else {
            val params = getChatRoomParams(call)
            val participants = arrayOf(remoteAddress)
            Log.i("$TAG Looking for conversation with local address [${localAddress.asStringUriOnly()}] and participant [${remoteAddress.asStringUriOnly()}]")
            call.core.searchChatRoom(
                params,
                localAddress,
                null,
                participants
            )
        }
        if (existingConversation != null) {
            Log.i("$TAG Found existing conversation [${existingConversation.peerAddress.asStringUriOnly()}] found for current call with local address [${localAddress.asStringUriOnly()}] and remote address [${remoteAddress.asStringUriOnly()}]")
        } else {
            Log.w("$TAG No existing conversation found for current call with local address [${localAddress.asStringUriOnly()}] and remote address [${remoteAddress.asStringUriOnly()}]")
        }
        return existingConversation
    }

    @WorkerThread
    private fun createCurrentCallConversation(call: Call) {
        val remoteAddress = call.remoteAddress
        val participants = arrayOf(remoteAddress)
        val core = call.core
        operationInProgress.postValue(true)

        val params = getChatRoomParams(call) ?: return // TODO: show error to user
        val chatRoom = core.createChatRoom(params, participants)
        if (chatRoom != null) {
            if (params.chatParams?.backend == ChatRoom.Backend.FlexisipChat) {
                if (chatRoom.state == ChatRoom.State.Created) {
                    val id = LinphoneUtils.getConversationId(chatRoom)
                    Log.i("$TAG 1-1 conversation [$id] has been created")
                    operationInProgress.postValue(false)
                    goToConversationEvent.postValue(Event(LinphoneUtils.getConversationId(chatRoom)))
                } else {
                    Log.i("$TAG Conversation isn't in Created state yet, wait for it")
                    chatRoom.addListener(chatRoomListener)
                }
            } else {
                val id = LinphoneUtils.getConversationId(chatRoom)
                Log.i("$TAG Conversation successfully created [$id]")
                operationInProgress.postValue(false)
                goToConversationEvent.postValue(Event(LinphoneUtils.getConversationId(chatRoom)))
            }
        } else {
            Log.e(
                "$TAG Failed to create 1-1 conversation with [${remoteAddress.asStringUriOnly()}]!"
            )
            operationInProgress.postValue(false)
            showRedToast(R.string.conversation_failed_to_create_toast, R.drawable.warning_circle)
        }
    }

    @WorkerThread
    private fun getChatRoomParams(call: Call): ConferenceParams? {
        val remoteAddress = call.remoteAddress
        val params = LinphoneUtils.getChatRoomParamsForOneToOneConversationWithAddress(remoteAddress, call.conference)
        if (params == null) {
            Log.e(
                "$TAG Account is in secure mode, can't chat with SIP address of different domain [${remoteAddress.asStringUriOnly()}]"
            )
        }
        return params
    }

    @WorkerThread
    private fun endCall(call: Call) {
        val reason = call.reason
        val status = call.callLog.status
        Log.i("$TAG Call is ending with status [$status] because of reason [$reason]")

        when (status) {
            Call.Status.AcceptedElsewhere, Call.Status.DeclinedElsewhere -> {
                Log.i("$TAG Call was accepted/declined on another device, do not show ended call fragment")
                finishActivityEvent.postValue(Event(true))
            }
            else -> {
                if (pipMode.value == true) {
                    Log.i("$TAG Activity currently displayed in PiP, do not go to ended call fragment")
                    finishActivityEvent.postValue(Event(true))
                    return
                }

                Log.i("$TAG Go to ended call fragment")
                updateCallDuration()

                // Show that call was ended for a few seconds, then leave
                val text = if (call.state == Call.State.Error) {
                    LinphoneUtils.getCallErrorInfoToast(call)
                } else {
                    ""
                }
                goToEndedCallEvent.postValue(Event(text))
            }
        }
    }

    @AnyThread
    private fun showRecordingToast() {
        showGreenToast(R.string.call_is_being_recorded, R.drawable.record_fill)
    }

    private fun volumeVuMeterTickerFlow() = flow {
        while (currentCall != null) {
            emit(Unit)
            delay(150)
        }
    }

    private fun computeVuMeterValue(volume: Float): Float {
        if (volume < VU_METER_MIN) return 0f
        if (volume > VU_METER_MAX) return 1f
        return (volume - VU_METER_MIN) / (VU_METER_MAX - VU_METER_MIN)
    }

    @WorkerThread
    fun updateProximitySensor() {
        val curCall = currentCall
        if (curCall != null) {
            val callState = curCall.state
            Log.i("$TAG Call is in state [$callState], enabling/disabling proximity sensor if needed")

            if (LinphoneUtils.isCallIncoming(callState)) {
                Log.i("$TAG Call is incoming, disabling proximity sensor")
                proximitySensorEnabled.postValue(false)
            } else if (LinphoneUtils.isCallOutgoing(callState)) {
                val videoEnabled = curCall.params.isVideoEnabled
                if (videoEnabled) {
                    Log.i("$TAG Call is outgoing and video is enabled, disabling proximity sensor")
                } else {
                    Log.i("$TAG Call is outgoing and video is disabled, enabling proximity sensor")
                }
                proximitySensorEnabled.postValue(!videoEnabled)
            } else {
                if (isSendingVideo.value == true || isReceivingVideo.value == true) {
                    Log.i("$TAG Video is being sent and/or received, disabling proximity sensor")
                    proximitySensorEnabled.postValue(false)
                } else {
                    val outputAudioDevice = curCall.outputAudioDevice ?: coreContext.core.outputAudioDevice
                    if (outputAudioDevice != null && outputAudioDevice.type == AudioDevice.Type.Earpiece) {
                        Log.i("$TAG Audio device is earpiece, enabling proximity sensor")
                        proximitySensorEnabled.postValue(true)
                    } else {
                        Log.i("$TAG Audio device is [${outputAudioDevice?.type}], disabling proximity sensor")
                        proximitySensorEnabled.postValue(false)
                    }
                }
            }
        } else {
            Log.w("$TAG No current call, disabling proximity sensor")
            proximitySensorEnabled.postValue(false)
        }
    }
}
