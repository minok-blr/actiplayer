package com.flowstate.app

import android.app.Application
import com.flowstate.app.di.AppContainer

class FlowStateApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
