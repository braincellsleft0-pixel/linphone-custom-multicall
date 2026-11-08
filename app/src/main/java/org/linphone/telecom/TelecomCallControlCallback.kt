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
package org.linphone.telecom

import android.telecom.DisconnectCause
import androidx.annotation.WorkerThread
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.core.Call
import org.linphone.core.CallListenerStub
import org.linphone.core.Reason
import org.linphone.core.tools.Log
import org.linphone.utils.AudioUtils
import org.linphone.utils.Event
import org.linphone.utils.LinphoneUtils

class TelecomCallControlCallback(
    private val call: Call,
    private val callControl: CallControlScope,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "[Telecom Call Control Callback]"
    }

    private var mutedByTelecomManager = false
    private var latestEndpoints: List<CallEndpointCompat> = emptyList()
    private var currentEndpointType: Int = CallEndpointCompat.TYPE_UNKNOWN
    private var hasRequestedStickySpeaker: Boolean = false

    fun requestSpeakerEndpoint() {
        if (currentEndpointType == CallEndpointCompat.TYPE_SPEAKER) {
            Log.d("$TAG Telecom endpoint is already SPEAKER, skipping request")
            hasRequestedStickySpeaker = true
            coreContext.postOnCoreThread {
                AudioUtils.ensureCallAudioRoute(
                    call,
                    force = true,
                    desiredType = org.linphone.core.AudioDevice.Type.Speaker
                )
            }
            return
        }
        val speakerEndpoint = latestEndpoints.find { it.type == CallEndpointCompat.TYPE_SPEAKER }
        if (speakerEndpoint != null) {
            scope.launch {
                try {
                    Log.i("$TAG Requesting Telecom endpoint change to SPEAKER [${speakerEndpoint.name}]")
                    val result = callControl.requestEndpointChange(speakerEndpoint)
                    Log.i("$TAG Telecom requestEndpointChange(SPEAKER) result: $result")
                    if (result is CallControlResult.Success) {
                        hasRequestedStickySpeaker = true
                    } else {
                        hasRequestedStickySpeaker = false
                        Log.w("$TAG requestEndpointChange(SPEAKER) non-success: $result, retrying in 500ms")
                        kotlinx.coroutines.delay(500)
                        val retry = callControl.requestEndpointChange(speakerEndpoint)
                        Log.i("$TAG Retry Telecom requestEndpointChange(SPEAKER) result: $retry")
                        if (retry is CallControlResult.Success) {
                            hasRequestedStickySpeaker = true
                        }
                    }
                } catch (e: Exception) {
                    hasRequestedStickySpeaker = false
                    Log.w("$TAG Failed to request Telecom endpoint change to SPEAKER: $e, retrying in 500ms")
                    kotlinx.coroutines.delay(500)
                    try {
                        val retry = callControl.requestEndpointChange(speakerEndpoint)
                        Log.i("$TAG Retry Telecom requestEndpointChange(SPEAKER) result: $retry")
                        if (retry is CallControlResult.Success) {
                            hasRequestedStickySpeaker = true
                        }
                    } catch (e2: Exception) {
                        Log.e("$TAG Retry requestEndpointChange(SPEAKER) failed: $e2")
                    }
                }
            }
        } else {
            Log.w("$TAG No SPEAKER endpoint found in available endpoints list (${latestEndpoints.size} endpoints)")
        }
    }

    fun requestEarpieceEndpoint() {
        hasRequestedStickySpeaker = false
        if (currentEndpointType == CallEndpointCompat.TYPE_EARPIECE) {
            Log.d("$TAG Telecom endpoint is already EARPIECE, skipping request")
            coreContext.postOnCoreThread {
                AudioUtils.ensureCallAudioRoute(
                    call,
                    force = true,
                    desiredType = org.linphone.core.AudioDevice.Type.Earpiece
                )
            }
            return
        }
        val earpieceEndpoint = latestEndpoints.find { it.type == CallEndpointCompat.TYPE_EARPIECE }
        if (earpieceEndpoint != null) {
            scope.launch {
                try {
                    Log.i("$TAG Requesting Telecom endpoint change to EARPIECE [${earpieceEndpoint.name}]")
                    val result = callControl.requestEndpointChange(earpieceEndpoint)
                    Log.i("$TAG Telecom requestEndpointChange(EARPIECE) result: $result")
                    if (result !is CallControlResult.Success) {
                        Log.w("$TAG requestEndpointChange(EARPIECE) non-success: $result, retrying in 500ms")
                        kotlinx.coroutines.delay(500)
                        val retry = callControl.requestEndpointChange(earpieceEndpoint)
                        Log.i("$TAG Retry Telecom requestEndpointChange(EARPIECE) result: $retry")
                    }
                } catch (e: Exception) {
                    Log.w("$TAG Failed to request Telecom endpoint change to EARPIECE: $e, retrying in 500ms")
                    kotlinx.coroutines.delay(500)
                    try {
                        val retry = callControl.requestEndpointChange(earpieceEndpoint)
                        Log.i("$TAG Retry Telecom requestEndpointChange(EARPIECE) result: $retry")
                    } catch (e2: Exception) {
                        Log.e("$TAG Retry requestEndpointChange(EARPIECE) failed: $e2")
                    }
                }
            }
        } else {
            Log.w("$TAG No EARPIECE endpoint found in available endpoints list (${latestEndpoints.size} endpoints)")
        }
    }

    fun resyncAudioRoute() {
        coreContext.postOnCoreThread {
            val linphoneDevice = call.outputAudioDevice
            val linphoneType = linphoneDevice?.type
            val targetType = when (linphoneType) {
                org.linphone.core.AudioDevice.Type.Speaker -> CallEndpointCompat.TYPE_SPEAKER
                org.linphone.core.AudioDevice.Type.Earpiece -> CallEndpointCompat.TYPE_EARPIECE
                org.linphone.core.AudioDevice.Type.Bluetooth -> CallEndpointCompat.TYPE_BLUETOOTH
                org.linphone.core.AudioDevice.Type.Headphones,
                org.linphone.core.AudioDevice.Type.Headset -> CallEndpointCompat.TYPE_WIRED_HEADSET
                else -> CallEndpointCompat.TYPE_UNKNOWN
            }
            Log.i(
                "$TAG resyncAudioRoute: Linphone output=[$linphoneType] ([${linphoneDevice?.id}]), Telecom current=[${endpointTypeToString(currentEndpointType)}], target=[${endpointTypeToString(targetType)}]"
            )
            // Always (re)apply the Android audio route for builtin devices, even when Telecom
            // already reports the right endpoint: Telecom reporting SPEAKER/EARPIECE doesn't mean
            // AudioManager is actually in communication mode with that device selected.
            val desiredType = when (targetType) {
                CallEndpointCompat.TYPE_SPEAKER -> org.linphone.core.AudioDevice.Type.Speaker
                CallEndpointCompat.TYPE_EARPIECE -> org.linphone.core.AudioDevice.Type.Earpiece
                else -> null
            }
            AudioUtils.ensureCallAudioRoute(call, force = true, desiredType = desiredType)
            coreContext.postOnCoreThreadDelayed({
                AudioUtils.ensureCallAudioRoute(call, force = true, desiredType = desiredType)
            }, 700L)

            if (targetType == CallEndpointCompat.TYPE_SPEAKER &&
                currentEndpointType != CallEndpointCompat.TYPE_SPEAKER
            ) {
                requestSpeakerEndpoint()
            } else if (targetType == CallEndpointCompat.TYPE_EARPIECE &&
                currentEndpointType != CallEndpointCompat.TYPE_EARPIECE
            ) {
                requestEarpieceEndpoint()
            } else if (targetType == CallEndpointCompat.TYPE_UNKNOWN) {
                Log.d("$TAG resyncAudioRoute: no builtin device to sync, letting the system handle it")
            }
        }
    }

    private val callListener = object : CallListenerStub() {
        @WorkerThread
        override fun onStateChanged(call: Call, state: Call.State?, message: String) {
            Log.i("$TAG Call [${call.remoteAddress.asStringUriOnly()}] state changed [$state]")
            if (state == Call.State.Connected) {
                if (call.dir == Call.Dir.Incoming) {
                    answerCall()
                } else {
                    scope.launch {
                        Log.i("$TAG Setting call active")
                        val result = callControl.setActive()
                        if (result is CallControlResult.Error) {
                            Log.e("$TAG Failed to set call control active: $result")
                        } else {
                            resyncAudioRoute()
                        }
                    }
                }
            } else if (state == Call.State.End) {
                callEnded()
            } else if (state == Call.State.Error) {
                callError(message)
            } else if (state == Call.State.Pausing) {
                scope.launch {
                    Log.i("$TAG Pausing call")
                    val result = callControl.setInactive()
                    if (result is CallControlResult.Error) {
                        Log.e("$TAG Failed to set call control inactive: $result")
                    }
                }
            } else if (state == Call.State.Resuming) {
                scope.launch {
                    Log.i("$TAG Resuming call")
                    val result = callControl.setActive()
                    if (result is CallControlResult.Error) {
                        Log.e("$TAG Failed to set call control active: $result")
                    }
                }
            }
        }
    }

    init {
        // NEVER CALL ANY METHOD FROM callControl OBJECT IN HERE!
        Log.i("$TAG Created callback for call")
        coreContext.postOnCoreThread {
            call.addListener(callListener)
        }
    }

    fun onCallControlCallbackSet() {
        Log.i(
            "$TAG Callback have been set for call, Telecom call ID is [${callControl.getCallId()}]"
        )

        coreContext.postOnCoreThread {
            val state = call.state
            Log.i("$TAG Call state currently is [$state]")
            when (state) {
                Call.State.Connected, Call.State.StreamsRunning -> answerCall()
                Call.State.End -> callEnded()
                Call.State.Error -> callError("")
                Call.State.Released -> callEnded()
                else -> {} // doing nothing
            }
        }

        callControl.currentCallEndpoint.onEach { endpoint ->
            currentEndpointType = endpoint.type
            Log.i("$TAG Current Telecom call endpoint [${endpoint.name}] with type [${endpointTypeToString(endpoint.type)}]")
            coreContext.postOnCoreThread {
                // Never touch the Linphone output device before the call is connected: assigning it
                // while no media stream exists leaves Android's route unapplied (no sound at connect).
                val callState = call.state
                if (callState != Call.State.Connected && callState != Call.State.StreamsRunning) {
                    Log.i(
                        "$TAG Not syncing Linphone output on Telecom endpoint change yet, call state is [$callState]"
                    )
                    return@postOnCoreThread
                }
                val currentLinphoneType = call.outputAudioDevice?.type
                if (endpoint.type == CallEndpointCompat.TYPE_SPEAKER && currentLinphoneType != org.linphone.core.AudioDevice.Type.Speaker) {
                    Log.i("$TAG Telecom endpoint changed to SPEAKER, syncing Linphone output to Speaker")
                    AudioUtils.routeAudioToSpeaker(call)
                } else if (endpoint.type == CallEndpointCompat.TYPE_EARPIECE && currentLinphoneType != org.linphone.core.AudioDevice.Type.Earpiece) {
                    Log.i("$TAG Telecom endpoint changed to EARPIECE, syncing Linphone output to Earpiece")
                    AudioUtils.routeAudioToEarpiece(call)
                }
            }
        }.launchIn(scope)

        callControl.availableEndpoints.onEach { list ->
            latestEndpoints = list
            Log.i("$TAG Available audio endpoints list: ${list.map { "${it.name}:${it.type}" }}")
            val wantsSpeaker = call.outputAudioDevice?.type == org.linphone.core.AudioDevice.Type.Speaker || AudioUtils.shouldUseStickySpeaker()
            if (wantsSpeaker && currentEndpointType != CallEndpointCompat.TYPE_SPEAKER && !hasRequestedStickySpeaker) {
                hasRequestedStickySpeaker = true
                requestSpeakerEndpoint()
            }
        }.launchIn(scope)

        callControl.isMuted.onEach { muted ->
            coreContext.postOnCoreThread {
                val callState = call.state
                Log.i(
                    "$TAG We're asked to [${if (muted) "mute" else "unmute"}] the call in state [$callState]"
                )
                if (muted) {
                    Log.w("$TAG Telecom requested MUTE for call [${call.remoteAddress.asStringUriOnly()}], muting microphone")
                }
                // Only follow un-mute requests for not outgoing calls (such as joining a conference muted)
                // and if connected to Android Auto that has a way to let user mute/unmute from the car directly
                // or if we muted the call previously following Telecom Manager request.
                if (muted || mutedByTelecomManager || (!LinphoneUtils.isCallOutgoing(callState, false) && coreContext.isConnectedToAndroidAuto)) {
                    mutedByTelecomManager = muted
                    call.microphoneMuted = muted
                    coreContext.refreshMicrophoneMuteStateEvent.postValue(Event(true))
                } else {
                    if (coreContext.isConnectedToAndroidAuto) {
                        Log.w(
                            "$TAG Not following unmute request because call is in state [$callState]"
                        )
                    } else {
                        Log.w(
                            "$TAG Not following unmute request because user isn't connected to Android Auto and call is in state [$callState]"
                        )
                    }
                }
            }
        }.launchIn(scope)
    }

    private fun answerCall() {
        val isVideo = LinphoneUtils.isVideoEnabled(call)
        val type = if (isVideo) {
            CallAttributesCompat.CALL_TYPE_VIDEO_CALL
        } else {
            CallAttributesCompat.CALL_TYPE_AUDIO_CALL
        }
        scope.launch {
            Log.i("$TAG Answering [${if (isVideo) "video" else "audio"}] call")
            val result = callControl.answer(type)
            if (result is CallControlResult.Error) {
                Log.e("$TAG Failed to answer call control: $result")
            } else {
                resyncAudioRoute()
            }
        }

        if (isVideo && corePreferences.routeAudioToSpeakerWhenVideoIsEnabled) {
            Log.i("$TAG Answering video call, routing audio to speaker")
            AudioUtils.routeAudioToSpeaker(call)
        }
    }

    private fun callEnded() {
        val reason = call.reason
        val direction = call.dir
        scope.launch {
            val disconnectCause = when (reason) {
                Reason.NotAnswered -> DisconnectCause.REMOTE
                Reason.Declined -> DisconnectCause.REJECTED
                Reason.Busy -> {
                    if (direction == Call.Dir.Incoming) {
                        DisconnectCause.MISSED
                    } else {
                        DisconnectCause.BUSY
                    }
                }
                else -> DisconnectCause.LOCAL
            }
            Log.i("$TAG Disconnecting [${if (direction == Call.Dir.Incoming)"incoming" else "outgoing"}] call with cause [${disconnectCauseToString(disconnectCause)}] because it has ended with reason [$reason]")
            try {
                val result = callControl.disconnect(DisconnectCause(disconnectCause))
                if (result is CallControlResult.Error) {
                    Log.e("$TAG Failed to disconnect call control: $result")
                }
            } catch (ise: IllegalArgumentException) {
                Log.e("$TAG Couldn't disconnect call control with cause [${disconnectCauseToString(disconnectCause)}]: $ise")
            }
        }
    }

    private fun callError(message: String) {
        val reason = call.reason
        scope.launch {
            // For some reason DisconnectCause.ERROR or DisconnectCause.BUSY triggers an IllegalArgumentException with following message
            // Valid DisconnectCause codes are limited to [DisconnectCause.LOCAL, DisconnectCause.REMOTE, DisconnectCause.MISSED, or DisconnectCause.REJECTED]
            val disconnectCause = DisconnectCause.REJECTED
            Log.w("$TAG Disconnecting call with cause [${disconnectCauseToString(disconnectCause)}] due to error [$message] and reason [$reason]")
            try {
                val result = callControl.disconnect(DisconnectCause(disconnectCause))
                if (result is CallControlResult.Error) {
                    Log.e("$TAG Failed to disconnect call control: $result")
                }
            } catch (ise: IllegalArgumentException) {
                Log.e("$TAG Couldn't disconnect call control with cause [${disconnectCauseToString(disconnectCause)}]: $ise")
            }
        }
    }

    private fun disconnectCauseToString(cause: Int): String {
        return when (cause) {
            DisconnectCause.UNKNOWN -> "UNKNOWN"
            DisconnectCause.ERROR -> "ERROR"
            DisconnectCause.LOCAL -> "LOCAL"
            DisconnectCause.REMOTE -> "REMOTE"
            DisconnectCause.CANCELED -> "CANCELED"
            DisconnectCause.MISSED -> "MISSED"
            DisconnectCause.REJECTED -> "REJECTED"
            DisconnectCause.BUSY -> "BUSY"
            DisconnectCause.RESTRICTED -> "RESTRICTED"
            DisconnectCause.OTHER -> "OTHER"
            DisconnectCause.CONNECTION_MANAGER_NOT_SUPPORTED -> "CONNECTION_MANAGER_NOT_SUPPORTED"
            DisconnectCause.ANSWERED_ELSEWHERE -> "ANSWERED_ELSEWHERE"
            DisconnectCause.CALL_PULLED -> "CALL_PULLED"
            else -> "UNEXPECTED: $cause"
        }
    }

    private fun endpointTypeToString(type: Int): String {
        return when (type) {
            CallEndpointCompat.TYPE_UNKNOWN -> "UNKNOWN"
            CallEndpointCompat.TYPE_EARPIECE -> "EARPIECE"
            CallEndpointCompat.TYPE_BLUETOOTH -> "BLUETOOTH"
            CallEndpointCompat.TYPE_WIRED_HEADSET -> "WIRED HEADSET"
            CallEndpointCompat.TYPE_SPEAKER -> "SPEAKER"
            CallEndpointCompat.TYPE_STREAMING -> "STREAMING"
            else -> "UNEXPECTED: $type"
        }
    }
}
