package com.betta.batarya

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.Uri
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.betta.batarya.ui.theme.BATARYATheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import androidx.core.content.edit

data class HistoryPoint(
    val current: Float,
    val event: BatteryEventType = BatteryEventType.NONE
)

enum class BatteryEventType {
    NONE, START_CHARGING, STOP_CHARGING
}

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var lightValue by mutableFloatStateOf(0f)
    private var accelValues by mutableStateOf(floatArrayOf(0f, 0f, 0f))
    
    private var isNotificationEnabled by mutableStateOf(true)
    private var isOverlayPermissionGranted by mutableStateOf(true)

    private val permissionsToRequest = mutableListOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.READ_EXTERNAL_STORAGE
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
        isNotificationEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        isOverlayPermissionGranted = Settings.canDrawOverlays(this)

        val prefs = getSharedPreferences("bms_prefs", MODE_PRIVATE)
        val isServiceEnabled = prefs.getBoolean("service_enabled", true)
        
        if (isNotificationEnabled && isServiceEnabled) {
            startBatteryService()
        }
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:$packageName".toUri()
        )
        startActivity(intent)
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
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsAndStart()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        registerSensors()

        setContent {
            val context = LocalContext.current
            val prefs = remember { context.getSharedPreferences("bms_prefs", MODE_PRIVATE) }
            val systemInDarkTheme = isSystemInDarkTheme()
            var themeMode by remember { 
                mutableIntStateOf(prefs.getInt("theme_mode", 0))
            }

            val useDarkTheme = when(themeMode) {
                1 -> false
                2 -> true
                else -> systemInDarkTheme
            }

            BATARYATheme(darkTheme = useDarkTheme) {
                var serviceEnabled by remember { mutableStateOf(prefs.getBoolean("service_enabled", true)) }
                var overlayEnabled by remember { mutableStateOf(prefs.getBoolean("overlay_enabled", true)) }
                var showDevMenu by remember { mutableStateOf(false) }

                if (!isNotificationEnabled || (serviceEnabled && !isOverlayPermissionGranted)) {
                    AlertDialog(
                        onDismissRequest = { },
                        title = { Text("           İZİN GEREKLİ!", fontWeight = FontWeight.Black, color = Color.Red) },
                        text = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (!isNotificationEnabled) {
                                    Text("Bildirim izni kapalı. Ayarlardan kapatabilirsin.", textAlign = TextAlign.Center)
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                                if (serviceEnabled && !isOverlayPermissionGranted) {
                                    Text("Ekranda gösterim izni kapalı. Ayarlardan kapatabilirsin.", textAlign = TextAlign.Center)
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = { 
                                    if (!isNotificationEnabled) openNotificationSettings()
                                    else if (!isOverlayPermissionGranted) openOverlaySettings()
                                },
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
                            title = { Text(if (showDevMenu) "GELİŞTİRİCİ MENÜSÜ" else "BMS", fontWeight = FontWeight.Black) },
                            navigationIcon = {
                                if (showDevMenu) {
                                    IconButton(onClick = { showDevMenu = false }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
                                    }
                                }
                            },
                            actions = {
                                if (!showDevMenu) {
                                    IconButton(onClick = { optimizeRam(this@MainActivity) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "RAM Temizle", tint = Color.Red)
                                    }
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    val batteryData by BatteryService.batteryDataFlow.collectAsState()
                    
                    if (showDevMenu) {
                        DeveloperMenuScreen(
                            modifier = Modifier.padding(innerPadding)
                        )
                    } else {
                        DeviceInfoScreen(
                            modifier = Modifier.padding(innerPadding),
                            batteryData = batteryData,
                            lightSensor = lightValue,
                            accelSensor = accelValues,
                            serviceEnabled = serviceEnabled,
                            overlayEnabled = overlayEnabled,
                            themeMode = themeMode,
                            onThemeChange = { mode ->
                                themeMode = mode
                                prefs.edit { putInt("theme_mode", mode) }
                            },
                            onServiceToggle = { enabled ->
                                serviceEnabled = enabled
                                prefs.edit { putBoolean("service_enabled", enabled) }
                                if (enabled) startBatteryService() else stopBatteryService()
                            },
                            onOverlayToggle = { enabled ->
                                overlayEnabled = enabled
                                prefs.edit { putBoolean("overlay_enabled", enabled) }
                                if (serviceEnabled) {
                                    val intent = Intent(context, BatteryService::class.java).apply {
                                        action = "UPDATE_OVERLAY"
                                    }
                                    context.startService(intent)
                                }
                            },
                            onOpenDevMenu = { showDevMenu = true }
                        )
                    }
                }
            }
        }
        
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val notGranted = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            requestPermissionLauncher.launch(notGranted.toTypedArray())
        } else {
            checkPermissionsAndStart()
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

    private fun startBatteryService() {
        val serviceIntent = Intent(this, BatteryService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopBatteryService() {
        val serviceIntent = Intent(this, BatteryService::class.java).apply {
            action = "STOP_SERVICE"
        }
        startService(serviceIntent)
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
    accelSensor: FloatArray,
    serviceEnabled: Boolean,
    overlayEnabled: Boolean,
    themeMode: Int,
    onThemeChange: (Int) -> Unit,
    onServiceToggle: (Boolean) -> Unit,
    onOverlayToggle: (Boolean) -> Unit,
    onOpenDevMenu: () -> Unit
) {
    val context = LocalContext.current
    var memoryInfo by remember { mutableStateOf(getMemoryInfo(context)) }
    var runningApps by remember { mutableStateOf(getRunningApps(context)) }
    val hardwareInfo = remember { getHardwareInfo() }
    
    var downloadSpeed by remember { mutableStateOf("0 B/s") }
    var uploadSpeed by remember { mutableStateOf("0 B/s") }
    var connectionType by remember { mutableStateOf("Yok") }
    var lastRxBytes by remember { mutableLongStateOf(TrafficStats.getTotalRxBytes()) }
    var lastTxBytes by remember { mutableLongStateOf(TrafficStats.getTotalTxBytes()) }
    var lastTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val currentHistory = remember { mutableStateListOf<HistoryPoint>() }
    var lastChargingState by remember { mutableStateOf<Boolean?>(null) }
    val latestBatteryData by rememberUpdatedState(batteryData)

    LaunchedEffect(Unit) {
        while (true) {
            val currentTime = System.currentTimeMillis()
            val currentRx = TrafficStats.getTotalRxBytes()
            val currentTx = TrafficStats.getTotalTxBytes()
            val timeDiff = (currentTime - lastTime) / 1000.0
            
            if (timeDiff > 0.5 && lastRxBytes != -1L) {
                val rxDiff = currentRx - lastRxBytes
                val txDiff = currentTx - lastTxBytes
                if (rxDiff >= 0) downloadSpeed = formatSpeed(rxDiff / timeDiff)
                if (txDiff >= 0) uploadSpeed = formatSpeed(txDiff / timeDiff)
            }
            
            lastRxBytes = currentRx
            lastTxBytes = currentTx
            lastTime = currentTime
            connectionType = getConnectionType(context)
            memoryInfo = getMemoryInfo(context)
            runningApps = getRunningApps(context)

            latestBatteryData?.let { data ->
                var event = BatteryEventType.NONE
                if (lastChargingState != null && lastChargingState != data.isCharging) {
                    event = if (data.isCharging) BatteryEventType.START_CHARGING else BatteryEventType.STOP_CHARGING
                }
                lastChargingState = data.isCharging
                
                currentHistory.add(HistoryPoint(data.currentMa.toFloat(), event))
                if (currentHistory.size > 60) {
                    currentHistory.removeAt(0)
                }
            }

            delay(1000)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            InfoCard("🎨 GÖRÜNÜM AYARLARI", Color(0xFF9C27B0)) {
                Column {
                    Text("Uygulama Teması", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        FilterChip(selected = themeMode == 0, onClick = { onThemeChange(0) }, label = { Text("Sistem") })
                        FilterChip(selected = themeMode == 1, onClick = { onThemeChange(1) }, label = { Text("Aydınlık") })
                        FilterChip(selected = themeMode == 2, onClick = { onThemeChange(2) }, label = { Text("Karanlık") })
                    }
                }
            }
        }

        item {
            InfoCard("⚙️ KONTROL PANELİ", Color(0xFF3F51B5)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Servis ve Bildirim", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Arka planda takip ve bildirim", fontSize = 11.sp, color = Color.Gray)
                    }
                    Switch(checked = serviceEnabled, onCheckedChange = onServiceToggle)
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color.LightGray.copy(alpha = 0.3f))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Ekranda Gösterge", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Diğer uygulamaların üzerinde göster", fontSize = 11.sp, color = Color.Gray)
                    }
                    Switch(checked = overlayEnabled, onCheckedChange = onOverlayToggle, enabled = serviceEnabled)
                }
            }
        }

        item {
            InfoCard("👨‍💻 GELİŞTİRİCİ", Color(0xFF009688)) {
                Column {
                    Text("Gelişmiş Menü", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Deneysel Özelliklerdir. Telefon Isınabilir.", fontSize = 11.sp, color = Color.Gray)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onOpenDevMenu,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("GELİŞTİRİCİ MENÜSÜ", fontWeight = FontWeight.Black)
                    }
                }
            }
        }

        item {
            InfoCard("📉 AKIM GRAFİĞİ (Anlık mA)", Color(0xFFFF5722)) {
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Tüketilen: ${String.format(Locale.ROOT, "%.2f mAh", batteryData?.sessionProcessedMah ?: 0.0)}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("Anlık: ${String.format(Locale.ROOT, "%.0f mA", batteryData?.currentMa ?: 0.0)}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    BatteryGraph(history = currentHistory)
                }
            }
        }

        item { InfoCard("📱 DONANIM BİLGİLERİ", Color(0xFF607D8B)) {
            InfoRow("Model", hardwareInfo["Model"] ?: "")
            InfoRow("Marka", hardwareInfo["Marka"] ?: "")
            InfoRow("Android", hardwareInfo["Android"] ?: "")
        }}

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

        item { InfoCard("🌐 İNTERNET VE AĞ", Color(0xFF03A9F4)) {
            InfoRow("Bağlantı", connectionType)
            InfoRow("İndirme", downloadSpeed)
            InfoRow("Yükleme", uploadSpeed)
        }}

        item { InfoCard("💾 BELLEK & DEPOLAMA", Color(0xFF9C27B0)) {
            InfoRow("Boş RAM", memoryInfo["AvailRAM"] ?: "")
            InfoRow("Toplam RAM", memoryInfo["TotalRAM"] ?: "")
            InfoRow("Dahili Depolama", memoryInfo["Internal"] ?: "")
        }}

        item { InfoCard("🧭 SENSÖRLER (CANLI)", Color(0xFFE91E63)) {
            InfoRow("Işık", String.format(Locale.ROOT, "%.1f lux", lightSensor))
            InfoRow("İvme X", String.format(Locale.ROOT, "%.2f", accelSensor[0]))
            InfoRow("İvme Y", String.format(Locale.ROOT, "%.2f", accelSensor[1]))
            InfoRow("İvme Z", String.format(Locale.ROOT, "%.2f", accelSensor[2]))
        }}

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
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
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
fun DeveloperMenuScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("bms_prefs", Context.MODE_PRIVATE) }

    var experimentalFeatureEnabled by remember { mutableStateOf(prefs.getBoolean("experimental_feature", false)) }
    
    var showDeepCleanDialog by remember { mutableStateOf(false) }
    var showDeleteAppDialog by remember { mutableStateOf(false) }
    var showFirstWarning by remember { mutableStateOf(false) }
    var showSecondWarning by remember { mutableStateOf(false) }

    if (showFirstWarning) {
        AlertDialog(
            onDismissRequest = { showFirstWarning = false },
            title = { Text("UYARI!", fontWeight = FontWeight.Black, color = Color.Red) },
            text = { Text("Emin misiniz? Bu seçenek aktifleştirilirse bazı uygulamalar sorunlu çalışabilir. Kesinlikle sorumluluk kabul etmiyoruz.", fontWeight = FontWeight.Bold) },
            confirmButton = {
                Button(
                    onClick = {
                        showFirstWarning = false
                        showSecondWarning = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text("DEVAM ET", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showFirstWarning = false }) {
                    Text("VAZGEÇ")
                }
            }
        )
    }

    if (showSecondWarning) {
        AlertDialog(
            onDismissRequest = { showSecondWarning = false },
            title = { Text("SON UYARI!", fontWeight = FontWeight.Black, color = Color.Red) },
            text = { Text("Telefonun stabilitesi bozulabilir. Devam etmek istiyor musunuz?", fontWeight = FontWeight.Bold) },
            confirmButton = {
                Button(
                    onClick = {
                        showSecondWarning = false
                        experimentalFeatureEnabled = true
                        prefs.edit { putBoolean("experimental_feature", true) }
                        Toast.makeText(context, "Deneysel mod açıldı.", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text("EVET, EMİNİM", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSecondWarning = false }) {
                    Text("HAYIR")
                }
            }
        )
    }

    if (showDeepCleanDialog) {
        AlertDialog(
            onDismissRequest = { showDeepCleanDialog = false },
            title = { Text("DERİN TEMİZLİK", fontWeight = FontWeight.Black, color = Color.Red) },
            text = { Text("Tüm arka plan süreçleri kapatılacak. Bu işlem açık olan diğer uygulamaların kapanmasına neden olur.", fontWeight = FontWeight.Bold) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeepCleanDialog = false
                        deepClean(context)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text("TEMİZLE", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeepCleanDialog = false }) {
                    Text("İPTAL")
                }
            }
        )
    }

    if (showDeleteAppDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAppDialog = false },
            title = { Text("UYGULAMAYI SİL?", fontWeight = FontWeight.Black, color = Color.Red) },
            text = { Text("BMS sistem ayarları üzerinden standart şekilde kaldırılacak.", fontWeight = FontWeight.Bold) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteAppDialog = false
                        try {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = "package:${context.packageName}".toUri()
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            Toast.makeText(context, "Ayarlar açılamadı.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text("AYARLARA GİT", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAppDialog = false }) {
                    Text("VAZGEÇ")
                }
            }
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            InfoCard("🔐 SİSTEM YETKİLERİ", Color(0xFFFF9800)) {
                Column {
                    Text(
                        "1.40: Cihaz yöneticisi ve tüm dosya erişimi isteği kaldırıldı. BMS bu yetkileri istemez.",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
            }
        }

        item {
            InfoCard("🔥 DENEYSEL ÖZELLİKLER", Color.Red) {
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Deneysel Mod", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("Sistem optimizasyonu için agresif yöntemler kullanır.", fontSize = 11.sp, color = Color.Gray)
                        }
                        Switch(
                            checked = experimentalFeatureEnabled,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    showFirstWarning = true
                                } else {
                                    experimentalFeatureEnabled = false
                                    prefs.edit { putBoolean("experimental_feature", false) }
                                }
                            }
                        )
                    }
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color.LightGray.copy(alpha = 0.3f))

                    Text("Derin Temizlik", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Tüm arka plan süreçlerini zorla durdurur.", fontSize = 11.sp, color = Color.Gray)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { showDeepCleanDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("DERİN TEMİZLİK", fontWeight = FontWeight.Black)
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color.LightGray.copy(alpha = 0.3f))
                    
                    Text("Uygulama Yönetimi", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Uygulamayı cihazdan tamamen kaldırır.", fontSize = 11.sp, color = Color.Gray)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { showDeleteAppDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Black),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("UYGULAMAYI SİL", fontWeight = FontWeight.Black, color = Color.White)
                    }
                }
            }
        }
        
        item {
            InfoCard("📌 NOT", Color.Gray) {
                Text(
                    "1.40: Uygulama standart sistem ayarları üzerinden kaldırılır. Anti-silme davranışı yoktur.",
                    fontSize = 12.sp,
                    color = Color.DarkGray
                )
            }
        }
    }
}

@Composable
fun BatteryGraph(history: List<HistoryPoint>) {
    Box(modifier = Modifier
        .fillMaxWidth()
        .height(150.dp)
        .padding(top = 8.dp)
        .background(Color.Black.copy(alpha = 0.03f), RoundedCornerShape(8.dp))
        .border(1.dp, Color.LightGray.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            val width = size.width
            val height = size.height

            drawLine(
                color = Color.Gray.copy(alpha = 0.3f),
                start = Offset(0f, height / 2),
                end = Offset(width, height / 2),
                strokeWidth = 1.dp.toPx()
            )

            if (history.size < 2) return@Canvas

            val maxVal = history.maxOf { it.current }.coerceAtLeast(100f)
            val minVal = history.minOf { it.current }.coerceAtMost(-100f)
            val range = (maxVal - minVal).coerceAtLeast(1f)
            
            val stepX = width / 59f

            val path = Path()
            history.forEachIndexed { i, point ->
                val x = i * stepX
                val y = height - ((point.current - minVal) / range * height)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                
                if (point.event != BatteryEventType.NONE) {
                    val eventColor = if (point.event == BatteryEventType.START_CHARGING) Color(0xFF4CAF50) else Color(0xFFF44336)
                    drawCircle(
                        color = eventColor,
                        radius = 4.dp.toPx(),
                        center = Offset(x, y)
                    )
                }
            }

            drawPath(
                path = path,
                color = Color(0xFF2196F3),
                style = Stroke(width = 2.dp.toPx())
            )
        }
        
        if (history.isEmpty()) {
            Text(
                "Veri bekleniyor...",
                modifier = Modifier.align(Alignment.Center),
                fontSize = 12.sp,
                color = Color.Gray
            )
        }
    }
}

@Composable
fun InfoCard(title: String, accentColor: Color, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(2.dp)) {
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
        Text(value, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

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
    return when {
        bytesPerSec >= 1024 * 1024 -> String.format(Locale.ROOT, "%.2f MB/s", bytesPerSec / (1024 * 1024))
        bytesPerSec >= 1024 -> String.format(Locale.ROOT, "%.1f KB/s", bytesPerSec / 1024)
        else -> String.format(Locale.ROOT, "%.0f B/s", bytesPerSec)
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
        Toast.makeText(context, "Ayarlar açıalamadı.", Toast.LENGTH_SHORT).show()
    }
}

fun optimizeRam(context: Context) {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    getRunningApps(context).forEach { am.killBackgroundProcesses(it) }
    Toast.makeText(context, "Önbellek temizlendi. Tam kapatma için listeden seçin.", Toast.LENGTH_LONG).show()
}

fun deepClean(context: Context) {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val time = System.currentTimeMillis()
    
    val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 60 * 60 * 24, time)
    
    if (stats.isNullOrEmpty()) {
        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        Toast.makeText(context, "Lütfen Kullanım Erişimi iznini verin.", Toast.LENGTH_LONG).show()
        return
    }

    val packages = stats.mapNotNull { it.packageName }.distinct().filter { 
        !it.contains("android") && !it.contains("google") && it != context.packageName
    }

    packages.forEach { pkg ->
        am.killBackgroundProcesses(pkg)
    }

    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_HOME)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    context.startActivity(homeIntent)

    Toast.makeText(context, "Arka plan işlemleri temizlendi!", Toast.LENGTH_LONG).show()
}

@SuppressLint("QueryPermissionsNeeded")
fun crazyFeature(context: Context, scope: CoroutineScope) {
    // 1.40: İzinsiz uygulama başlatma ve toplu işlem kaldırıldı.
    Toast.makeText(context, "Deneysel mod 1.40 içinde etkisizdir.", Toast.LENGTH_SHORT).show()
}
