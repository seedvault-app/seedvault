package com.stevesoltys.seedvault.settings

import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.stevesoltys.seedvault.R

class AboutDialogFragment : DialogFragment() {

    private lateinit var licenseView: TextView
    private lateinit var authorView: TextView
    private lateinit var designView: TextView
    private lateinit var sponsorView: TextView

    companion object {
        internal val TAG = AboutDialogFragment::class.java.simpleName
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        val v: View = inflater.inflate(R.layout.fragment_about, container, false)

        licenseView = v.findViewById(R.id.licenseView)
        authorView = v.findViewById(R.id.authorView)
        designView = v.findViewById(R.id.designView)
        sponsorView = v.findViewById(R.id.sponsorView)

        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val linkMovementMethod = LinkMovementMethod.getInstance()
        licenseView.movementMethod = linkMovementMethod
        authorView.movementMethod = linkMovementMethod
        designView.movementMethod = linkMovementMethod
        sponsorView.movementMethod = linkMovementMethod
    }

}
