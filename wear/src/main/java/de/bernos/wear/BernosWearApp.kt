package de.bernos.wear

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class BernosWearApp : Application() {

    /** Verbindung zur Handy-App; lebt so lange wie der Prozess. */
    lateinit var phone: PhoneLink
        private set

    override fun onCreate() {
        super.onCreate()
        phone = PhoneLink(this, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        phone.start()
    }
}

val Context.phone: PhoneLink get() = (applicationContext as BernosWearApp).phone
