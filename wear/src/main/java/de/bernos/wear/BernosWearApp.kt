package de.bernos.wear

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class BernosWearApp : Application() {

    /** Verbindung zur Handy-App; lebt so lange wie der Prozess. */
    lateinit var phone: PhoneLink
        private set

    override fun onCreate() {
        super.onCreate()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        phone = PhoneLink(this, scope)
        phone.start()
        // Kachel und Komplikation nur bei Änderungen neu zeichnen, die sie auch zeigen.
        scope.launch {
            combine(phone.state.map { NowPlayingSummary.from(it) }, phone.tileCover.map { it?.version }) { summary, cover -> summary to cover }
                .distinctUntilChanged()
                .collect { WearSurfaces.requestUpdate(this@BernosWearApp) }
        }
    }
}

val Context.phone: PhoneLink get() = (applicationContext as BernosWearApp).phone
