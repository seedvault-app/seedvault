/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.ui.check

import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.settings.SettingsViewModel
import org.koin.androidx.viewmodel.ext.android.activityViewModel

internal const val WARN_PERCENT = 25
internal const val WARN_BYTES = 1024 * 1024 * 1024 // 1 GB

class AppCheckFragment : Fragment() {

    private val viewModel: SettingsViewModel by activityViewModel()
    private lateinit var sliderLabel: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val v = inflater.inflate(R.layout.fragment_app_check, container, false) as ScrollView

        val slider = v.requireViewById<Slider>(R.id.slider)
        sliderLabel = v.requireViewById(R.id.sliderLabel)

        // label not scrolling will be fixed in material-components 1.12.0 (next update)
        slider.setLabelFormatter { value ->
            viewModel.backupSize.value?.let {
                Formatter.formatShortFileSize(context, (it * value / 100).toLong())
            } ?: "${value.toInt()}%"
        }
        slider.addOnChangeListener { _, value, _ ->
            onSliderChanged(value)
        }

        viewModel.backupSize.observe(viewLifecycleOwner) {
            if (it != null) {
                slider.labelBehavior = LabelFormatter.LABEL_VISIBLE
                slider.invalidate()
                onSliderChanged(slider.value)
            }
            // we can stop observing as the loaded size won't change again
            viewModel.backupSize.removeObservers(viewLifecycleOwner)
        }

        v.requireViewById<Button>(R.id.startButton).setOnClickListener {
            viewModel.checkAppBackups(slider.value.toInt())
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        return v
    }

    override fun onStart() {
        super.onStart()
        activity?.setTitle(null)
        viewModel.loadBackupSize()
    }

    private fun onSliderChanged(value: Float) {
        val size = viewModel.backupSize.value
        // when size is unknown, we show warning based on percent
        val showWarning = if (size == null) {
            value > WARN_PERCENT
        } else {
            size * value / 100 > WARN_BYTES
        }
        // only update label visibility when different from before
        val newVisibility = if (showWarning) View.VISIBLE else View.GONE
        if (sliderLabel.visibility != newVisibility) {
            sliderLabel.visibility = newVisibility
        }
    }
}
