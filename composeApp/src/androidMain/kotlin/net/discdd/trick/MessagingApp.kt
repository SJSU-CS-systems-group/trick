package net.discdd.trick

import android.app.Application
import net.discdd.trick.di.initKoin

class MessagingApp : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin()
    }
}

