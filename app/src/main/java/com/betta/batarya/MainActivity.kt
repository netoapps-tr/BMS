package com.betta.batarya

import android.Manifest
import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.betta.batarya.ui.theme.BATARYATheme
import kotlinx.coroutines.delay
import java.util.*

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var lightValue by mutableFloatStateOf(0f)
    private var accelValues by mutableStateOf(floatArrayOf(0f, 0f, 0f))
    
    // Bildirim izni durumu için state
    private var isNotificationEnabled by mutableStateOf(true)

    private val permissionsToRequest = mutableListOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.ACCESS_NETWORK_STATE
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        checkNotificationStatus()
        startBatteryService()
    }

    private fun checkNotificationStatus() {
        isNotificationEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    private fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = "package:$packageName".toUri()
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        
        try {
            startActivity(intent)
            Toast.makeText(this, "Lütfen Bildirim İznini Veriniz.", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Ayarlar açılamadı. (Error 002)", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // Ayarlardan geri dönüldüğünde izni tekrar kontrol et
        checkNotificationStatus()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkPermissions()
        checkNotificationStatus()
        
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        registerSensors()

        setContent {
            BATARYATheme {
                if (!isNotificationEnabled) {
                    AlertDialog(
                        onDismissRequest = { },
                        title = { Text("BİLDİRİM İZNİ GEREKLİ", fontWeight = FontWeight.Black, color = Color.Red) },
                        text = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "Bildirim İznini Reddettiniz. (Error 001)",
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    "Lütfen Bildirim İznini Veriniz.",
                                    textAlign = TextAlign.Center,
                                    fontSize = 14.sp
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = { openNotificationSettings() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                            ) {
                                Text("Ayarlara Git", color = Color.White)
                            }
                        }
                    )
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = { Text("BMS", fontWeight = FontWeight.Black) },
                            actions = {
                                IconButton(onClick = { optimizeRam(this@MainActivity) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "RAM Temizle", tint = Color.Red)
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    val batteryData by BatteryService.batteryDataFlow.collectAsState()
                    DeviceInfoScreen(
                        modifier = Modifier.padding(innerPadding),
                        batteryData = batteryData,
                        lightSensor = lightValue,
                        accelSensor = accelValues
                    )
                }
            }
        }
    }

    private fun registerSensors() {
        sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        when (event?.sensor?.type) {
            Sensor.TYPE_LIGHT -> lightValue = event.values[0]
            Sensor.TYPE_ACCELEROMETER -> accelValues = event.values.clone()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun checkPermissions() {
        val notGranted = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            requestPermissionLauncher.launch(notGranted.toTypedArray())
        } else {
            startBatteryService()
        }
    }

    private fun startBatteryService() {
        val serviceIntent = Intent(this, BatteryService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
    }
}

@Composable
fun DeviceInfoScreen(
    modifier: Modifier = Modifier,
    batteryData: BatteryService.BatteryData?,
    lightSensor: Float,
    accelSensor: FloatArray
) {
    val context = LocalContext.current
    var memoryInfo by remember { mutableStateOf(getMemoryInfo(context)) }
    var runningApps by remember { mutableStateOf(getRunningApps(context)) }
    val hardwareInfo = remember { getHardwareInfo() }
    
    var downloadSpeed by remember { mutableStateOf("0 KB/s") }
    var uploadSpeed by remember { mutableStateOf("0 KB/s") }
    var connectionType by remember { mutableStateOf("Yok") }
    var lastRxBytes by remember { mutableLongStateOf(TrafficStats.getTotalRxBytes()) }
    var lastTxBytes by remember { mutableLongStateOf(TrafficStats.getTotalTxBytes()) }
    var lastTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            val currentTime = System.currentTimeMillis()
            val timeDiff = (currentTime - lastTime) / 1000.0
            if (timeDiff > 0) {
                downloadSpeed = formatSpeed((TrafficStats.getTotalRxBytes() - lastRxBytes) / timeDiff)
                uploadSpeed = formatSpeed((TrafficStats.getTotalTxBytes() - lastTxBytes) / timeDiff)
            }
            lastRxBytes = TrafficStats.getTotalRxBytes()
            lastTxBytes = TrafficStats.getTotalTxBytes()
            lastTime = currentTime

            connectionType = getConnectionType(context)
            memoryInfo = getMemoryInfo(context)
            runningApps = getRunningApps(context)
            delay(1000)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().background(Color(0xFFF5F5F5)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // DONANIM
        item { InfoCard("📱 DONANIM BİLGİLERİ", Color(0xFF607D8B)) {
            InfoRow("Model", hardwareInfo["Model"] ?: "")
            InfoRow("Marka", hardwareInfo["Marka"] ?: "")
            InfoRow("Android", hardwareInfo["Android"] ?: "")
        }}

        // BATARYA
        item { InfoCard("🔋 BATARYA ANALİZİ", Color(0xFF4CAF50)) {
            InfoRow("Yüzde", "%${batteryData?.level ?: "--"}")
            InfoRow("Sağlık", batteryData?.health ?: "--")
            InfoRow("Anlık Akım", String.format(Locale.ROOT, "%.0f mA", batteryData?.currentMa ?: 0.0))
            InfoRow("Ortalama", String.format(Locale.ROOT, "%.0f mA", batteryData?.averageMa ?: 0.0))
            InfoRow("Tüketim Hızı", String.format(Locale.ROOT, "%.1f %% / sa", batteryData?.percentPerHour ?: 0.0))
            InfoRow("Kalan Kapasite", String.format(Locale.ROOT, "%.0f mAh", batteryData?.remainingMah ?: 0.0))
            InfoRow("Sıcaklık", String.format(Locale.ROOT, "%.1f °C", batteryData?.temperature ?: 0.0))
            InfoRow("Durum", if (batteryData?.isCharging == true) "Şarj (${batteryData.source})" else "Deşarj")
        }}

        // İNTERNET
        item { InfoCard("🌐 İNTERNET VE AĞ", Color(0xFF03A9F4)) {
            InfoRow("Bağlantı", connectionType)
            InfoRow("İndirme", downloadSpeed)
            InfoRow("Yükleme", uploadSpeed)
        }}

        // HAFIZA
        item { InfoCard("💾 BELLEK & DEPOLAMA", Color(0xFF9C27B0)) {
            InfoRow("Boş RAM", memoryInfo["AvailRAM"] ?: "")
            InfoRow("Toplam RAM", memoryInfo["TotalRAM"] ?: "")
            InfoRow("Dahili Depolama", memoryInfo["Internal"] ?: "")
        }}

        // SENSÖRLER
        item { InfoCard("🧭 SENSÖRLER (CANLI)", Color(0xFFE91E63)) {
            InfoRow("Işık", String.format(Locale.ROOT, "%.1f lux", lightSensor))
            InfoRow("İvme X", String.format(Locale.ROOT, "%.2f", accelSensor[0]))
            InfoRow("İvme Y", String.format(Locale.ROOT, "%.2f", accelSensor[1]))
            InfoRow("İvme Z", String.format(Locale.ROOT, "%.2f", accelSensor[2]))
        }}

        // UYGULAMALAR
        item {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Text("🏃 ÇALIŞAN SÜREÇLER (${runningApps.size})", fontWeight = FontWeight.Bold)
                Text("Uygulamayı cidden kapatmak için yanındaki ikona basın ve 'Zorla Durdur'u seçin.", fontSize = 10.sp, color = Color.Gray)
            }
        }
        
        if (runningApps.isEmpty()) {
            item { Button(onClick = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) { Text("Erişim İzni Ver") } }
        }
        
        items(runningApps) { app ->
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(app.split(".").last().uppercase(), modifier = Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(app, modifier = Modifier.weight(2f), fontSize = 10.sp, color = Color.Gray)
                    IconButton(onClick = { killProcess(context, app) }) { 
                        Icon(Icons.Default.Warning, contentDescription = "Zorla Kapat", tint = Color(0xFFFF9800)) 
                    }
                }
            }
        }
    }
}

@Composable
fun InfoCard(title: String, accentColor: Color, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, color = accentColor, fontWeight = FontWeight.Bold)
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = accentColor.copy(alpha = 0.1f))
            content()
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.Gray, fontSize = 13.sp)
        Text(value, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

// YARDIMCI FONKSİYONLAR
fun getHardwareInfo() = mapOf("Model" to Build.MODEL, "Marka" to Build.MANUFACTURER, "Android" to Build.VERSION.RELEASE)

fun getMemoryInfo(context: Context): Map<String, String> {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mi = ActivityManager.MemoryInfo()
    am.getMemoryInfo(mi)
    val stat = StatFs(Environment.getDataDirectory().path)
    val internal = "${(stat.availableBlocksLong * stat.blockSizeLong) / (1024 * 1024 * 1024)} GB / ${(stat.blockCountLong * stat.blockSizeLong) / (1024 * 1024 * 1024)} GB"
    return mapOf("AvailRAM" to "${mi.availMem / (1024 * 1024)} MB", "TotalRAM" to "${mi.totalMem / (1024 * 1024)} MB", "Internal" to internal)
}

fun getConnectionType(context: Context): String {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
    return when {
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Mobil Veri"
        else -> "Yok"
    }
}

fun formatSpeed(bytesPerSec: Double): String {
    val bitsPerSec = bytesPerSec * 8
    return when {
        bitsPerSec >= 1024 * 1024 -> String.format(Locale.ROOT, "%.2f Mbps", bitsPerSec / (1024 * 1024))
        bitsPerSec >= 1024 -> String.format(Locale.ROOT, "%.1f Kbps", bitsPerSec / 1024)
        else -> String.format(Locale.ROOT, "%.0f bps", bitsPerSec)
    }
}

fun getRunningApps(context: Context): List<String> {
    val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val time = System.currentTimeMillis()
    val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 60, time)
    return stats?.mapNotNull { it.packageName }?.distinct()?.filter { !it.contains("android") && !it.contains("google") }?.take(10) ?: emptyList()
}

fun killProcess(context: Context, packageName: String) {
    (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(packageName)
    
    try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = "package:$packageName".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        Toast.makeText(context, "Tam kapatmak için 'ZORLA DURDUR'a basın.", Toast.LENGTH_LONG).show()
    } catch (_: Exception) {
        Toast.makeText(context, "Ayarlar açılamadı. (Error 002)", Toast.LENGTH_SHORT).show()
    }
}

fun optimizeRam(context: Context) {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    getRunningApps(context).forEach { am.killBackgroundProcesses(it) }
    Toast.makeText(context, "Önbellek temizlendi. Tam kapatma için listeden seçin.", Toast.LENGTH_LONG).show()
}
