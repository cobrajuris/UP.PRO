package com.uppro.nero.notebook

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import com.uppro.nero.NeroApp
import com.uppro.nero.R
import com.uppro.nero.data.NeroState
import com.uppro.nero.data.NotebookStatus
import com.uppro.nero.data.Store
import com.uppro.nero.data.TransferDirection
import com.uppro.nero.data.TransferInfo
import com.uppro.nero.ui.MainActivity
import kotlin.concurrent.thread

/** Mantém o servidor do notebook rodando em primeiro plano. */
class NotebookService : Service() {

    private var server: NotebookServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.init(this)
        if (intent?.action == ACTION_STOP) {
            startInForeground(null)
            stopSelf()
            return START_NOT_STICKY
        }
        Outbox.load(this)
        val ip = NotebookServer.localIp()
        if (server == null) {
            val s = NotebookServer(applicationContext)
            try {
                s.start()
                server = s
            } catch (e: Exception) {
                NeroState.setNotebook(NotebookStatus(false, null, "Não foi possível abrir a porta do servidor."))
                startInForeground(null)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val url = ip?.let { "http://$it:${server?.port}" }
        NeroState.setNotebook(
            NotebookStatus(true, url, if (ip == null) "Conecte o celular ao mesmo Wi‑Fi do notebook." else null),
        )
        startInForeground(url)
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        NeroState.setNotebook(NotebookStatus(false, null))
        super.onDestroy()
    }

    private fun startInForeground(url: String?) {
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, "notebook"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 3, Intent(this, NotebookService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pin = Store.settings.value.notebookPin
        val n = Notification.Builder(this, NeroApp.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_nero)
            .setContentTitle("Notebook conectado ao Nero")
            .setContentText(if (url != null) "Abra $url no notebook · PIN $pin" else "Conecte o celular ao Wi‑Fi")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Desligar", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 43
        private const val ACTION_STOP = "com.uppro.nero.NOTEBOOK_STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, NotebookService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NotebookService::class.java))
        }

        /** Copia os arquivos para a fila e liga o servidor. */
        fun queue(context: Context, uris: List<Uri>, onDone: (Int) -> Unit = {}) {
            val app = context.applicationContext
            start(app)
            thread(name = "nero-queue") {
                var count = 0
                var lastName = ""
                var lastSize = 0L
                uris.forEach { uri ->
                    Outbox.add(app, uri)?.let {
                        count++
                        lastName = it.name
                        lastSize = it.size
                    }
                }
                if (count > 0) {
                    val label = if (count == 1) lastName else "$count arquivos"
                    NeroState.setTransfer(TransferInfo(label, lastSize, 0, TransferDirection.READY, false, System.currentTimeMillis()))
                }
                onDone(count)
            }
        }
    }
}

/** Recebe o "Compartilhar → Enviar ao notebook" de qualquer app. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = buildList {
            when (intent?.action) {
                Intent.ACTION_SEND -> {
                    @Suppress("DEPRECATION")
                    (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { add(it) }
                }
                Intent.ACTION_SEND_MULTIPLE -> {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { addAll(it) }
                }
            }
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, "Nada para enviar. Compartilhe um arquivo ou foto.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val main = android.os.Handler(mainLooper)
        val app = applicationContext
        NotebookService.queue(this, uris) { count ->
            main.post {
                val url = NeroState.notebook.value.url
                val msg = when {
                    count == 0 -> "Não consegui ler o arquivo."
                    url != null -> "Pronto! No notebook abra $url"
                    else -> "Na fila. Conecte o celular ao Wi‑Fi."
                }
                Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
                // Só fecha depois da cópia: a permissão de leitura do arquivo acaba com a Activity.
                finish()
            }
        }
    }
}
