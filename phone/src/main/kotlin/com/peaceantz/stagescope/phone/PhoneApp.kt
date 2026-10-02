package com.peaceantz.stagescope.phone

import android.app.Application

class PhoneApp : Application() {
    lateinit var container: PhoneContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = PhoneContainer(this)
    }
}
