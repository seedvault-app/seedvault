package de.grobox.storagebackuptester.scanner

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.View.FOCUS_DOWN
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.UiThread
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
        setHasOptionsMenu(true)
        requireActivity().title = arguments?.getString("name")
        val v = inflater.inflate(R.layout.fragment_scan, container, false)
        scrollView = v.findViewById(R.id.scrollView)
        logView = v.findViewById(R.id.logView)
        progressBar = v.findViewById(R.id.progressBar)
        loadText()
        return v
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.fragment_scan, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
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
            else -> super.onOptionsItemSelected(item)
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
