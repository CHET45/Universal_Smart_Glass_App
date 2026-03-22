package com.fersaiyan.cyanbridge.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.R

class WelcomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (isOnboardingCompleted()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_welcome)

        findViewById<Button>(R.id.btn_start).setOnClickListener {
            startActivity(Intent(this, BatteryOptimizationGuideActivity::class.java))
            finish()
        }
    }

    private fun isOnboardingCompleted(): Boolean {
        val prefs = getSharedPreferences("cyanbridge_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("onboarding_completed", false)
    }
}
