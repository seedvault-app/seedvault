package com.stevesoltys.seedvault.settings

import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import com.stevesoltys.seedvault.R
import kotlinx.android.synthetic.main.fragment_about.*

class AboutDialogFragment : DialogFragment() {

    companion object {
        internal val TAG = AboutDialogFragment::class.java.simpleName
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_about, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val linkMovementMethod = LinkMovementMethod.getInstance()
        licenseView.movementMethod = linkMovementMethod
        authorView.movementMethod = linkMovementMethod
        sponsorView.movementMethod = linkMovementMethod
    }

}
