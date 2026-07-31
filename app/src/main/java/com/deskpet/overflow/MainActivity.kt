package com.deskpet.overflow

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.deskpet.overflow.service.OverlayService

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusText = findViewById<TextView>(R.id.status_text)
        val startBtn = findViewById<Button>(R.id.btn_start)
        val overlayBtn = findViewById<Button>(R.id.btn_overlay_perm)
        val usageBtn = findViewById<Button>(R.id.btn_usage_perm)

        // 检查悬浮窗权限
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true

        // 检查使用情况访问权限
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val hasUsage = try {
            val start = System.currentTimeMillis() - 60000
            val stats = usageStatsManager.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                start, System.currentTimeMillis()
            )
            !stats.isNullOrEmpty()
        } catch (e: Exception) { false }

        if (hasOverlay && hasUsage) {
            statusText.text = "✅ 权限齐全，桌宠已就绪"
            startService()
        } else {
            statusText.text = buildString {
                if (!hasOverlay) append("⚠ 需要悬浮窗权限\n")
                if (!hasUsage) append("⚠ 需要使用情况访问权限\n")
            }
        }

        startBtn.setOnClickListener {
            if (hasOverlay || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this))) {
                startService()
            } else {
                Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            }
        }

        overlayBtn.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
            }
        }

        usageBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    private fun startService() {
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        finish() // 关闭设置页
    }
}