/*
 * AniLight-compatible subset of CloudStream's Android Plugin host API.
 * Derived from CloudStream GPL-3.0 source at:
 * https://github.com/recloudstream/cloudstream/tree/a28e6468f54cfe420feebd205d3d21d08ed0cde8
 *
 * This host intentionally implements the Plugin members used by AniLight.
 * It does not claim compatibility with app-only VideoClickAction integrations.
 */
package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.res.Resources

abstract class Plugin : BasePlugin() {
    open fun load(context: Context) {
        load()
    }

    var resources: Resources? = null
    var openSettings: ((context: Context) -> Unit)? = null
}
