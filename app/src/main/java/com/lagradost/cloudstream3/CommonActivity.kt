package com.lagradost.cloudstream3

import android.app.Activity
import java.lang.ref.WeakReference

/**
 * Minimal host-activity compatibility surface used by CloudStream providers.
 *
 * This intentionally implements only the activity reference contract. It is
 * not CloudStream's full CommonActivity UI/service implementation.
 */
object CommonActivity {
    private var activityReference: WeakReference<Activity>? = null

    var activity: Activity?
        get() = activityReference?.get()
        private set(value) {
            activityReference = value?.let(::WeakReference)
        }

    fun setActivityInstance(newActivity: Activity?) {
        activity = newActivity
    }
}
