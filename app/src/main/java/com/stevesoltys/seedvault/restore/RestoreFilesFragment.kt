package com.stevesoltys.seedvault.restore

import android.app.Activity.RESULT_OK
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.Button
import androidx.fragment.app.Fragment
import com.google.android.setupdesign.GlifLayout
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.ui.getColorAccent
import org.calyxos.backup.storage.api.SnapshotItem
import org.calyxos.backup.storage.ui.restore.SnapshotFragment
import org.koin.androidx.viewmodel.ext.android.sharedViewModel

internal class RestoreFilesFragment : SnapshotFragment() {
    override val viewModel: RestoreViewModel by sharedViewModel()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val v = super.onCreateView(inflater, container, savedInstanceState)

        val topStub: ViewStub = v.findViewById(R.id.topStub)
        topStub.layoutResource = R.layout.header_snapshots
        val header = topStub.inflate()
        val suw_layout: GlifLayout = header.findViewById(R.id.setup_wizard_layout)
        val icon: Drawable = suw_layout.getIcon()
        icon.setTintList(getColorAccent(requireContext()))
        suw_layout.setIcon(icon)

        val bottomStub: ViewStub = v.findViewById(R.id.bottomStub)
        bottomStub.layoutResource = R.layout.footer_snapshots
        val footer = bottomStub.inflate()
        val skipView: Button = footer.findViewById(R.id.skipView)
        skipView.setOnClickListener {
            requireActivity().apply {
                setResult(RESULT_OK)
                finishAfterTransition()
            }
        }
        return v
    }

    override fun onSnapshotClicked(item: SnapshotItem) {
        viewModel.startFilesRestore(item)
    }
}

internal class RestoreFilesStartedFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val v: View = inflater.inflate(R.layout.fragment_restore_files_started, container, false)

        val suw_layout: GlifLayout = v.findViewById(R.id.setup_wizard_layout)
        val icon: Drawable = suw_layout.getIcon()
        icon.setTintList(getColorAccent(requireContext()))
        suw_layout.setIcon(icon)

        val button: Button = v.findViewById(R.id.button)
        button.setOnClickListener {
            requireActivity().apply {
                setResult(RESULT_OK)
                finishAfterTransition()
            }
        }
        return v
    }
}
