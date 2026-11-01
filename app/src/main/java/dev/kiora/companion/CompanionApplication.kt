package dev.kiora.companion

import android.app.Application

class CompanionApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        InferenceResources.initialize(this)
    }
}
