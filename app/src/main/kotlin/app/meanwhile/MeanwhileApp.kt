package app.meanwhile

import android.app.Application
import app.meanwhile.di.AppContainer
import app.meanwhile.notify.Notifications

class MeanwhileApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        container = AppContainer(this)
        container.start()
    }
}
