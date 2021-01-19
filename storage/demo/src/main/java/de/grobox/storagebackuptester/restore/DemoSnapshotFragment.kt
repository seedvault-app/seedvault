package de.grobox.storagebackuptester.restore

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.Button
import androidx.fragment.app.activityViewModels
import de.grobox.storagebackuptester.MainViewModel
import de.grobox.storagebackuptester.R
import org.calyxos.backup.storage.api.SnapshotItem
import org.calyxos.backup.storage.ui.restore.SnapshotFragment

class DemoSnapshotFragment : SnapshotFragment() {

    override val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val v = super.onCreateView(inflater, container, savedInstanceState)
        val bottomStub: ViewStub = v.findViewById(R.id.bottomStub)
        bottomStub.layoutResource = R.layout.footer_snapshot
        val footer = bottomStub.inflate()
        footer.findViewById<Button>(R.id.button).setOnClickListener {
            requireActivity().onBackPressed()
        }
        return v
    }

    override fun onSnapshotClicked(item: SnapshotItem) {
        viewModel.onSnapshotClicked(item)
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, RestoreFragment.newInstance())
            .addToBackStack("RESTORE")
            .commit()
    }

}
