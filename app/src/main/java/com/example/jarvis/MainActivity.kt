package com.example.jarvis
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
class MainActivity : Activity() {
    private lateinit var avatar: ImageView
    private lateinit var status: TextView
    private lateinit var micBtn: Button
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#2B1650"))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        avatar = ImageView(this).apply {
            setImageResource(R.drawable.dp)
            layoutParams = LinearLayout.LayoutParams((250 * d).toInt(), (250 * d).toInt())
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, view.width / 2f)
                }
            }
        }
        col.addView(avatar)
        status = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, (24 * d).toInt(), 0, (24 * d).toInt())
        }
        col.addView(status)
        micBtn = Button(this).apply {
            textSize = 30f
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.OVAL
            bg.setColor(Color.parseColor("#FF4F8B"))
            background = bg
            layoutParams = LinearLayout.LayoutParams((84 * d).toInt(), (84 * d).toInt())
            setOnClickListener { toggle() }
        }
        col.addView(micBtn)
        root.addView(
            col,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        val gear = TextView(this).apply {
            text = "⚙"
            textSize = 30f
            setTextColor(Color.WHITE)
            setPadding((16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt())
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        val gp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        gp.gravity = Gravity.TOP or Gravity.END
        root.addView(gear, gp)
        setContentView(root)
    }
    private fun toggle() {
        if (UiBus.running) {
            stopService(Intent(this, VoiceService::class.java))
            UiBus.update("Band hai. Mic dabao", false)
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestAllPermissions(this)
            Toast.makeText(this, "Permissions allow karke dobara mic dabao", Toast.LENGTH_LONG).show()
            return
        }
        val i = Intent(this, VoiceService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        UiBus.update("Shuru ho rahi hu...", true)
    }
    private fun refresh() {
        status.text = UiBus.status
        micBtn.text = if (UiBus.running) "⏹" else "🎤"
        val s = if (UiBus.speakingNow) 1.07f else 1.0f
        avatar.animate().scaleX(s).scaleY(s).setDuration(250).start()
    }
    override fun onResume() {
        super.onResume()
        UiBus.listener = { refresh() }
        refresh()
    }
    override fun onPause() {
        UiBus.listener = null
        super.onPause()
    }
}
