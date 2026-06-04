package com.vietmediaf.cloudstream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class VietmediafPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(VietmediafProvider())
    }
}
