package app.meanwhile

import app.meanwhile.log.AppLog
import android.app.Application
import app.meanwhile.di.AppContainer
import app.meanwhile.notify.Notifications

class MeanwhileApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        CrashLog.install(this)
        AppLog.i(
            "App",
            "process start · v${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA.ifBlank { "local" }}) · " +
                "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})",
        )
        Notifications.createChannels(this)
        container = AppContainer(this)
        container.start()
    }
}
