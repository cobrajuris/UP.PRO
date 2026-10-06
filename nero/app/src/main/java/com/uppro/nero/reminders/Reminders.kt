package com.uppro.nero.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.uppro.nero.NeroApp
import com.uppro.nero.R
import com.uppro.nero.data.AlertInfo
import com.uppro.nero.data.NeroState
import com.uppro.nero.data.Reminder
import com.uppro.nero.data.Store
import com.uppro.nero.data.Upcoming
import com.uppro.nero.overlay.OverlayService
import com.uppro.nero.ui.MainActivity
import java.util.TimeZone

/** Agenda os avisos de lembretes e do timer no AlarmManager. */
object ReminderScheduler {

    private const val ACTION_REMINDER = "com.uppro.nero.REMINDER"
    private const val ACTION_TIMER = "com.uppro.nero.TIMER"
    private const val EXTRA_ID = "id"
    private const val EXTRA_LABEL = "label"
    private const val TIMER_REQUEST = 7_000_001

    /** Cria o lembrete: salva, agenda o aviso e (se permitido) grava no Google Agenda. */
    fun create(context: Context, title: String, atMillis: Long): Reminder {
        var reminder = Reminder(id = System.currentTimeMillis(), title = title, at = atMillis)
        if (Store.settings.value.syncCalendar) {
            CalendarSync.insert(context, title, atMillis)?.let { reminder = reminder.copy(calendarEventId = it) }
        }
        Store.addReminder(reminder)
        schedule(context, reminder)
        return reminder
    }

    fun delete(context: Context, reminder: Reminder) {
        cancel(context, reminder.id)
        reminder.calendarEventId?.let { CalendarSync.delete(context, it) }
        Store.deleteReminder(reminder.id)
    }

    fun schedule(context: Context, reminder: Reminder) {
        if (reminder.done || reminder.at <= System.currentTimeMillis()) return
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_REMINDER)
            .putExtra(EXTRA_ID, reminder.id)
        setExact(context, reminder.at, pending(context, requestCode(reminder.id), intent))
    }

    fun cancel(context: Context, id: Long) {
        val intent = Intent(context, ReminderReceiver::class.java).setAction(ACTION_REMINDER).putExtra(EXTRA_ID, id)
        alarmManager(context).cancel(pending(context, requestCode(id), intent))
    }

    fun scheduleTimer(context: Context, endAtMillis: Long, label: String) {
        val intent = Intent(context, ReminderReceiver::class.java).setAction(ACTION_TIMER).putExtra(EXTRA_LABEL, label)
        setExact(context, endAtMillis, pending(context, TIMER_REQUEST, intent))
    }

    fun cancelTimer(context: Context) {
        val intent = Intent(context, ReminderReceiver::class.java).setAction(ACTION_TIMER)
        alarmManager(context).cancel(pending(context, TIMER_REQUEST, intent))
    }

    fun rescheduleAll(context: Context) {
        Store.reminders.value.forEach { schedule(context, it) }
    }

    private fun requestCode(id: Long): Int = (id xor (id ushr 32)).toInt()

    private fun pending(context: Context, code: Int, intent: Intent): PendingIntent =
        PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun alarmManager(context: Context) = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun setExact(context: Context, at: Long, pi: PendingIntent) {
        val am = alarmManager(context)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    internal fun handle(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REMINDER -> {
                val id = intent.getLongExtra(EXTRA_ID, -1)
                val reminder = Store.reminder(id) ?: return
                Store.updateReminder(id) { it.copy(done = true) }
                NeroState.setAlert(AlertInfo(reminder.title, "Lembrete · agora", System.currentTimeMillis()))
                notify(context, id.hashCode(), reminder.title, "Lembrete do Nero")
            }
            ACTION_TIMER -> {
                val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
                NeroState.setTimer(null)
                NeroState.setAlert(AlertInfo("Tempo esgotado", label.ifBlank { "Timer" }, System.currentTimeMillis()))
                notify(context, TIMER_REQUEST, "Tempo esgotado", label.ifBlank { "Seu timer terminou." })
            }
        }
    }

    private fun notify(context: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, "reminders"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, NeroApp.CH_REMINDER)
            .setSmallIcon(R.drawable.ic_stat_nero)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(id, n) }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store.init(context)
        ReminderScheduler.handle(context, intent)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store.init(context)
        ReminderScheduler.rescheduleAll(context)
        if (Store.settings.value.overlayEnabled) {
            runCatching { OverlayService.start(context) }
        }
    }
}

/** Integração com o Google Agenda pelo provedor de calendário do Android (sem login extra). */
object CalendarSync {

    fun canRead(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun canWrite(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            canRead(context)

    private fun calendarId(context: Context): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.IS_PRIMARY,
        )
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1 AND " +
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
        var best: Long? = null
        var bestScore = -1
        runCatching {
            context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val google = c.getString(1) == "com.google"
                    val primary = c.getInt(2) == 1
                    val score = (if (google) 2 else 0) + (if (primary) 1 else 0)
                    if (score > bestScore) {
                        bestScore = score
                        best = c.getLong(0)
                    }
                }
            }
        }
        return best
    }

    fun insert(context: Context, title: String, startMillis: Long): Long? {
        if (!canWrite(context)) return null
        val calId = calendarId(context) ?: return null
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, "Criado pelo Nero")
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, startMillis + 30 * 60_000L)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(CalendarContract.Events.HAS_ALARM, 0)
        }
        return runCatching {
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)?.lastPathSegment?.toLongOrNull()
        }.getOrNull()
    }

    fun delete(context: Context, eventId: Long) {
        if (!canWrite(context)) return
        runCatching {
            context.contentResolver.delete(
                android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null,
            )
        }
    }

    /** Próximo evento (não de dia inteiro) entre [from] e [to]. */
    fun nextEvent(context: Context, from: Long, to: Long): Upcoming? {
        if (!canRead(context)) return null
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(from.toString())
            .appendPath(to.toString())
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.EVENT_LOCATION,
        )
        return runCatching {
            context.contentResolver.query(
                uri, projection, "${CalendarContract.Instances.ALL_DAY} = 0", null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val begin = c.getLong(1)
                    if (begin >= from) return@use Upcoming(c.getString(0) ?: "Compromisso", begin, c.getString(2).orEmpty())
                }
                null
            }
        }.getOrNull()
    }
}
