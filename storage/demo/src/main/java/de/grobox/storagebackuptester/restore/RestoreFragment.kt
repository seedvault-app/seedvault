package de.grobox.storagebackuptester.restore

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.RecyclerView
import de.grobox.storagebackuptester.LogAdapter
import de.grobox.storagebackuptester.MainViewModel
import de.grobox.storagebackuptester.R

class RestoreFragment : Fragment() {

    companion object {
        fun newInstance() = RestoreFragment()
    }

    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var list: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var horizontalProgressBar: ProgressBar
    private lateinit var button: Button
    private val adapter = LogAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        setHasOptionsMenu(true)
        val v = inflater.inflate(R.layout.fragment_log, container, false)
        list = v.findViewById(R.id.listView)
        list.adapter = adapter
        progressBar = v.findViewById(R.id.progressBar)
        horizontalProgressBar = v.findViewById(R.id.horizontalProgressBar)
        button = v.findViewById(R.id.button)
        button.visibility = GONE
        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewModel.restoreLog.observe(viewLifecycleOwner, { progress ->
            progress.text?.let { adapter.addItem(it) }
            horizontalProgressBar.max = progress.total
            horizontalProgressBar.setProgress(progress.current, true)
            list.postDelayed({
                list.scrollToPosition(adapter.itemCount - 1)
            }, 50)
        })
        viewModel.restoreProgressVisible.observe(viewLifecycleOwner, { visible ->
            progressBar.visibility = if (visible) VISIBLE else INVISIBLE
        })
    }

    override fun onStart() {
        super.onStart()
        requireActivity().title = "Restore"
    }

}
