package com.jarvis.assistant.contacts

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import com.jarvis.assistant.R

/** Presents the system's contacts permission prompt, without retaining a pending dial request. */
class PermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false) {
            finish()
        } else if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.contacts_already_allowed, Toast.LENGTH_SHORT).show()
            finish()
        } else if (savedInstanceState == null) {
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            Toast.makeText(this, R.string.contacts_already_allowed, Toast.LENGTH_SHORT).show()
        } else if (!shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)) {
            Toast.makeText(this, R.string.contacts_open_settings, Toast.LENGTH_LONG).show()
            runCatching {
                startActivity(
                    Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                )
            }
        }
        finish()
    }

    companion object {
        private const val REQUEST = 41

        fun intent(context: Context): Intent = Intent(context, PermissionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
