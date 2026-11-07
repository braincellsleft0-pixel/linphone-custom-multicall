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

import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Reason
import org.linphone.core.tools.Log
import org.linphone.ui.GenericViewModel
import org.linphone.ui.call.model.CallModel
import org.linphone.utils.AppUtils
import org.linphone.utils.Event
import org.linphone.utils.LinphoneUtils

class CallsViewModel
    @UiThread
    constructor() : GenericViewModel() {
    companion object {
        private const val TAG = "[Calls ViewModel]"
        var focusedCall: Call? = null
    }

    val calls = MutableLiveData<ArrayList<CallModel>>()

    val callsExceptCurrentOne = MutableLiveData<ArrayList<CallModel>>()

    val callsCount = MutableLiveData<Int>()

    val allCallsIntoConference = MutableLiveData<Boolean>()

    val showTopBar = MutableLiveData<Boolean>()

    val goToActiveCallEvent = MutableLiveData<Event<Boolean>>()

    val showIncomingCallEvent = MutableLiveData<Event<Boolean>>()

    val showOutgoingCallEvent = MutableLiveData<Event<Boolean>>()

    val noCallFoundEvent = MutableLiveData<Event<Boolean>>()

    val callsTopBarLabel = MutableLiveData<String>()

    val callsTopBarIcon = MutableLiveData<Int>()

    val callsTopBarStatus = MutableLiveData<String>()

    val goToCallsListEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    // Synchronous call list kept strictly on the Core Thread to prevent race conditions & duplicate models
    internal val modelsList = arrayListOf<CallModel>()

    // Core thread only. Coalesces bursts of call events (end-all, many calls ringing...) into ONE UI refresh
    private var listRefreshPending = false
    private var lastOtherCallsIds: List<String>? = null

    @WorkerThread
    private fun scheduleListRefresh() {
        if (listRefreshPending) return
        listRefreshPending = true
        viewModelScope.launch {
            delay(120)
            coreContext.postOnCoreThread { core ->
                listRefreshPending = false
                calls.postValue(ArrayList(modelsList))
                callsCount.postValue(
                    core.calls.count { !LinphoneUtils.isCallEnding(it.state, considerReleasedAsEnding = true) }
                )
                updateOtherCallsInfo()
            }
        }
    }

    @UiThread
    fun terminateAllCalls() {
        Log.i("$TAG Terminating all calls with instant UI feedback")
        org.linphone.ui.call.CallHandlerActivity.cancelAllPendingCalls()
        ArrayList(modelsList).forEach { model ->
            model.isEnded.value = true
            model.statusTag.value = "(Ending...)"
            model.subStatusText.value = ""
            model.durationText.value = ""
        }
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
        }
    }

    @WorkerThread
    fun markCallEnding(targetCall: Call) {
        val targetNum = targetCall.remoteAddress?.username
        modelsList.filter {
            it.call == targetCall || (!targetNum.isNullOrEmpty() && it.call.remoteAddress?.username == targetNum)
        }.forEach { model ->
            model.isEnded.postValue(true)
            model.statusTag.postValue("(Ending...)")
            model.subStatusText.postValue("")
            model.durationText.postValue("")
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
            Log.i("$TAG Call [${call.remoteAddress?.asStringUriOnly()}] state changed [$state]")

            if (state == Call.State.OutgoingInit) {
                if (focusedCall == null || LinphoneUtils.isCallEnding(focusedCall?.state ?: Call.State.End, considerReleasedAsEnding = true)) {
                    focusedCall = call
                    Log.i("$TAG Asking activity to show outgoing call fragment for new call [${call.remoteAddress?.asStringUriOnly()}]")
                    showOutgoingCallEvent.postValue(Event(true))
                } else {
                    Log.i("$TAG Additional outgoing call [${call.remoteAddress?.asStringUriOnly()}] started in background while [${focusedCall?.remoteAddress?.asStringUriOnly()}] is focused")
                }
            }

            // Update calls list synchronously on Core Thread
            val found = modelsList.find {
                it.call == call
            }
            if (found == null) {
                if (state != Call.State.End && state != Call.State.Released && state != Call.State.Error) {
                    Log.i(
                        "$TAG Found a call [${call.remoteAddress.asStringUriOnly()}] not yet in calls list, adding unique model"
                    )
                    val model = CallModel(call)
                    modelsList.add(model)
                    scheduleListRefresh()
                }
            } else {

                if (state == Call.State.Released || state == Call.State.Error) {
                    Log.i(
                        "$TAG Call [${call.remoteAddress?.asStringUriOnly()}] released, removing from list after delay"
                    )
                    viewModelScope.launch {
                        delay(600)
                        coreContext.postOnCoreThread {
                            if (found != null && modelsList.remove(found)) {
                                found.destroy()
                                updateCallsSelection()
                                scheduleListRefresh()
                            }
                        }
                    }
                }
            }

            // Fast path: update only the call that changed, instead of all 10 calls on every packet!
            val cur = focusedCall ?: core.currentCall ?: core.calls.firstOrNull {
                !LinphoneUtils.isCallEnding(it.state, considerReleasedAsEnding = true)
            } ?: core.calls.firstOrNull()
            found?.updateStatus(isCur = (cur != null && found.call == cur))
            scheduleListRefresh()

            // Update currently displayed fragment according to call state
            val currentFocus = focusedCall ?: core.currentCall
            if (currentFocus != null && call != currentFocus) {
                if (state == Call.State.Connected || state == Call.State.StreamsRunning) {
                    Log.i(
                        "$TAG Call [${call.remoteAddress.asStringUriOnly()}] answered in background while focused on [${currentFocus.remoteAddress.asStringUriOnly()}]. Auto-holding background call!"
                    )
                    if (state == Call.State.StreamsRunning) {
                        call.pause()
                    }
                    // Do NOT navigate to active call for this background call! Keep user on current screen!
                }
            } else {
                if (call == core.currentCall || core.currentCall == null) {
                    Log.i(
                        "$TAG Current call [${call.remoteAddress.asStringUriOnly()}] state changed [$state]"
                    )
                    when (call.state) {
                        Call.State.Connected -> {
                            goToActiveCallEvent.postValue(Event(call.conference == null))
                        }
                        else -> {
                        }
                    }
                }
            }

            if (LinphoneUtils.isCallIncoming(call.state)) {
                Log.i("$TAG Asking activity to show incoming call fragment")
                showIncomingCallEvent.postValue(Event(true))
            } else if (LinphoneUtils.isCallEnding(call.state)) {
                val wasFocused = (focusedCall != null && call == focusedCall)
                val remainingCalls = core.calls.filter {
                    it != call && !LinphoneUtils.isCallEnding(it.state, considerReleasedAsEnding = true)
                }

                if (remainingCalls.isEmpty()) {
                    Log.i("$TAG All calls have ended, finishing activity and bubble")
                    focusedCall = null
                    org.linphone.ui.call.CallHandlerActivity.cancelAllPendingCalls()
                    noCallFoundEvent.postValue(Event(true))
                    coreContext.postOnMainThread {
                        org.linphone.ui.call.bubble.FloatingBubbleManager.dismiss()
                    }
                } else if (wasFocused) {
                    val newCurrentCall = remainingCalls.first()
                    focusedCall = newCurrentCall
                    Log.i("$TAG Focused call shifted smoothly to [${newCurrentCall.remoteAddress.asStringUriOnly()}]")
                    if (newCurrentCall.state == Call.State.Paused) {
                        Log.i("$TAG Auto-resuming remaining call [${newCurrentCall.remoteAddress.asStringUriOnly()}]")
                        newCurrentCall.resume()
                    }
                    updateCallsSelection()
                    // Keep user in active multi-call list without disruptive fragment recreation
                } else {
                    Log.i("$TAG Background call [${call.remoteAddress.asStringUriOnly()}] ended, keeping focused call")
                }
            }
        }
    }

    init {
        showTopBar.value = false

        viewModelScope.launch {
            while (isActive) {
                delay(1000)
                coreContext.postOnCoreThread { core ->
                    val current = focusedCall ?: core.currentCall ?: core.calls.firstOrNull {
                        !LinphoneUtils.isCallEnding(it.state, considerReleasedAsEnding = true)
                    } ?: core.calls.firstOrNull()
                    modelsList.forEach { model ->
                        model.tickDuration(current != null && model.call == current)
                    }
                }
            }
        }

        coreContext.postOnCoreThread { core ->
            core.addListener(coreListener)

            modelsList.clear()
            if (core.callsNb > 0) {
                val currentCall = core.currentCall ?: core.calls.first()
                for (call in core.calls) {
                    val model = CallModel(call, isCur = (call == currentCall))
                    modelsList.add(model)
                }
                calls.postValue(ArrayList(modelsList))
                callsCount.postValue(modelsList.size)
                Log.i("$TAG There is [${modelsList.size}] calls")
                Log.i("$TAG Current call is [${currentCall.remoteAddress.asStringUriOnly()}]")

                when (currentCall.state) {
                    Call.State.Connected, Call.State.StreamsRunning, Call.State.Paused, Call.State.Pausing, Call.State.PausedByRemote, Call.State.UpdatedByRemote, Call.State.Updating -> {
                        goToActiveCallEvent.postValue(Event(currentCall.conference == null))
                    }
                    Call.State.OutgoingInit, Call.State.OutgoingRinging, Call.State.OutgoingProgress, Call.State.OutgoingEarlyMedia -> {
                        showOutgoingCallEvent.postValue(Event(true))
                    }
                    Call.State.IncomingReceived, Call.State.IncomingEarlyMedia -> {
                        showIncomingCallEvent.postValue(Event(true))
                    }
                    else -> {}
                }

                updateCallsSelection()
                updateOtherCallsInfo()
            } else {
                Log.w("$TAG No call found, leaving Call activity")
                noCallFoundEvent.postValue(Event(true))
            }
        }
    }

    @WorkerThread
    fun updateCallsSelection() {
        val core = coreContext.core
        val current = focusedCall ?: core.currentCall ?: core.calls.firstOrNull {
            !LinphoneUtils.isCallEnding(it.state, considerReleasedAsEnding = true)
        } ?: core.calls.firstOrNull()
        modelsList.forEach { model ->
            val isCur = (current != null && model.call == current)
            model.updateStatus(isCur)
        }
    }

    @UiThread
    override fun onCleared() {
        super.onCleared()

        coreContext.postOnCoreThread { core ->
            modelsList.forEach(CallModel::destroy)
            modelsList.clear()
            callsCount.postValue(0)
            core.removeListener(coreListener)
        }
    }

    @UiThread
    fun topBarClicked() {
        coreContext.postOnCoreThread { core ->
            if (core.callsNb == 1) {
                val currentCall = core.currentCall ?: core.calls.first()
                goToActiveCallEvent.postValue(Event(currentCall.conference == null))
            } else {
                goToCallsListEvent.postValue(Event(true))
            }
        }
    }

    @UiThread
    fun switchToCall(targetCall: Call, callViewModel: CurrentCallViewModel? = null) {
        coreContext.postOnCoreThread { core ->
            if (LinphoneUtils.isCallEnding(targetCall.state, considerReleasedAsEnding = true)) {
                Log.w("$TAG Target call [${targetCall.remoteAddress.asStringUriOnly()}] is ending, cannot switch")
                return@postOnCoreThread
            }

            Log.i("$TAG Switching directly to call [${targetCall.remoteAddress.asStringUriOnly()}]")
            focusedCall = targetCall
            callViewModel?.configureCall(targetCall)

            // Pause all other calls that are streaming audio
            for (c in core.calls) {
                if (c != targetCall && c.state == Call.State.StreamsRunning) {
                    Log.i("$TAG Auto-pausing call [${c.remoteAddress.asStringUriOnly()}]")
                    c.pause()
                }
            }

            // Resume target call if paused
            if (targetCall.state == Call.State.Paused) {
                Log.i("$TAG Auto-resuming call [${targetCall.remoteAddress.asStringUriOnly()}]")
                targetCall.resume()
            }

            updateCallsSelection()
            updateOtherCallsInfo()

            if (LinphoneUtils.isCallOutgoing(targetCall.state)) {
                showOutgoingCallEvent.postValue(Event(true))
            } else {
                goToActiveCallEvent.postValue(Event(targetCall.conference == null))
            }
        }
    }

    @UiThread
    fun switchToNextCall() {
        coreContext.postOnCoreThread { core ->
            val activeCalls = core.calls.filter {
                !LinphoneUtils.isCallEnding(it.state, considerReleasedAsEnding = true)
            }
            if (activeCalls.size < 2) {
                Log.w("$TAG Cannot switch calls, only [${activeCalls.size}] active call(s)")
                return@postOnCoreThread
            }

            val current = focusedCall ?: core.currentCall ?: activeCalls.first()
            val currentIndex = activeCalls.indexOf(current)
            val nextIndex = if (currentIndex >= 0) (currentIndex + 1) % activeCalls.size else 0
            val nextCall = activeCalls[nextIndex]

            Log.i(
                "$TAG Switching call from [${current.remoteAddress.asStringUriOnly()}] to [${nextCall.remoteAddress.asStringUriOnly()}]"
            )
            focusedCall = nextCall

            // Pause all other calls that are streaming audio
            for (c in activeCalls) {
                if (c != nextCall && c.state == Call.State.StreamsRunning) {
                    Log.i("$TAG Auto-pausing call [${c.remoteAddress.asStringUriOnly()}]")
                    c.pause()
                }
            }

            // Resume next call if it was paused
            if (nextCall.state == Call.State.Paused) {
                Log.i("$TAG Auto-resuming next call [${nextCall.remoteAddress.asStringUriOnly()}]")
                nextCall.resume()
            }

            updateCallsSelection()
            updateOtherCallsInfo()

            if (LinphoneUtils.isCallOutgoing(nextCall.state)) {
                showOutgoingCallEvent.postValue(Event(true))
            } else {
                goToActiveCallEvent.postValue(Event(nextCall.conference == null))
            }
        }
    }

    @UiThread
    fun mergeCallsIntoConference() {
        coreContext.postOnCoreThread { core ->
            val callsCount = core.callsNb
            val defaultAccount = LinphoneUtils.getDefaultAccount()
            val subject = if (defaultAccount != null && defaultAccount.params.audioVideoConferenceFactoryAddress != null) {
                Log.i("$TAG Merging [$callsCount] calls into a remotely hosted conference")
                AppUtils.getString(R.string.conference_remotely_hosted_title)
            } else {
                Log.i("$TAG Merging [$callsCount] calls into a locally hosted conference")
                AppUtils.getString(R.string.conference_locally_hosted_title)
            }

            val conference = LinphoneUtils.createGroupCall(defaultAccount, subject)
            if (conference == null) {
                Log.e("$TAG Failed to create conference!")
                showRedToast(R.string.conference_failed_to_merge_calls_into_conference_toast, R.drawable.warning_circle)
            } else {
                conference.addParticipants(core.calls)
                allCallsIntoConference.postValue(true)
            }
        }
    }

    @WorkerThread
    private fun updateOtherCallsInfo() {
        val cur = focusedCall ?: coreContext.core.currentCall ?: modelsList.firstOrNull {
            it.isEnded.value != true
        }?.call

        val otherCalls = modelsList.filter { it.call != cur }
        val otherIds = otherCalls.map { it.id }
        if (otherIds != lastOtherCallsIds) {
            lastOtherCallsIds = otherIds
            callsExceptCurrentOne.postValue(ArrayList(otherCalls))
        }

        val activeCount = modelsList.count { it.isEnded.value != true }
        if (activeCount > 1) {
            val curNum = cur?.remoteAddress?.username ?: LinphoneUtils.getDisplayName(cur?.remoteAddress)
            val newLabel = if (!curNum.isNullOrEmpty()) "$activeCount Calls • $curNum" else "$activeCount Calls"
            if (callsTopBarLabel.value != newLabel) {
                callsTopBarLabel.postValue(newLabel)
            }
            val pausedCount = modelsList.count { it.isPaused.value == true }
            val ringingCount = (activeCount - pausedCount).coerceAtLeast(0)
            val status = when {
                pausedCount > 0 && ringingCount > 0 -> "$pausedCount on hold, $ringingCount ringing"
                pausedCount > 0 -> "$pausedCount on hold"
                ringingCount > 0 -> "$ringingCount ringing"
                else -> ""
            }
            if (callsTopBarStatus.value != status) {
                callsTopBarStatus.postValue(status)
            }
        } else if (coreContext.core.callsNb == 1) {
            configureTopBarForSingleCallOrConference()
        }
    }

    private fun configureTopBarForSingleCallOrConference() {
        callsTopBarIcon.postValue(R.drawable.phone)

        val call = coreContext.core.calls.first()
        val conference = call.conference
        if (conference != null) {
            callsTopBarLabel.postValue(conference.subject)
        } else {
            val remoteAddress = call.callLog.remoteAddress
            val displayName = LinphoneUtils.getDisplayName(remoteAddress)
            callsTopBarLabel.postValue(displayName)
        }
        callsTopBarStatus.postValue(LinphoneUtils.callStateToString(call.state))
    }
}
