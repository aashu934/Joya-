package com.example.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        root.addView(TextView(this).apply {
            text = "Jarvis Voice Assistant\n\nPehle 1, 2 aur 3 karo, phir Start dabao.\nBand karne ke liye \"bye\" bolo."
            textSize = 16f
            setPadding(0, 0, 0, 32)
        })

        fun addButton(label: String, onClick: () -> Unit) {
            root.addView(Button(this).apply {
                text = label
                setOnClickListener { onClick() }
            })
        }

        addButton("1. Permissions do") { askPermissions() }
        addButton("2. Overlay permission (apps kholne ke liye)") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        addButton("3. WhatsApp auto-send (Accessibility ON karo)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addButton("Start") { startAssistant() }
        addButton("Stop") { stopService(Intent(this, VoiceService::class.java)) }

        setContentView(root)
    }

    private fun askPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(perms.toTypedArray(), 1)
    }

    private fun startAssistant() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Pehle permissions do", Toast.LENGTH_SHORT).show()
            return
        }
        val i = Intent(this, VoiceService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        Toast.makeText(this, "Jarvis shuru ho gaya", Toast.LENGTH_SHORT).show()
    }
}
