package com.xcluice.manzil

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

/** Receives the result of the in-app update install session. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = confirmIntent(i)
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { c.startActivity(confirm) } catch (_: Exception) {}
            }
        } else if (st != PackageInstaller.STATUS_SUCCESS) {
            val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            Toast.makeText(c, "Update failed" + (if (msg == null) "" else ": $msg"), Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmIntent(i: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        else i.getParcelableExtra(Intent.EXTRA_INTENT)
}
