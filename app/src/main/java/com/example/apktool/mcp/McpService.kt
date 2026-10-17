package com.example.apktool.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.example.apktool.MainActivity
import com.example.apktool.tools.McpTools

/**
 * 承载 MCP 服务器的前台服务：被系统杀掉后由 START_STICKY 拉起，
 * 通知栏常驻显示监听地址，点通知回到 App。
 */
class McpService : Service() {

    companion object {
        const val ACTION_START = "com.example.apktool.action.MCP_START"
        const val ACTION_STOP = "com.example.apktool.action.MCP_STOP"
        private const val CHANNEL_ID = "mcp"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        var running: Boolean = false
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, McpService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, McpService::class.java).setAction(ACTION_STOP)
            ctx.startService(i)
        }
    }

    private var server: McpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        McpState.load(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdownServer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startServer()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        shutdownServer()
        super.onDestroy()
    }

    private fun startServer() {
        val notification = buildNotification("MCP 服务器运行中 · 127.0.0.1:${McpState.port.value}")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (server != null) return
        try {
            val registry = McpTools.build(applicationContext)
            val s = McpServer(McpState.port.value, McpState.token.value, registry)
            s.start()
            server = s
            running = true
            McpState.running.value = true
            McpState.lastError.value = null
        } catch (t: Throwable) {
            running = false
            McpState.running.value = false
            McpState.lastError.value = "${t.javaClass.simpleName}: ${t.message}"
            McpState.append("启动失败：${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun shutdownServer() {
        server?.stop()
        server = null
        running = false
        McpState.running.value = false
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "MCP 服务器",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "本地 MCP 服务器运行状态" }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val flags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val pi = PendingIntent.getActivity(this, 0, intent, flags)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("APK 工具箱")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
