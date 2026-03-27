package com.fersaiyan.cyanbridge.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.agent.LocalAgentPrefs as AgentPrefs
import com.fersaiyan.cyanbridge.localagent.daily.DailyFactsReminderScheduler
import com.fersaiyan.cyanbridge.localagent.memory.LocalAgentMemoryStore
import com.fersaiyan.cyanbridge.memoryvault.MemoryModeManager
import com.fersaiyan.cyanbridge.media.autocapture.AutoAudioCapturePrefs
import com.fersaiyan.cyanbridge.media.autocapture.AutoAudioCaptureService
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

class OnboardingFeatureActivity : AppCompatActivity() {

    private var featureIndex = 0

    data class OnboardingFeature(
        val iconRes: Int,
        val titleRes: Int,
        val descriptionRes: Int,
        val detailsRes: Int,
        val togglePrefKey: String? = null,
        val toggleLabel: String? = null
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        featureIndex = intent.getIntExtra(EXTRA_FEATURE_INDEX, 0)
        setupFeatureScreen()
    }

    private fun setupFeatureScreen() {
        val feature = FEATURES.getOrNull(featureIndex) ?: run {
            finishOnboarding()
            return
        }

        findViewById<ImageView>(R.id.iv_icon).setImageResource(feature.iconRes)
        findViewById<TextView>(R.id.tv_title).text = getString(feature.titleRes)
        findViewById<TextView>(R.id.tv_description).text = getString(feature.descriptionRes)
        findViewById<TextView>(R.id.tv_feature_details).text = getString(feature.detailsRes)

        val switchEnable = findViewById<SwitchMaterial>(R.id.switch_enable)
        if (feature.togglePrefKey != null) {
            switchEnable.visibility = View.VISIBLE
            switchEnable.text = feature.toggleLabel
            switchEnable.isChecked = getFeatureDefaultState(featureIndex)
            switchEnable.setOnCheckedChangeListener { _, isChecked ->
                setFeatureState(featureIndex, isChecked)
            }
        } else {
            switchEnable.visibility = View.GONE
        }

        findViewById<MaterialButton>(R.id.btn_back).apply {
            text = if (featureIndex == 0) "Skip All" else "Back"
            setOnClickListener {
                if (featureIndex == 0) {
                    skipAllOnboarding()
                } else {
                    goToFeature(featureIndex - 1)
                }
            }
        }

        findViewById<MaterialButton>(R.id.btn_next).apply {
            text = if (featureIndex == FEATURES.lastIndex) "Get Started" else "Next"
            setOnClickListener {
                if (featureIndex == FEATURES.lastIndex) {
                    finishOnboarding()
                } else {
                    goToFeature(featureIndex + 1)
                }
            }
        }
    }

    private fun getFeatureDefaultState(index: Int): Boolean {
        return when (index) {
            0 -> AgentPrefs.isDailyFactsReminderEnabled(this)
            1 -> AgentPrefs.isAutoCaptureEnabled(this) && MemoryModeManager.isScreenOcrCaptureEnabled(this)
            2 -> AutoAudioCapturePrefs.isEnabled(this)
            else -> false
        }
    }

    private fun setFeatureState(index: Int, enabled: Boolean) {
        when (index) {
            0 -> {
                AgentPrefs.setDailyFactsReminderEnabled(this, enabled)
                DailyFactsReminderScheduler.scheduleIfEnabled(this, enabled)
            }
            1 -> {
                AgentPrefs.setAutoCaptureEnabled(this, enabled)
                MemoryModeManager.setScreenOcrCaptureEnabled(this, enabled)
            }
            2 -> {
                AutoAudioCapturePrefs.setEnabled(this, enabled)
                if (enabled) {
                    AutoAudioCaptureService.start(this)
                } else {
                    AutoAudioCaptureService.stop(this)
                }
            }
            3 -> {
                if (enabled) {
                    LocalAgentMemoryStore.ensureSeedFiles(this)
                }
            }
        }
    }

    private fun goToFeature(index: Int) {
        startActivity(Intent(this, OnboardingFeatureActivity::class.java).apply {
            putExtra(EXTRA_FEATURE_INDEX, index)
        })
        overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.slide_out_right)
        finish()
    }

    private fun finishOnboarding() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("onboarding_completed", true)
            .apply()

        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        finish()
    }

    private fun skipAllOnboarding() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("onboarding_completed", true)
            .apply()

        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        finish()
    }

    companion object {
        private const val EXTRA_FEATURE_INDEX = "feature_index"
        private const val PREFS = "cyanbridge_prefs"

        private val FEATURES = listOf(
            OnboardingFeature(
                iconRes = R.drawable.ic_device_heycyan,
                titleRes = R.string.onboarding_daily_facts_title,
                descriptionRes = R.string.onboarding_daily_facts_desc,
                detailsRes = R.string.onboarding_daily_facts_details,
                togglePrefKey = "daily_facts",
                toggleLabel = "Enable daily fact verification"
            ),
            OnboardingFeature(
                iconRes = R.drawable.ic_device_heycyan,
                titleRes = R.string.onboarding_screen_capture_title,
                descriptionRes = R.string.onboarding_screen_capture_desc,
                detailsRes = R.string.onboarding_screen_capture_details,
                togglePrefKey = "screen_capture",
                toggleLabel = "Enable screen capture"
            ),
            OnboardingFeature(
                iconRes = R.drawable.ic_device_generic_audio,
                titleRes = R.string.onboarding_audio_capture_title,
                descriptionRes = R.string.onboarding_audio_capture_desc,
                detailsRes = R.string.onboarding_audio_capture_details,
                togglePrefKey = "audio_capture",
                toggleLabel = "Enable continuous audio recording"
            ),
            OnboardingFeature(
                iconRes = R.drawable.ic_device_heycyan,
                titleRes = R.string.onboarding_agent_personality_title,
                descriptionRes = R.string.onboarding_agent_personality_desc,
                detailsRes = R.string.onboarding_agent_personality_details
            ),
            OnboardingFeature(
                iconRes = R.drawable.ic_device_heycyan,
                titleRes = R.string.onboarding_local_storage_title,
                descriptionRes = R.string.onboarding_local_storage_desc,
                detailsRes = R.string.onboarding_local_storage_details
            ),
            OnboardingFeature(
                iconRes = R.drawable.ic_device_heycyan,
                titleRes = R.string.onboarding_pro_sub_title,
                descriptionRes = R.string.onboarding_pro_sub_desc,
                detailsRes = R.string.onboarding_pro_sub_details
            )
        )

        fun launchIfNeeded(activity: AppCompatActivity) {
            val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean("onboarding_completed", false)) return

            activity.startActivity(Intent(activity, OnboardingFeatureActivity::class.java))
        }
    }
}
