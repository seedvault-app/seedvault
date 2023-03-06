package com.stevesoltys.seedvault.settings

import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.transport.backup.PackageService
import org.koin.android.ext.android.inject

class AboutDialogFragment : Fragment() {

    private val packageService: PackageService by inject()

    companion object {
        internal val TAG = AboutDialogFragment::class.java.simpleName
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val v: View = inflater.inflate(R.layout.fragment_about, container, false)

        val versionName = packageService.getVersionName(requireContext().packageName) ?: "???"
        val versionView: TextView = v.findViewById(R.id.versionView)
        versionView.text = getString(R.string.about_version, versionName)

        val linkMovementMethod = LinkMovementMethod.getInstance()
        val contributorsView = v.findViewById<TextView>(R.id.contributorView)
        val orgsView = v.findViewById<TextView>(R.id.about_contributing_organizations_content)
        contributorsView.movementMethod = linkMovementMethod
        orgsView.movementMethod = linkMovementMethod

        return v
    }

    override fun onStart() {
        super.onStart()
        activity?.setTitle(R.string.about_title)
    }

}
