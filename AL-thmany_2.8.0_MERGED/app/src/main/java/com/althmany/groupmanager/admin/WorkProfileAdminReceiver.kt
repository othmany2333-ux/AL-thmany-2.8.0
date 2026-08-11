package com.althmany.groupmanager.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.althmany.groupmanager.util.RuntimeDiagnosticStore

/**
 * Optional DPC endpoint. Merely installing AL-thmany does not make it a profile owner.
 * This receiver becomes policy-capable only when Android provisioning explicitly assigns this app
 * as Device/Profile Owner. Existing work profiles owned by another DPC are never taken over.
 */
class WorkProfileAdminReceiver : DeviceAdminReceiver() {
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
        val admin = ComponentName(context, WorkProfileAdminReceiver::class.java)
        if (!dpm.isProfileOwnerApp(context.packageName)) return
        runCatching { dpm.setProfileName(admin, "AL-thmany Work") }
        runCatching { dpm.setProfileEnabled(admin) }
        RuntimeDiagnosticStore.append(context, "WORK_PROFILE_PROVISIONED", "AL-thmany is profile owner; profile enabled")
    }
}
