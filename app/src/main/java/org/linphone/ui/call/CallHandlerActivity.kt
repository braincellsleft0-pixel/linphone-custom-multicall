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
package org.linphone.ui.call

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Call
import org.linphone.core.tools.Log
import org.linphone.utils.LinphoneUtils
import java.util.concurrent.atomic.AtomicLong

/**
 * Invisible trampoline activity to handle tel: and sip: intents from browser or other apps.
 * Initiates the call via CoreContext without opening a duplicate window or tab in floating/mini screen mode.
 */
class CallHandlerActivity : Activity() {
    companion object {
        private const val TAG = "[Call Handler Activity]"
        private val callGeneration = AtomicLong(0)
        private var lastDialedNumber: String? = null
        private var lastDialedTimestamp: Long = 0L

        fun cancelAllPendingCalls() {
            val gen = callGeneration.incrementAndGet()
            synchronized(this) {
                lastDialedNumber = null
                lastDialedTimestamp = 0L
            }
            Log.w("$TAG Cancelled all in-flight and pending call intents (generation=$gen)")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        overridePendingTransition(0, 0)
        super.onCreate(savedInstanceState)
        handleCallIntent(intent)
        finishAndRemoveTask()
        overridePendingTransition(0, 0)
    }

    override fun onNewIntent(intent: Intent?) {
        overridePendingTransition(0, 0)
        super.onNewIntent(intent)
        handleCallIntent(intent)
        finishAndRemoveTask()
        overridePendingTransition(0, 0)
    }

    private fun handleCallIntent(intent: Intent?) {
        val uri = intent?.data?.toString()
        if (uri.isNullOrEmpty()) {
            Log.e("$TAG Intent data is null or empty, cannot process call intent")
            return
        }

        Log.i("$TAG Handling call intent with URI: $uri")
        val sipUriToCall = when {
            uri.startsWith("tel:") -> uri.substring("tel:".length)
            uri.startsWith("callto:") -> uri.substring("callto:".length)
            uri.startsWith("sip-linphone:") -> uri.replace("sip-linphone:", "sip:")
            uri.startsWith("linphone-sip:") -> uri.replace("linphone-sip:", "sip:")
            uri.startsWith("sips-linphone:") -> uri.replace("sips-linphone:", "sips:")
            uri.startsWith("linphone-sips:") -> uri.replace("linphone-sips:", "sips:")
            else -> uri.replace("%40", "@")
        }

        var cleanNumber = sipUriToCall
        if (cleanNumber.contains("?")) {
            cleanNumber = cleanNumber.substringBefore("?")
        }
        cleanNumber = cleanNumber.trimStart('/', ' ')
        if (!cleanNumber.contains("@")) {
            cleanNumber = cleanNumber.replace(" ", "").replace("-", "").replace("(", "").replace(")", "")
        }

        val now = System.currentTimeMillis()
        synchronized(Companion) {
            if (cleanNumber == lastDialedNumber && now - lastDialedTimestamp < 1500L) {
                Log.w("$TAG Ignoring duplicate rapid call intent for [$cleanNumber] within 1.5s (${now - lastDialedTimestamp}ms elapsed)")
                return
            }
            lastDialedNumber = cleanNumber
            lastDialedTimestamp = now
        }

        val thisGeneration = callGeneration.get()

        Thread {
            try {
                while (!coreContext.isReady()) {
                    if (thisGeneration != callGeneration.get()) {
                        Log.w("$TAG Call intent for [$cleanNumber] aborted while waiting for core (generation changed)")
                        return@Thread
                    }
                    Thread.sleep(50)
                }

                if (thisGeneration != callGeneration.get()) {
                    Log.w("$TAG Call intent for [$cleanNumber] aborted before posting to core thread")
                    return@Thread
                }

                coreContext.postOnCoreThread { core ->
                    if (thisGeneration != callGeneration.get()) {
                        Log.w("$TAG Call intent for [$cleanNumber] aborted on core thread (generation changed)")
                        return@postOnCoreThread
                    }

                    var address = core.interpretUrl(
                        cleanNumber,
                        LinphoneUtils.applyInternationalPrefix()
                    )
                    if (address == null) {
                        address = core.interpretUrl(cleanNumber, false)
                    }
                    if (address == null) {
                        address = core.interpretUrl(cleanNumber, true)
                    }
                    if (address == null) {
                        val domain = core.defaultAccount?.params?.identityAddress?.domain
                        if (!domain.isNullOrEmpty()) {
                            address = core.interpretUrl("sip:$cleanNumber@$domain", false)
                        }
                    }

                    Log.i("$TAG Interpreted address: ${address?.asStringUriOnly()}")
                    if (address != null) {
                        // Avoid redundant duplicate outgoing invites to the exact same destination
                        val alreadyConnecting = core.calls.any {
                            !LinphoneUtils.isCallEnding(it.state, considerReleasedAsEnding = true) &&
                            (it.remoteAddress.weakEqual(address) || (!address.username.isNullOrEmpty() && it.remoteAddress.username == address.username)) &&
                            (
                                it.state == Call.State.OutgoingInit || it.state == Call.State.OutgoingProgress ||
                             it.state == Call.State.OutgoingRinging || it.state == Call.State.OutgoingEarlyMedia ||
                             it.state == Call.State.Connected || it.state == Call.State.StreamsRunning ||
                             it.state == Call.State.Paused || it.state == Call.State.Pausing || it.state == Call.State.Resuming
                            )
                        }
                        if (alreadyConnecting) {
                            Log.w("$TAG Call to [${address.asStringUriOnly()}] is already connecting/active, skipping duplicate invite")
                            coreContext.postOnMainThread {
                                val appContext = coreContext.context
                                if (org.linphone.ui.call.bubble.FloatingBubbleManager.canDrawOverlays(appContext)) {
                                    org.linphone.ui.call.bubble.FloatingBubbleManager.show(cleanNumber)
                                } else {
                                    coreContext.showCallActivity()
                                }
                            }
                            return@postOnCoreThread
                        }

                        coreContext.startAudioCall(address, skipNetworkReachabilityTest = true)
                        coreContext.postOnMainThread {
                            val appContext = coreContext.context
                            if (org.linphone.ui.call.bubble.FloatingBubbleManager.canDrawOverlays(appContext)) {
                                org.linphone.ui.call.bubble.FloatingBubbleManager.show(cleanNumber)
                            } else {
                                coreContext.showCallActivity()
                            }
                        }
                    } else {
                        Log.e("$TAG Failed to interpret SIP address from [$cleanNumber]")
                    }
                }
            } catch (e: Exception) {
                Log.e("$TAG Error while initiating call from intent: $e")
            }
        }.start()
    }
}
