package com.uppro.nero.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Note(val id: Long, val text: String, val createdAt: Long)

data class Reminder(
    val id: Long,
    val title: String,
    /** Horário em epoch millis. */
    val at: Long,
    val calendarEventId: Long? = null,
    val done: Boolean = false,
)

data class Settings(
    val overlayEnabled: Boolean = false,
    val minimizeInFullscreen: Boolean = true,
    val minimizeInLandscape: Boolean = true,
    val syncCalendar: Boolean = true,
    val albumName: String = "",
    val albumLink: String = "",
    val notebookPin: String = "",
    /** Pílula no canto direito (true) ou esquerdo (false, padrão). */
    val pillRight: Boolean = false,
    /** Distância da pílula até a base, como fração da altura da tela (0 = bem embaixo). */
    val pillY: Float = 0.02f,
)

/** Persistência simples em JSON (notas e lembretes) e SharedPreferences (ajustes). */
object Store {

    private lateinit var dir: File
    private lateinit var appContext: Context

    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    private val _reminders = MutableStateFlow<List<Reminder>>(emptyList())
    val reminders: StateFlow<List<Reminder>> = _reminders.asStateFlow()

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    @Volatile
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        dir = appContext.filesDir
        _notes.value = readNotes()
        _reminders.value = readReminders()
        val p = appContext.getSharedPreferences("nero", Context.MODE_PRIVATE)
        var pin = p.getString("pin", "") ?: ""
        if (pin.length != 4) {
            pin = (1000..9999).random().toString()
            p.edit().putString("pin", pin).apply()
        }
        _settings.value = Settings(
            overlayEnabled = p.getBoolean("overlay", false),
            minimizeInFullscreen = p.getBoolean("fullscreen", true),
            minimizeInLandscape = p.getBoolean("landscape", true),
            syncCalendar = p.getBoolean("calendar", true),
            albumName = p.getString("albumName", "") ?: "",
            albumLink = p.getString("albumLink", "") ?: "",
            notebookPin = pin,
            pillRight = p.getBoolean("pillRight", false),
            pillY = p.getFloat("pillBottom", 0.02f),
        )
        initialized = true
    }

    // ---------------- Ajustes ----------------

    fun updateSettings(transform: (Settings) -> Settings) {
        val s = transform(_settings.value)
        _settings.value = s
        appContext.getSharedPreferences("nero", Context.MODE_PRIVATE).edit()
            .putBoolean("overlay", s.overlayEnabled)
            .putBoolean("fullscreen", s.minimizeInFullscreen)
            .putBoolean("landscape", s.minimizeInLandscape)
            .putBoolean("calendar", s.syncCalendar)
            .putString("albumName", s.albumName)
            .putString("albumLink", s.albumLink)
            .putString("pin", s.notebookPin)
            .putBoolean("pillRight", s.pillRight)
            .putFloat("pillBottom", s.pillY)
            .apply()
    }

    // ---------------- Notas ----------------

    fun addNote(text: String): Note {
        val note = Note(System.currentTimeMillis(), text.trim(), System.currentTimeMillis())
        _notes.value = listOf(note) + _notes.value
        saveNotes()
        return note
    }

    fun updateNote(id: Long, text: String) {
        _notes.value = _notes.value.map { if (it.id == id) it.copy(text = text.trim()) else it }
        saveNotes()
    }

    fun deleteNote(id: Long) {
        _notes.value = _notes.value.filterNot { it.id == id }
        saveNotes()
    }

    // ---------------- Lembretes ----------------

    fun addReminder(reminder: Reminder) {
        _reminders.value = (_reminders.value + reminder).sortedBy { it.at }
        saveReminders()
    }

    fun updateReminder(id: Long, transform: (Reminder) -> Reminder) {
        _reminders.value = _reminders.value.map { if (it.id == id) transform(it) else it }
        saveReminders()
    }

    fun deleteReminder(id: Long) {
        _reminders.value = _reminders.value.filterNot { it.id == id }
        saveReminders()
    }

    fun reminder(id: Long): Reminder? = _reminders.value.firstOrNull { it.id == id }

    // ---------------- Arquivos ----------------

    private fun readNotes(): List<Note> = runCatching {
        val f = File(dir, "notes.json")
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Note(o.getLong("id"), o.getString("text"), o.optLong("createdAt", o.getLong("id")))
        }
    }.getOrDefault(emptyList())

    private fun saveNotes() {
        val arr = JSONArray()
        _notes.value.forEach { arr.put(JSONObject().put("id", it.id).put("text", it.text).put("createdAt", it.createdAt)) }
        writeAtomic("notes.json", arr.toString())
    }

    private fun readReminders(): List<Reminder> = runCatching {
        val f = File(dir, "reminders.json")
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Reminder(
                id = o.getLong("id"),
                title = o.getString("title"),
                at = o.getLong("at"),
                calendarEventId = if (o.has("event")) o.getLong("event") else null,
                done = o.optBoolean("done", false),
            )
        }.sortedBy { it.at }
    }.getOrDefault(emptyList())

    private fun saveReminders() {
        val arr = JSONArray()
        _reminders.value.forEach {
            val o = JSONObject().put("id", it.id).put("title", it.title).put("at", it.at).put("done", it.done)
            it.calendarEventId?.let { e -> o.put("event", e) }
            arr.put(o)
        }
        writeAtomic("reminders.json", arr.toString())
    }

    @Synchronized
    private fun writeAtomic(name: String, content: String) {
        runCatching {
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(content)
            val target = File(dir, name)
            if (!tmp.renameTo(target)) {
                target.writeText(content)
                tmp.delete()
            }
        }
    }
}
