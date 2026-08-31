package com.lumi.pet

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var toggleBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        toggleBtn = findViewById(R.id.toggle_btn)

        toggleBtn.setOnClickListener {
            if (OverlayService.isRunning) {
                stopService(Intent(this, OverlayService::class.java))
                updateUI()
            } else {
                if (!Settings.canDrawOverlays(this)) {
                    requestOverlayPermission()
                    return@setOnClickListener
                }
                startOverlay()
            }
        }

        findViewById<Button>(R.id.btn_usage_perm).setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun startOverlay() {
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateUI()
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivityForResult(intent, 1001)
        Toast.makeText(this, "请授权悬浮窗权限", Toast.LENGTH_SHORT).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && Settings.canDrawOverlays(this)) {
            startOverlay()
        }
    }

    private fun hasUsageAccess(): Boolean {
        val aom = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = aom.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(), packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun updateUI() {
        val overlay = Settings.canDrawOverlays(this)
        val usage = hasUsageAccess()
        val running = OverlayService.isRunning

        val sb = StringBuilder()
        sb.appendLine("悬浮窗权限: ${if (overlay) "✓" else "✗"}")
        sb.appendLine("使用情况权限: ${if (usage) "✓" else "✗"}")
        sb.appendLine("桌宠状态: ${if (running) "运行中" else "已停止"}")
        statusText.text = sb.toString()

        toggleBtn.text = if (running) "关闭桌宠" else "启动桌宠"
    }
}