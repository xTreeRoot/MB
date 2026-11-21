package com.xtreeroot.mb.account

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import androidx.core.app.ServiceCompat
import com.xtreeroot.mb.R

internal class QrSignInService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var expiry: Runnable? = null
    private var currentStart = 0

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val attempt = active
        if (attempt == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            val title = getString(R.string.qr_sign_in_waiting)
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channel, getString(R.string.qr_sign_in), NotificationManager.IMPORTANCE_LOW),
            )
            val notification = Notification.Builder(this, channel).setContentTitle(title)
                .setSmallIcon(R.drawable.ic_music_note).setOngoing(true).setShowWhen(false).build()
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0
            ServiceCompat.startForeground(this, notificationId, notification, type)
        } catch (failure: RuntimeException) {
            Log.w(logTag, "foreground unavailable: ${failure.javaClass.simpleName}")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        currentStart = startId
        expiry?.let(handler::removeCallbacks)
        expiry = Runnable {
            if (active === attempt) {
                active = null
                stopSelf(startId)
                attempt()
            }
        }.also { handler.postDelayed(it, protectionMs) }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) {
        if (startId == currentStart) expiry?.run()
    }

    override fun onDestroy() {
        expiry?.let(handler::removeCallbacks)
        super.onDestroy()
    }

    companion object {
        private const val channel = "mb-qr-sign-in"
        private const val notificationId = 7_301
        private const val protectionMs = 150_000L
        private const val logTag = "MBQrSignIn"
        private var active: (() -> Unit)? = null

        @MainThread
        fun protect(context: Context, expired: () -> Unit): () -> Unit {
            val app = context.applicationContext
            val intent = Intent(app, QrSignInService::class.java)
            try {
                if (app.startService(intent) != null) active = expired
            } catch (failure: RuntimeException) {
                Log.w(logTag, "service unavailable: ${failure.javaClass.simpleName}")
            }
            return {
                if (active === expired) {
                    active = null
                    app.stopService(intent)
                }
            }
        }
    }
}
