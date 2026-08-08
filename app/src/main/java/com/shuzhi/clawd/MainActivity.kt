package com.shuzhi.clawd

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 只有一件事：把权限要齐，然后把桌宠放出来。
 * 界面用代码写，省一个 layout 文件。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        statusView = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, 48)
        }
        root.addView(statusView)

        root.addView(button("授予悬浮窗权限") { requestOverlay() })
        root.addView(button("放它出来") { startPet() })
        root.addView(button("收起来") { stopPet() })

        setContentView(root)
        askNotificationIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        setOnClickListener { onClick() }
    }

private fun refreshStatus() {
        val overlay = if (canOverlay()) "已授权" else "未授权"
        statusView.text = "悬浮窗：$overlay\n\n" +
            "通信：http://127.0.0.1:8791"
    }
    private fun canOverlay(): Boolean = Settings.canDrawOverlays(this)


    private fun requestOverlay() {
        if (canOverlay()) {
            toast("已经有了")
            return
        }
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun askNotificationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun startPet() {
        if (!canOverlay()) {
            toast("先给悬浮窗权限")
            return
        }
        startForegroundService(Intent(this, OverlayService::class.java))
        toast("出来了")
    }

    private fun stopPet() {
        stopService(Intent(this, OverlayService::class.java))
        toast("收起来了")
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}