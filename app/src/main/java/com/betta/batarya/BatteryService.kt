package com.betta.batarya

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.abs

class BatteryService : Service() {

    private val channelId = "battery_service_channel"
    private lateinit var batteryManager: BatteryManager
    private lateinit var handler: Handler
    private var wakeLock: PowerManager.WakeLock? = null
    private val updateInterval = 1000L 

    private var internalMah: Double = -1.0
    private val designCapacity: Double = 3075.0
    private var isForegroundStarted = false
    
    private var sessionStartTime: Long = 0
    private var sessionStartLevel: Int = 0
    private var totalCurrentSum: Double = 0.0
    private var sampleCount: Int = 0
    private var sessionProcessedMah: Double = 0.0
    private var lastChargingState: Boolean? = null

    companion object {
        private val _batteryDataFlow = MutableStateFlow<BatteryData?>(null)
        val batteryDataFlow = _batteryDataFlow.asStateFlow()
    }

    override fun onCreate() {
        super.onCreate()
        batteryManager = getSystemService(BATTERY_SERVICE) as BatteryManager
        handler = Handler(Looper.getMainLooper())
        
        val initialData = getBatteryInfo()
        val notification = buildNotification(initialData)
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, notification)
            }
            isForegroundStarted = true
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BMS::ServiceWakeLock")
            wakeLock?.acquire(10 * 60 * 1000L)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        createNotificationChannel()
        resetSession()

        handler.post(object : Runnable {
            override fun run() {
                updateNotification()
                handler.postDelayed(this, updateInterval)
            }
        })
    }

    private fun resetSession() {
        sessionStartTime = System.currentTimeMillis()
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        sessionStartLevel = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        totalCurrentSum = 0.0
        sampleCount = 0
        sessionProcessedMah = 0.0
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_SERVICE") {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun getBatteryInfo(): BatteryData {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val temperature = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN) ?: 0
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        if (lastChargingState != null && lastChargingState != isCharging) resetSession()
        lastChargingState = isCharging

        val currentNow = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        var currentMa = abs(currentNow).toDouble()
        if (currentMa > 10000) currentMa /= 1000.0
        
        val remainingMah = designCapacity * (level / 100.0)

        totalCurrentSum += currentMa
        sampleCount++
        val delta = currentMa / 3600.0
        sessionProcessedMah += delta

        if (internalMah < 0) {
            internalMah = remainingMah
        } else {
            val currentTrend = if (isCharging) internalMah + delta else internalMah - delta
            internalMah = (remainingMah * 0.95) + (currentTrend * 0.05)
        }

        val elapsedHours = (System.currentTimeMillis() - sessionStartTime) / 3600000.0
        val percentPerHour = if (elapsedHours > 0.01) abs(level - sessionStartLevel) / elapsedHours else 0.0
        
        val healthText = when(health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "İyi"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Sıcak"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Ölü"
            else -> "Normal"
        }

        val sourceText = when(plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "Priz"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB/PC"
            else -> "Pilde"
        }

        val data = BatteryData(
            level, currentMa, internalMah, voltage / 1000.0, 
            temperature / 10.0, isCharging, totalCurrentSum / sampleCount, 
            percentPerHour, sessionProcessedMah, healthText, sourceText
        )
        
        _batteryDataFlow.value = data
        return data
    }

    private fun updateNotification() {
        if (!isForegroundStarted) return
        val data = getBatteryInfo()
        val notification = buildNotification(data)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1, notification)
    }

    private fun buildNotification(data: BatteryData): android.app.Notification {
        val stopIntent = Intent(this, BatteryService::class.java).apply { action = "STOP_SERVICE" }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val statusLabel = if (data.isCharging) "⚡ ${data.source}" else "🔋 Deşarj"
        val row1 = String.format(Locale.US, "%s: %.0f mA | %s", statusLabel, data.currentMa, data.health)
        val row2 = String.format(Locale.US, "Ort: %.0f mA | %.1f%%/sa", data.averageMa, data.percentPerHour)
        val row3 = String.format(Locale.US, "Kalan: %.0f mAh | %.2fV | %.1f°C", data.remainingMah, data.voltage, data.temperature)

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Batarya Analizi: %${data.level}")
            .setContentText(row1)
            .setSmallIcon(R.drawable.ic_battery_notif)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Durdur", stopPendingIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$row1\n$row2\n$row3"))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "BMS Takibi", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    data class BatteryData(
        val level: Int, val currentMa: Double, val remainingMah: Double,
        val voltage: Double, val temperature: Double, val isCharging: Boolean,
        val averageMa: Double, val percentPerHour: Double, val sessionProcessedMah: Double, 
        val health: String, val source: String
    )
}
