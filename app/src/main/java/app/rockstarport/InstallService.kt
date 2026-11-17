package app.rockstarport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

class InstallService : Service() {
    override fun onBind(i: Intent?): IBinder? = null

    private fun notif(text: String, pct: Int, indeterminate: Boolean): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "install").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("ROCKSTAR PORT").setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(pi).setProgress(100, pct, indeterminate).build()
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        L.tr = Store(this).tr
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("install", "Install", NotificationManager.IMPORTANCE_LOW))
        val first = notif(L.t("Preparing…", "Hazırlanıyor…"), 0, true)
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(1, first)
        val uriStr = i?.getStringExtra("uri")
        if (uriStr == null || Inst.running) { stopForeground(Service.STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        Inst.running = true; Inst.cancel = false; Inst.message = ""; Inst.progress = null; Inst.notifyUi()
        Thread {
            val wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rp:install")
            wl.acquire(12L * 3600 * 1000)
            var lastN = 0L
            try {
                Inst.message = Installer.run(this, Uri.parse(uriStr), Dirs.dest) { p ->
                    Inst.progress = p; Inst.notifyUi()
                    val now = System.currentTimeMillis()
                    if (now - lastN > 1000) {
                        lastN = now
                        val pct = if (p.total > 0) (p.done * 100 / p.total).toInt() else 0
                        nm.notify(1, notif("${p.phase} $pct%", pct, p.total == 0L))
                    }
                }
            } catch (t: Throwable) {
                Inst.message = L.t("Install failed: ", "Kurulum başarısız: ") + (t.message ?: t.javaClass.simpleName)
            } finally {
                wl.release(); Inst.running = false; Inst.progress = null; Inst.notifyUi()
                stopForeground(Service.STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }
}
