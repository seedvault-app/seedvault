/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package de.grobox.storagebackuptester.scanner

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.View.FOCUS_DOWN
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.UiThread
import androidx.appcompat.widget.Toolbar
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import de.grobox.storagebackuptester.MainViewModel
import de.grobox.storagebackuptester.R
import kotlinx.coroutines.launch

private const val EMAIL = "incoming+grote-storage-backup-tester-22079635-issue-@incoming.gitlab.com"

open class MediaScanFragment : Fragment() {

    companion object {
        fun newInstance(name: String, uri: Uri) = MediaScanFragment().apply {
            arguments = Bundle().apply {
                putString("name", name)
                putString("uri", uri.toString())
            }
        }
    }

    protected val viewModel: MainViewModel by activityViewModels()

    private lateinit var scrollView: NestedScrollView
    protected lateinit var logView: TextView
    protected lateinit var progressBar: ProgressBar

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val v = inflater.inflate(R.layout.fragment_scan, container, false)
        scrollView = v.findViewById(R.id.scrollView)
        logView = v.findViewById(R.id.logView)
        progressBar = v.findViewById(R.id.progressBar)
        loadText()
        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.requireViewById<Toolbar>(R.id.toolbar).apply {
            title = arguments?.getString("name")
            setOnMenuItemClickListener(::onMenuItemSelected)
            setNavigationOnClickListener {
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    private fun onMenuItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.refresh -> {
                loadText()
                true
            }
            R.id.share -> {
                val sendIntent: Intent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(EMAIL))
                    putExtra(Intent.EXTRA_SUBJECT, arguments?.getString("name"))
                    putExtra(Intent.EXTRA_TEXT, logView.text)
                    type = "text/plain"
                }
                val shareIntent = Intent.createChooser(sendIntent, null)
                startActivity(shareIntent)
                true
            }
            else -> false
        }
    }

    private fun loadText() {
        progressBar.visibility = VISIBLE
        lifecycleScope.launch {
            logView.text = getText()
            logView.postDelayed({
                scrollView.fullScroll(FOCUS_DOWN)
            }, 50)
            progressBar.visibility = INVISIBLE
        }
    }

    @UiThread
    protected open suspend fun getText(): String {
        Log.e("TEST", "loading text")
        return viewModel.scanMediaUri(getUri())
    }

    protected fun getUri(): Uri {
        return Uri.parse(arguments?.getString("uri"))
    }

}
