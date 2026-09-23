package io.github.superthom196.maa

import android.app.Application

class MaaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}
