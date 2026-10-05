package app.meanwhile.data.cgm

import android.app.Notification
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import app.meanwhile.domain.cgm.CgmReading
import app.meanwhile.domain.cgm.EversenseNotification
import app.meanwhile.service.EversenseListener
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.time.Instant

/** What a CGM app's notification showed, as the listener saw it. */
data class CompanionNotification(
    val packageName: String,
    val texts: List<String>,
    val descriptions: List<String>,
    val postedAt: Instant,
)

/**
 * The built-in Eversense interceptor (spec §13.1's "future built-in Eversense interceptor"): readings
 * come from the official Eversense app's glucose notification through [EversenseListener], so no
 * bridge app is needed. A notification shows only the current value, so there is no history to
 * back-fill from — xDrip+, when installed, still back-fills gaps.
 */
class EversenseSource : CgmSource {
    override val name = EversenseNotification.SOURCE

    private val accepted = MutableSharedFlow<CgmReading>(extraBufferCapacity = 16)

    override suspend fun fetchSince(since: Instant): List<CgmReading> = emptyList()

    /** Readings the intake accepted from the Eversense notification. */
    override fun live(): Flow<CgmReading> = accepted

    internal fun emit(reading: CgmReading) {
        accepted.tryEmit(reading)
    }

    companion object {
        fun listenerComponent(context: Context) = ComponentName(context, EversenseListener::class.java)

        /** Notification access granted to Meanwhile (Settings → Notification access). */
        fun accessGranted(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        fun anyInstalled(context: Context): Boolean = EversenseNotification.PACKAGES.any { installed(context, it) }

        /** Opens Meanwhile's own notification-access switch (the general list if that page is missing). */
        fun openAccessSettings(context: Context) {
            val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listenerComponent(context).flattenToString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(detail)
            } catch (_: ActivityNotFoundException) {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }

        fun installed(context: Context, pkg: String): Boolean = try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}

/** Every visible text of a notification: standard fields plus the TextViews of a custom layout. */
object NotificationTexts {
    @Suppress("DEPRECATION") // contentView & co. are how other apps' custom layouts arrive
    fun extract(context: Context, n: Notification, packageName: String, postedAt: Instant): CompanionNotification {
        val texts = LinkedHashSet<String>()
        val descriptions = LinkedHashSet<String>()
        val extras = n.extras
        if (extras != null) {
            for (key in listOf(
                Notification.EXTRA_TITLE, Notification.EXTRA_TITLE_BIG, Notification.EXTRA_TEXT,
                Notification.EXTRA_SUB_TEXT, Notification.EXTRA_INFO_TEXT, Notification.EXTRA_SUMMARY_TEXT,
                Notification.EXTRA_BIG_TEXT,
            )) {
                extras.getCharSequence(key)?.toString()?.let(texts::add)
            }
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { texts += it.toString() }
        }
        for (views in listOf(n.contentView, n.bigContentView, n.headsUpContentView)) {
            collect(context, views, texts, descriptions)
        }
        return CompanionNotification(packageName, texts.filter { it.isNotBlank() }, descriptions.filter { it.isNotBlank() }, postedAt)
    }

    private fun collect(context: Context, views: RemoteViews?, texts: MutableSet<String>, descriptions: MutableSet<String>) {
        if (views == null) return
        val root = views.apply(context, FrameLayout(context))
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE) return
            v.contentDescription?.toString()?.let(descriptions::add)
            when (v) {
                is TextView -> v.text?.toString()?.let(texts::add)
                is ViewGroup -> for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
    }
}
