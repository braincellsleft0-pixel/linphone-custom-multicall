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
package org.linphone.ui.call.model

import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Call
import org.linphone.core.Reason
import org.linphone.core.tools.Log
import org.linphone.ui.main.contacts.model.ContactAvatarModel
import org.linphone.utils.LinphoneUtils

class CallModel
    @WorkerThread
    constructor(val call: Call, isCur: Boolean = false) {
    companion object {
        private const val TAG = "[Call Model]"
    }

    val id: String = runCatching { call.callLog?.callId }.getOrNull().takeIf { !it.isNullOrEmpty() }
        ?: "call_${System.identityHashCode(call)}"

    private val rawNum: String = run {
        val addr = runCatching { call.callLog?.remoteAddress ?: call.remoteAddress }.getOrNull()
        addr?.username ?: LinphoneUtils.getDisplayName(addr)
    }

    val displayName: MutableLiveData<String>
    val phoneNumber: MutableLiveData<String>
    val statusTag: MutableLiveData<String>
    val durationText: MutableLiveData<String>
    val state: MutableLiveData<String>
    val isPaused: MutableLiveData<Boolean>
    val isEnded: MutableLiveData<Boolean>
    val isBusy: MutableLiveData<Boolean>
    val isOff: MutableLiveData<Boolean>
    val isCurrent: MutableLiveData<Boolean>
    val subStatusText: MutableLiveData<String>

    val friend: org.linphone.core.Friend? = null
    val contact = MutableLiveData<ContactAvatarModel>()

    init {
        val s = call.state
        val duration = call.duration
        val durationFormatted = formatDuration(duration)

        val paused = LinphoneUtils.isCallPaused(s)
        var st: String
        var tag: String
        var dur = ""
        var ended = false
        var busy = false
        var off = false

        when (s) {
            Call.State.OutgoingInit, Call.State.OutgoingProgress -> {
                st = "Calling..."
                tag = "(Calling)"
            }
            Call.State.OutgoingRinging, Call.State.OutgoingEarlyMedia -> {
                st = "Ringing..."
                tag = "(Ringing)"
            }
            Call.State.Connected, Call.State.StreamsRunning -> {
                st = "Connected $durationFormatted"
                tag = "(Received)"
                dur = " ($durationFormatted)"
            }
            Call.State.Pausing, Call.State.Paused, Call.State.PausedByRemote -> {
                st = "On Hold $durationFormatted"
                tag = "(Hold)"
                dur = " ($durationFormatted)"
            }
            Call.State.End, Call.State.Error, Call.State.Released -> {
                ended = true
                val reason = call.reason
                val code = call.errorInfo?.protocolCode ?: 0
                if (reason == Reason.Busy || code == 486) {
                    st = "Busy"
                    tag = "(Busy)"
                    busy = true
                } else if (reason == Reason.NotFound || reason == Reason.NotAnswered ||
                    reason == Reason.TemporarilyUnavailable || reason == Reason.IOError ||
                    code in listOf(404, 408, 480, 503)
                ) {
                    st = "Unavailable"
                    tag = "(Off)"
                    dur = if (duration > 0) " ($durationFormatted)" else ""
                    off = true
                } else if (reason == Reason.Declined || code == 603) {
                    st = "Declined"
                    tag = "(Declined)"
                    busy = true
                } else {
                    st = if (duration > 0) "Ended $durationFormatted" else "Ended"
                    tag = "(Ended)"
                    dur = if (duration > 0) " ($durationFormatted)" else ""
                }
            }
            else -> {
                st = LinphoneUtils.callStateToString(s)
                tag = "(${LinphoneUtils.callStateToString(s)})"
            }
        }

        val sub = ""

        displayName = MutableLiveData(rawNum)
        phoneNumber = MutableLiveData(rawNum)
        statusTag = MutableLiveData(tag)
        durationText = MutableLiveData(dur)
        state = MutableLiveData(st)
        isPaused = MutableLiveData(paused)
        isEnded = MutableLiveData(ended)
        isBusy = MutableLiveData(busy)
        isOff = MutableLiveData(off)
        isCurrent = MutableLiveData(isCur)
        subStatusText = MutableLiveData(sub)
    }

    private fun <T> MutableLiveData<T>.postValueIfChanged(newValue: T) {
        if (value != newValue) {
            postValue(newValue)
        }
    }

    fun updateStatus(isCur: Boolean) {
        val s = call.state
        val duration = call.duration
        val durationFormatted = formatDuration(duration)

        isCurrent.postValueIfChanged(isCur)
        isPaused.postValueIfChanged(LinphoneUtils.isCallPaused(s))

        var newState: String
        var newStatusTag: String
        var newDurationText: String
        var newEnded = false
        var newBusy = false
        var newOff = false

        when (s) {
            Call.State.OutgoingInit, Call.State.OutgoingProgress -> {
                newState = "Calling..."
                newStatusTag = "(Calling)"
                newDurationText = ""
                newEnded = false
                newBusy = false
                newOff = false
            }
            Call.State.OutgoingRinging, Call.State.OutgoingEarlyMedia -> {
                newState = "Ringing..."
                newStatusTag = "(Ringing)"
                newDurationText = ""
                newEnded = false
                newBusy = false
                newOff = false
            }
            Call.State.Connected, Call.State.StreamsRunning -> {
                newState = "Connected $durationFormatted"
                newStatusTag = "(Received)"
                newDurationText = " ($durationFormatted)"
                newEnded = false
                newBusy = false
                newOff = false
            }
            Call.State.Pausing, Call.State.Paused, Call.State.PausedByRemote -> {
                newState = "On Hold $durationFormatted"
                newStatusTag = "(Hold)"
                newDurationText = " ($durationFormatted)"
                newEnded = false
                newBusy = false
                newOff = false
            }
            Call.State.End, Call.State.Error, Call.State.Released -> {
                newEnded = true
                val reason = call.reason
                val code = call.errorInfo?.protocolCode ?: 0
                if (reason == Reason.Busy || code == 486) {
                    newState = "Busy"
                    newStatusTag = "(Busy)"
                    newDurationText = ""
                    newBusy = true
                    newOff = false
                } else if (reason == Reason.NotFound || reason == Reason.NotAnswered ||
                    reason == Reason.TemporarilyUnavailable || reason == Reason.IOError ||
                    code in listOf(404, 408, 480, 503)
                ) {
                    newState = "Unavailable"
                    newStatusTag = "(Off)"
                    newDurationText = if (duration > 0) " ($durationFormatted)" else ""
                    newBusy = false
                    newOff = true
                } else if (reason == Reason.Declined || code == 603) {
                    newState = "Declined"
                    newStatusTag = "(Declined)"
                    newDurationText = ""
                    newBusy = true
                    newOff = false
                } else {
                    newState = if (duration > 0) "Ended $durationFormatted" else "Ended"
                    newStatusTag = "(Ended)"
                    newDurationText = if (duration > 0) " ($durationFormatted)" else ""
                    newBusy = false
                    newOff = false
                }
            }
            else -> {
                newState = LinphoneUtils.callStateToString(s)
                newStatusTag = "(${LinphoneUtils.callStateToString(s)})"
                newDurationText = ""
            }
        }

        state.postValueIfChanged(newState)
        statusTag.postValueIfChanged(newStatusTag)
        durationText.postValueIfChanged(newDurationText)
        isEnded.postValueIfChanged(newEnded)
        isBusy.postValueIfChanged(newBusy)
        isOff.postValueIfChanged(newOff)

        val sub = ""
        subStatusText.postValueIfChanged(sub)
    }

    // Only accessed from the Core thread
    private var lastTickDuration = -1
    private var lastTickState: Call.State? = null

    @WorkerThread
    fun tickDuration(isCur: Boolean) {
        val s = call.state
        if (isCurrent.value != isCur) {
            isCurrent.postValue(isCur)
        }
        if (s == Call.State.Connected || s == Call.State.StreamsRunning ||
            s == Call.State.Paused || s == Call.State.PausedByRemote
        ) {
            val dur = call.duration
            // Same second + same state -> nothing to post (avoids useless main thread work)
            if (dur == lastTickDuration && s == lastTickState) return
            lastTickDuration = dur
            lastTickState = s
            // The call cell only displays durationText; statusTag/state are refreshed by updateStatus()
            // when the call state really changes, so we no longer post them every second.
            durationText.postValue(" (${formatDuration(dur)})")
        }
    }

    private fun formatDuration(seconds: Int): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return StringBuilder(5)
            .append(if (mins < 10) "0" else "").append(mins)
            .append(':')
            .append(if (secs < 10) "0" else "").append(secs)
            .toString()
    }

    @WorkerThread
    fun destroy() {
    }

    @WorkerThread
    fun togglePauseResume() {
        when (call.state) {
            Call.State.Paused -> {
                Log.i("$TAG Trying to resume call [${call.remoteAddress.asStringUriOnly()}]")
                call.resume()
            }
            Call.State.StreamsRunning -> {
                Log.i("$TAG Trying to pause call [${call.remoteAddress.asStringUriOnly()}]")
                call.pause()
            }
            else -> {
                Log.i("$TAG Call in state [${call.state}], skipping pause/resume")
            }
        }
    }

    private var hangUpRequested = false

    @UiThread
    fun hangUp() {
        if (hangUpRequested || isEnded.value == true) return
        hangUpRequested = true
        // Instant visual feedback so user sees immediate response with zero lag
        isEnded.value = true
        statusTag.value = "(Ending...)"
        subStatusText.value = ""
        durationText.value = ""

        coreContext.postOnCoreThread {
            try {
                Log.i("$TAG Terminating call [$id]")
                coreContext.terminateCall(call)
            } catch (e: Throwable) {
                Log.e("$TAG Error terminating call [$id]: ${e.message}")
            }
        }
    }
}
