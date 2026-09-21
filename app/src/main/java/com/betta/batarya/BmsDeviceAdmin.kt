package com.betta.batarya

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

// 1.40: setUninstallBlocked ve caydırıcı mesaj kaldırıldı.
// Manifest kaydı da kaldırıldı; bu sınıf geçmiş uyumluluk için duruyor.
class BmsDeviceAdmin : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(context, "Cihaz Yöneticisi Aktif Edildi", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Toast.makeText(context, "Cihaz Yöneticisi Devre Dışı", Toast.LENGTH_SHORT).show()
    }
}
