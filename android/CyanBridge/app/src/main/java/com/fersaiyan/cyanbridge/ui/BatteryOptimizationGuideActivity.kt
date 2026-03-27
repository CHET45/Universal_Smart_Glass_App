package com.fersaiyan.cyanbridge.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.R

class BatteryOptimizationGuideActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_battery_optimization_guide)

        findViewById<TextView>(R.id.battery_opt_status).text =
            if (isBatteryOptimizationIgnored(this)) {
                getString(R.string.battery_opt_status_off)
            } else {
                getString(R.string.battery_opt_status_on)
            }

        findViewById<Button>(R.id.btn_disable_optimization).setOnClickListener {
            openDisableBatteryOptimizationFlow()
        }
        findViewById<Button>(R.id.btn_open_app_info).setOnClickListener {
            openAppInfo()
        }
        findViewById<Button>(R.id.btn_open_optimization_list).setOnClickListener {
            openBatteryOptimizationList()
        }
        findViewById<Button>(R.id.btn_done).setOnClickListener {
            val ok = isBatteryOptimizationIgnored(this)
            if (ok) {
                markCompleted(this)
                navigateToNext()
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.battery_opt_still_on_toast),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        findViewById<Button>(R.id.btn_skip).setOnClickListener {
            suppressPermanently(this)
            navigateToNext()
        }
    }

    private fun navigateToNext() {
        // After battery optimization, continue to feature onboarding screens
        startActivity(Intent(this, OnboardingFeatureActivity::class.java))
        finish()
    }

    private fun openDisableBatteryOptimizationFlow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(this, getString(R.string.battery_opt_not_supported), Toast.LENGTH_SHORT).show()
            return
        }

        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (_: ActivityNotFoundException) {
            openBatteryOptimizationList()
        }
    }

    private fun openBatteryOptimizationList() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            openAppInfo()
        }
    }

    private fun openAppInfo() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (_: ActivityNotFoundException) {
            // Best-effort fallback.
            Toast.makeText(this, getString(R.string.battery_opt_cant_open_settings), Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val PREFS = "cyanbridge_prefs"
        private const val KEY_COMPLETED = "battery_opt_guide_completed"
        private const val KEY_SUPPRESS = "battery_opt_guide_suppress"

        fun launchIfNeeded(activity: AppCompatActivity) {
            if (!shouldShow(activity)) return
            activity.startActivity(Intent(activity, BatteryOptimizationGuideActivity::class.java))
        }

        private fun shouldShow(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_SUPPRESS, false)) return false
            if (prefs.getBoolean(KEY_COMPLETED, false)) return false
            return !isBatteryOptimizationIgnored(context)
        }

        private fun markCompleted(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_COMPLETED, true)
                .apply()
        }

        private fun suppressPermanently(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SUPPRESS, true)
                .apply()
        }

        private fun isBatteryOptimizationIgnored(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
            val pm = context.getSystemService(POWER_SERVICE) as PowerManager
            return pm.isIgnoringBatteryOptimizations(context.packageName)
        }
    }
}
