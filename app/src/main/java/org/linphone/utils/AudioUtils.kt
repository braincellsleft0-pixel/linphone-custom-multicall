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
package org.linphone.utils

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import java.util.Collections
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.core.AudioDevice
import org.linphone.core.Call
import org.linphone.core.tools.Log

class AudioUtils {
    companion object {
        private const val TAG = "[Audio Utils]"

        private val stickySpeakerAppliedCallIds = Collections.synchronizedSet(HashSet<String>())

        /** Calls for which we already forced the Android audio route to be applied. */
        private val audioRouteEnsuredCallIds = Collections.synchronizedSet(HashSet<String>())

        /** User explicitly picked speaker (true) or earpiece (false). Call ONLY from user-initiated actions. */
        @WorkerThread
        fun rememberUserSpeakerChoice(speaker: Boolean) {
            if (corePreferences.keepSpeakerOn != speaker) {
                Log.i("$TAG User explicitly chose [${if (speaker) "speaker" else "earpiece"}], remembering it for next calls")
                corePreferences.keepSpeakerOn = speaker
            }
        }

        /** True if a bluetooth / headset / headphones / hearing aid device is currently available for playback. */
        @WorkerThread
        fun isExternalAudioDeviceAvailable(): Boolean {
            return coreContext.core.audioDevices.any {
                it.hasCapability(AudioDevice.Capabilities.CapabilityPlay) && (
                    it.type == AudioDevice.Type.Bluetooth ||
                        it.type == AudioDevice.Type.HearingAid ||
                        it.type == AudioDevice.Type.Headset ||
                        it.type == AudioDevice.Type.Headphones
                    )
            }
        }

        /** Should a new/ongoing call be (re)routed to speaker because the user asked for it earlier? */
        @WorkerThread
        fun shouldUseStickySpeaker(): Boolean {
            return corePreferences.keepSpeakerOn && !isExternalAudioDeviceAvailable()
        }

        /** Target output type if the sticky speaker setting has to be enforced, otherwise null. */
        @WorkerThread
        fun stickyOutputTypeOrNull(): AudioDevice.Type? {
            return if (shouldUseStickySpeaker()) AudioDevice.Type.Speaker else null
        }

        /** Re-apply the sticky speaker on this call if needed (idempotent, applies once per call). */
        @WorkerThread
        fun applyStickySpeakerIfNeeded(call: Call) {
            if (!shouldUseStickySpeaker()) return

            val callId = call.callLog.callId.orEmpty()
            if (callId.isNotEmpty() && stickySpeakerAppliedCallIds.contains(callId)) {
                return
            }

            if (call.outputAudioDevice?.type == AudioDevice.Type.Speaker) {
                if (callId.isNotEmpty()) stickySpeakerAppliedCallIds.add(callId)
                return
            }

            if (callId.isNotEmpty()) stickySpeakerAppliedCallIds.add(callId)
            Log.i("$TAG Sticky speaker enabled, routing call [${call.remoteAddress.asStringUriOnly()}] to speaker")
            routeAudioToSpeaker(call)
        }

        /**
         * Backstop that makes sure Android is really routing the call audio to the builtin
         * earpiece / speaker once the media stream is running.
         *
         * The Linphone SDK and the self managed Telecom integration normally do it, but when the
         * SDK audio mode switch races with Telecom's endpoint setup the RTP flow can start while
         * AudioManager is still in MODE_NORMAL: the call is "connected" but nothing is heard until
         * the user manually changes the audio route (which restarts the audio stream).
         *
         * This method is idempotent, is only allowed while a call is actually connected, and never
         * touches bluetooth / wired headset / hearing aid routing (those are left to the system).
         *
         * @param force true to re-apply the route even if it was already ensured for this call.
         * @param desiredType explicit target type, use null to read it from the call's output device.
         */
        @WorkerThread
        fun ensureCallAudioRoute(
            call: Call? = null,
            force: Boolean = false,
            desiredType: AudioDevice.Type? = null
        ) {
            if (coreContext.core.callsNb == 0) {
                Log.i("$TAG No call, not touching Android audio route")
                return
            }

            val currentCall = call ?: coreContext.core.currentCall ?: coreContext.core.calls.firstOrNull()
            if (currentCall == null) {
                Log.w("$TAG No call found to ensure Android audio route for")
                return
            }

            val state = currentCall.state
            if (state != Call.State.Connected && state != Call.State.StreamsRunning) {
                Log.i("$TAG Not ensuring Android audio route yet, call state is [$state]")
                return
            }

            val callId = currentCall.callLog.callId.orEmpty()
            if (!force && callId.isNotEmpty() && audioRouteEnsuredCallIds.contains(callId)) {
                Log.i("$TAG Android audio route already ensured for call [$callId], skipping")
                return
            }

            val targetType = desiredType ?: currentCall.outputAudioDevice?.type
                ?: coreContext.core.defaultOutputAudioDevice?.type
            if (targetType != AudioDevice.Type.Earpiece && targetType != AudioDevice.Type.Speaker) {
                Log.i("$TAG Desired output device [$targetType] isn't earpiece or speaker, leaving Android audio route untouched")
                return
            }

            if (callId.isNotEmpty()) audioRouteEnsuredCallIds.add(callId)

            val audioManager = coreContext.context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager == null) {
                Log.e("$TAG Unable to get Android AudioManager, can't ensure audio route")
                return
            }

            try {
                // Without MODE_IN_COMMUNICATION Android keeps the voice stream muted / not routed.
                if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                    Log.w("$TAG AudioManager mode is [${audioManager.mode}], switching to MODE_IN_COMMUNICATION")
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                }

                val wantSpeaker = targetType == AudioDevice.Type.Speaker
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val wantedInfoType = if (wantSpeaker) {
                        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    } else {
                        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    }
                    val currentCommunicationDevice = audioManager.communicationDevice
                    if (currentCommunicationDevice?.type == wantedInfoType) {
                        Log.i("$TAG Communication device is already [$wantedInfoType], nothing to do")
                    } else {
                        val target = audioManager.availableCommunicationDevices.firstOrNull {
                            it.type == wantedInfoType
                        }
                        if (target != null) {
                            val success = audioManager.setCommunicationDevice(target)
                            Log.i(
                                "$TAG setCommunicationDevice([${target.productName}], type [$wantedInfoType]) result: $success"
                            )
                        } else {
                            Log.w("$TAG No communication device with type [$wantedInfoType] available")
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    if (audioManager.isSpeakerphoneOn != wantSpeaker) {
                        @Suppress("DEPRECATION")
                        audioManager.isSpeakerphoneOn = wantSpeaker
                        Log.i("$TAG Updated legacy audioManager.isSpeakerphoneOn = $wantSpeaker")
                    }
                }
            } catch (e: Exception) {
                Log.e("$TAG Failed to ensure Android audio route: $e")
            }
        }

        @WorkerThread
        fun applySystemAudioRoute(isSpeaker: Boolean) {
            Log.i("$TAG applySystemAudioRoute(isSpeaker=$isSpeaker) - forwarding to ensureCallAudioRoute()")
            ensureCallAudioRoute(
                call = null,
                force = true,
                desiredType = if (isSpeaker) AudioDevice.Type.Speaker else AudioDevice.Type.Earpiece
            )
        }

        @WorkerThread
        fun resetSystemAudioRoute() {
            audioRouteEnsuredCallIds.clear()
            stickySpeakerAppliedCallIds.clear()

            if (coreContext.core.callsNb > 0) {
                Log.i("$TAG Not resetting Android audio route, [${coreContext.core.callsNb}] call(s) still running")
                return
            }

            val audioManager = coreContext.context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager == null) {
                Log.e("$TAG Unable to get Android AudioManager, can't reset audio route")
                return
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.clearCommunicationDevice()
                }
                @Suppress("DEPRECATION")
                if (audioManager.isSpeakerphoneOn) {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = false
                }
                if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
                    audioManager.mode = AudioManager.MODE_NORMAL
                }
                Log.i("$TAG Android audio route reset after last call ended")
            } catch (e: Exception) {
                Log.w("$TAG Failed to reset Android audio route: $e")
            }
        }

        @WorkerThread
        fun routeAudioToEarpiece(call: Call? = null) {
            routeAudioTo(call, arrayListOf(AudioDevice.Type.Earpiece))
        }

        @WorkerThread
        fun routeAudioToSpeaker(call: Call? = null) {
            routeAudioTo(call, arrayListOf(AudioDevice.Type.Speaker))
        }

        @WorkerThread
        fun routeAudioToBluetooth(call: Call? = null) {
            routeAudioTo(call, arrayListOf(AudioDevice.Type.Bluetooth))
        }

        @WorkerThread
        fun routeAudioToHearingAid(call: Call? = null) {
            routeAudioTo(call, arrayListOf(AudioDevice.Type.HearingAid))
        }

        @WorkerThread
        fun routeAudioBluetoothOrHearingAid(call: Call? = null) {
            routeAudioTo(
                call,
                arrayListOf(AudioDevice.Type.Bluetooth, AudioDevice.Type.HearingAid)
            )
        }

        @WorkerThread
        fun routeAudioToAnyConnectedAudioDeviceOtherThanEarpieceAndSpeaker(call: Call? = null) {
            routeAudioTo(
                call,
                arrayListOf(
                    AudioDevice.Type.Bluetooth, AudioDevice.Type.HearingAid,
                    AudioDevice.Type.Headphones, AudioDevice.Type.Headset
                )
            )
        }

        @WorkerThread
        fun routeAudioToHeadset(call: Call? = null) {
            routeAudioTo(
                call,
                arrayListOf(AudioDevice.Type.Headphones, AudioDevice.Type.Headset)
            )
        }

        @WorkerThread
        fun routeAudioToHdmi(call: Call? = null) {
            routeAudioTo(call, arrayListOf(AudioDevice.Type.Hdmi))
        }

        @WorkerThread
        private fun routeAudioTo(
            call: Call?,
            types: List<AudioDevice.Type>
        ) {
            val currentCall = call ?: coreContext.core.currentCall ?: coreContext.core.calls.firstOrNull()
            if (currentCall != null) {
                applyAudioRouteChange(currentCall, types)
            } else {
                applyAudioRouteChange(null, types)
            }
        }

        @WorkerThread
        private fun applyAudioRouteChange(
            call: Call?,
            types: List<AudioDevice.Type>,
            output: Boolean = true
        ) {
            val currentCall = if (coreContext.core.callsNb > 0) {
                call ?: coreContext.core.currentCall ?: coreContext.core.calls[0]
            } else {
                Log.w("$TAG No call found, setting audio route on Core")
                null
            }
            applyAudioRouteChangeInLinphone(currentCall, types, output)
        }

        fun applyAudioRouteChangeInLinphone(
            call: Call?,
            types: List<AudioDevice.Type>,
            output: Boolean = true
        ): Boolean {
            val capability = if (output) {
                AudioDevice.Capabilities.CapabilityPlay
            } else {
                AudioDevice.Capabilities.CapabilityRecord
            }
            val preferredDriver = if (output) {
                coreContext.core.defaultOutputAudioDevice?.driverName
            } else {
                coreContext.core.defaultInputAudioDevice?.driverName
            }

            val extendedAudioDevices = coreContext.core.extendedAudioDevices
            Log.i(
                "$TAG Looking for an [${if (output) "output" else "input"}] audio device with capability [$capability], driver name [$preferredDriver] and type [$types] in extended audio devices list (size ${extendedAudioDevices.size})"
            )
            val foundAudioDevice = extendedAudioDevices.find {
                it.driverName == preferredDriver && types.contains(it.type) && it.hasCapability(
                    capability
                )
            }
            val audioDevice = if (foundAudioDevice == null) {
                Log.w(
                    "$TAG Failed to find an audio device with capability [$capability], driver name [$preferredDriver] and type [$types]"
                )
                extendedAudioDevices.find {
                    types.contains(it.type) && it.hasCapability(capability)
                }
            } else {
                foundAudioDevice
            }

            if (audioDevice == null) {
                Log.e(
                    "$TAG Couldn't find audio device with capability [$capability] and type [$types]"
                )
                return false
            }
            if (call != null) {
                Log.i(
                    "$TAG Found [${audioDevice.type}] [${if (output) "playback" else "recorder"}] audio device [${audioDevice.deviceName} (${audioDevice.driverName})], routing call audio to it"
                )
                if (output) {
                    call.outputAudioDevice = audioDevice
                } else {
                    call.inputAudioDevice = audioDevice
                }
            } else {
                Log.i(
                    "$TAG Found [${audioDevice.type}] [${if (output) "playback" else "recorder"}] audio device [${audioDevice.deviceName} (${audioDevice.driverName})], changing core default audio device"
                )
                if (output) {
                    coreContext.core.outputAudioDevice = audioDevice
                } else {
                    coreContext.core.inputAudioDevice = audioDevice
                }
            }
            if (output) {
                if (audioDevice.type == AudioDevice.Type.Speaker) {
                    coreContext.telecomManager.requestSpeakerEndpoint(call)
                } else if (audioDevice.type == AudioDevice.Type.Earpiece) {
                    coreContext.telecomManager.requestEarpieceEndpoint(call)
                }
                // Also force Android's own audio route to follow, so that switching
                // earpiece/speaker always restarts the voice stream on the right device.
                if (call != null) {
                    ensureCallAudioRoute(call, force = true, desiredType = audioDevice.type)
                }
            }
            return true
        }

        @WorkerThread
        fun getAudioPlaybackDeviceIdForCallRecordingOrVoiceMessage(): String? {
            // In case no headphones/headset/hearing aid/bluetooth is connected, use speaker sound card to play recordings, otherwise use earpiece
            // If none are available, default one will be used
            var headphonesCard: String? = null
            var bluetoothCard: String? = null
            var speakerCard: String? = null
            var earpieceCard: String? = null
            for (device in coreContext.core.audioDevices) {
                if (device.hasCapability(AudioDevice.Capabilities.CapabilityPlay)) {
                    when (device.type) {
                        AudioDevice.Type.Headphones, AudioDevice.Type.Headset -> {
                            headphonesCard = device.id
                        }
                        AudioDevice.Type.Bluetooth, AudioDevice.Type.HearingAid -> {
                            bluetoothCard = device.id
                        }
                        AudioDevice.Type.Speaker -> {
                            speakerCard = device.id
                        }
                        AudioDevice.Type.Earpiece -> {
                            earpieceCard = device.id
                        }
                        else -> {}
                    }
                }
            }
            Log.i(
                "$TAG Found headset/headphones/hearingAid sound card [$headphonesCard], bluetooth sound card [$bluetoothCard], speaker sound card [$speakerCard] and earpiece sound card [$earpieceCard]"
            )
            return if (coreContext.isConnectedToAndroidAuto) {
                Log.w(
                    "$TAG Device seems to be connected to Android Auto, do not use bluetooth sound card, priority order is headphone > speaker > earpiece"
                )
                headphonesCard ?: speakerCard ?: earpieceCard
            } else {
                Log.i(
                    "$TAG Device doesn't seem to be connected to Android Auto, use headphone > bluetooth > speaker > earpiece sound card in priority order"
                )
                headphonesCard ?: bluetoothCard ?: speakerCard ?: earpieceCard
            }
        }

        @WorkerThread
        fun getAudioRecordingDeviceIdForVoiceMessage(): AudioDevice? {
            // In case no headset/hearing aid/bluetooth is connected, use microphone sound card
            // If none are available, default one will be used
            var headsetCard: AudioDevice? = null
            var microphoneCard: AudioDevice? = null
            for (device in coreContext.core.audioDevices) {
                if (device.hasCapability(AudioDevice.Capabilities.CapabilityRecord)) {
                    when (device.type) {
                        AudioDevice.Type.Headphones, AudioDevice.Type.Headset -> {
                            headsetCard = device
                        }
                        AudioDevice.Type.Microphone -> {
                            microphoneCard = device
                        }
                        else -> {}
                    }
                }
            }
            Log.i(
                "$TAG Found headset/headphones sound card [$headsetCard] and microphone card [$microphoneCard]"
            )
            return headsetCard ?: microphoneCard
        }

        @AnyThread
        fun acquireAudioFocusForVoiceRecordingOrPlayback(context: Context): AudioFocusRequestCompat {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val audioAttrs = AudioAttributesCompat.Builder()
                .setUsage(AudioAttributesCompat.USAGE_MEDIA)
                .setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC)
                .build()

            val request =
                AudioFocusRequestCompat.Builder(
                    AudioManagerCompat.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                )
                    .setAudioAttributes(audioAttrs)
                    .setOnAudioFocusChangeListener { }
                    .build()
            when (AudioManagerCompat.requestAudioFocus(audioManager, request)) {
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                    Log.i("$TAG Voice recording/playback audio focus request granted")
                }
                AudioManager.AUDIOFOCUS_REQUEST_FAILED -> {
                    Log.w("$TAG Voice recording/playback audio focus request failed")
                }
                AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                    Log.w("$TAG Voice recording/playback audio focus request delayed")
                }
            }
            return request
        }

        @AnyThread
        fun releaseAudioFocusForVoiceRecordingOrPlayback(
            context: Context,
            request: AudioFocusRequestCompat
        ) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            AudioManagerCompat.abandonAudioFocusRequest(audioManager, request)
            Log.i("$TAG Voice recording/playback audio focus request abandoned")
        }

        @AnyThread
        fun isMediaVolumeLow(context: Context): Boolean {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            Log.i("$TAG Current media volume value is $currentVolume, max value is $maxVolume")
            return currentVolume <= maxVolume * 0.5
        }
    }
}
