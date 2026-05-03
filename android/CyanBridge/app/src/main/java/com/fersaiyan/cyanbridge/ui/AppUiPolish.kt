package com.fersaiyan.cyanbridge.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.widget.TextView
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.databinding.AcitivytMainBinding
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * Centralized UI cleanup for the user-facing build.
 *
 * This intentionally hides unfinished/debug destinations instead of deleting the
 * underlying screens. To restore them later, make the corresponding menu items
 * visible here and re-enable their handlers in each setupBottomNavigation().
 */
object AppUiPolish {
    private val accentColor = Color.parseColor("#4F46E5")
    private val accentPressedColor = Color.parseColor("#4338CA")
    private val mutedColor = Color.parseColor("#64748B")
    private val surfaceColor = Color.parseColor("#FFFFFF")
    private val softSurfaceColor = Color.parseColor("#EEF2FF")
    private val onAccentColor = Color.WHITE
    private val onSoftSurfaceColor = Color.parseColor("#1E293B")

    fun configureBottomNavigation(
        bottomNavigation: BottomNavigationView,
        selectedItemId: Int,
    ) {
        with(bottomNavigation.menu) {
            findItem(R.id.nav_glasses)?.isVisible = true
            findItem(R.id.nav_transcriptions_recordings)?.isVisible = true

            // Hidden from the user-facing bottom bar for now. Screens remain in code.
            findItem(R.id.nav_chats)?.isVisible = false
            findItem(R.id.nav_settings)?.isVisible = false
            findItem(R.id.nav_community_plugins)?.isVisible = false
        }

        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked),
        )
        val colors = intArrayOf(accentColor, mutedColor)
        val tint = ColorStateList(states, colors)
        bottomNavigation.itemIconTintList = tint
        bottomNavigation.itemTextColor = tint
        bottomNavigation.itemRippleColor = ColorStateList.valueOf(Color.parseColor("#E0E7FF"))
        bottomNavigation.setBackgroundColor(surfaceColor)

        val selectedItem = bottomNavigation.menu.findItem(selectedItemId)
        if (selectedItem?.isVisible == true && bottomNavigation.selectedItemId != selectedItemId) {
            bottomNavigation.selectedItemId = selectedItemId
        }
    }

    fun applyMainScreen(binding: AcitivytMainBinding) {
        // Debug/advanced controls are still present in the layout/binding but hidden.
        binding.btnToggleAdvanced.visibility = View.GONE
        binding.layoutAdvancedContainer.visibility = View.GONE
        binding.btnTestHijackVoice.visibility = View.GONE
        binding.btnTestHijackImage.visibility = View.GONE
        binding.btnPullOtaTest.visibility = View.GONE
        binding.btnOtaInfo.visibility = View.GONE

        binding.btnScan.text = "Find glasses"
        binding.btnConnect.text = "Connect"
        binding.btnDisconnect.text = "Disconnect"
        binding.btnCamera.text = "Take photo"
        binding.btnVideo.text = "Record video"
        binding.btnRecord.text = "Record audio"
        binding.btnDataDownload.text = "Sync media"

        listOf(
            binding.btnScan,
            binding.btnConnect,
            binding.btnDataDownload,
        ).forEach { stylePrimaryButton(it) }

        listOf(
            binding.btnCamera,
            binding.btnVideo,
            binding.btnRecord,
            binding.btnDisconnect,
        ).forEach { styleSecondaryButton(it) }
    }

    private fun stylePrimaryButton(view: View) {
        view.backgroundTintList = ColorStateList.valueOf(accentColor)
        view.elevation = 4f
        (view as? TextView)?.setTextColor(onAccentColor)
    }

    private fun styleSecondaryButton(view: View) {
        view.backgroundTintList = ColorStateList.valueOf(softSurfaceColor)
        view.elevation = 2f
        (view as? TextView)?.setTextColor(onSoftSurfaceColor)
    }

    @Suppress("unused")
    private fun stylePressedButton(view: View) {
        view.backgroundTintList = ColorStateList.valueOf(accentPressedColor)
        (view as? TextView)?.setTextColor(onAccentColor)
    }
}
