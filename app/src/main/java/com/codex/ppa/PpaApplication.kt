package com.codex.ppa

import android.app.Application
import android.content.Context
import com.codex.ppa.worker.AutoClassificationWorker

class PpaApplication : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        AutoClassificationWorker.ensureNotificationChannel(this)
        appContainer = AppContainer(this)
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as PpaApplication).appContainer
