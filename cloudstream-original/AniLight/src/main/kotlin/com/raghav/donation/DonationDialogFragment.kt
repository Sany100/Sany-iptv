package com.raghav.donation

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment

/**
 * Donation UI is disabled in SANY IPTV.
 *
 * Keep the class as a binary/source compatibility shim for plugin code that
 * still references DonationDialogFragment, but immediately dismiss it.
 */
class DonationDialogFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return Dialog(requireContext()).apply {
            setOnShowListener {
                if (isAdded) dismissAllowingStateLoss()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dismissAllowingStateLoss()
    }
}
