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
package org.linphone.ui.call.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.UiThread
import androidx.core.view.doOnLayout
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.core.Call
import org.linphone.core.tools.Log
import org.linphone.databinding.CallOutgoingFragmentBinding
import org.linphone.ui.call.adapter.CallsListAdapter
import org.linphone.ui.call.viewmodel.CallsViewModel
import org.linphone.ui.call.viewmodel.CurrentCallViewModel
import org.linphone.utils.LinphoneUtils

@UiThread
class OutgoingCallFragment : GenericCallFragment() {
    companion object {
        private const val TAG = "[Outgoing Call Fragment]"
    }

    private lateinit var binding: CallOutgoingFragmentBinding

    private lateinit var callViewModel: CurrentCallViewModel
    private lateinit var callsViewModel: CallsViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = CallOutgoingFragmentBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        callViewModel = requireActivity().run {
            ViewModelProvider(this)[CurrentCallViewModel::class.java]
        }
        observeToastEvents(callViewModel)

        callsViewModel = requireActivity().run {
            ViewModelProvider(this)[CallsViewModel::class.java]
        }

        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewModel = callViewModel
        binding.callsViewModel = callsViewModel
        binding.numpadModel = callViewModel.numpadModel

        binding.btnMinimizeFloating?.setOnClickListener {
            (requireActivity() as org.linphone.ui.call.CallActivity).minimizeFloatingWindow()
        }

        val callsAdapter = CallsListAdapter()
        binding.inCallCallsList?.apply {
            setHasFixedSize(true)
            setItemViewCacheSize(25)
            itemAnimator = null
            layoutManager = LinearLayoutManager(requireContext())
            adapter = callsAdapter
        }

        callsViewModel.calls.observe(viewLifecycleOwner) { list ->
            callsAdapter.submitList(list?.toList())
        }

        callsAdapter.callClickedEvent.observe(viewLifecycleOwner) {
            it.consume { model ->
                if (model.isEnded.value == true) {
                    return@consume
                }
                callsViewModel.switchToCall(model.call, callViewModel)
            }
        }

        callViewModel.isOutgoingEarlyMedia.observe(viewLifecycleOwner) { earlyMedia ->
            if (earlyMedia) {
                coreContext.postOnCoreThread { core ->
                    val call = core.calls.find {
                        it.state == Call.State.OutgoingEarlyMedia
                    }
                    if (call != null && LinphoneUtils.isVideoEnabled(call)) {
                        Log.i("$TAG Outgoing early-media call with video, setting preview surface")
                        core.nativePreviewWindowId = binding.localPreviewVideoSurface
                    }
                }
            }
        }

        val numpadBottomSheetBehavior = BottomSheetBehavior.from(binding.callNumpad.root)
        numpadBottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        numpadBottomSheetBehavior.skipCollapsed = true

        callViewModel.showNumpadBottomSheetEvent.observe(viewLifecycleOwner) {
            it.consume {
                numpadBottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }

        binding.newCall?.setOnClickListener {
            if (findNavController().currentDestination?.id == R.id.outgoingCallFragment) {
                val action = OutgoingCallFragmentDirections.actionOutgoingCallFragmentToNewCallFragment()
                findNavController().navigate(action)
            }
        }
    }

    override fun onResume() {
        super.onResume()

        (binding.root as? ViewGroup)?.doOnLayout {
            setupVideoPreview(binding.localPreviewVideoSurface)
        }
    }

    override fun onPause() {
        super.onPause()

        cleanVideoPreview(binding.localPreviewVideoSurface)
    }
}
