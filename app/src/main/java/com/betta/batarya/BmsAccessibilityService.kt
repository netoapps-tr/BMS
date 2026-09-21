package com.betta.batarya

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

// 1.40: Anti-silme davranışı kaldırıldı. Servis bilerek etkisizdir.
// Manifest kaydın da kaldırıldı; bu sınıf geçmiş uyumluluk için duruyor.
class BmsAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
    }

    override fun onInterrupt() {}
}
