package com.fersaiyan.cyanbridge.ui.chat

import android.animation.ValueAnimator
import android.view.View
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Controls the ephemeral "Thinking..." indicator in the chat.
 * 
 * Features:
 * - Shows "Thinking" initially
 * - After 10 seconds, changes to "Thinking longer"
 * - Animated dots that bounce up and down sequentially
 */
class ThinkingIndicatorController(
    private val container: View,
    private val tvThinkingText: TextView,
    private val dot1: TextView,
    private val dot2: TextView,
    private val dot3: TextView,
) {
    private val isShowing = AtomicBoolean(false)
    private val startTimeMs = AtomicLong(0)
    
    private var dotAnimators: List<ValueAnimator> = emptyList()
    private var longerTextRunnable: Runnable? = null
    
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    
    /** Show the thinking indicator and start animations */
    fun show() {
        if (isShowing.get()) return
        isShowing.set(true)
        startTimeMs.set(System.currentTimeMillis())
        
        container.visibility = View.VISIBLE
        tvThinkingText.text = "Thinking"
        
        // Schedule "Thinking longer" after 10 seconds
        longerTextRunnable = Runnable {
            if (isShowing.get()) {
                tvThinkingText.text = "Thinking longer"
            }
        }
        handler.postDelayed(longerTextRunnable!!, 10_000L)
        
        startDotAnimation()
    }
    
    /** Hide the indicator and stop all animations */
    fun hide() {
        if (!isShowing.get()) return
        isShowing.set(false)
        
        container.visibility = View.GONE
        
        // Cancel scheduled "Thinking longer"
        longerTextRunnable?.let { handler.removeCallbacks(it) }
        longerTextRunnable = null
        
        stopDotAnimation()
    }
    
    private fun startDotAnimation() {
        // Create a bouncing animation for each dot with staggered delays
        // Each dot will translate up and down with a sine wave pattern
        val bounceHeight = -8f // pixels to move up (negative = up)
        val duration = 600L // ms for one full bounce cycle
        
        val dots = listOf(dot1, dot2, dot3)
        dotAnimators = dots.mapIndexed { index, dot ->
            val delay = index * 150L // stagger each dot by 150ms
            
            ValueAnimator.ofFloat(0f, 1f, 0f).apply {
                this.duration = duration
                repeatCount = ValueAnimator.INFINITE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                
                addUpdateListener { anim ->
                    val phase = anim.animatedValue as Float
                    // Use sine wave for smooth up-down motion
                    val translationY = (kotlin.math.sin(phase * Math.PI) * bounceHeight).toFloat()
                    dot.translationY = translationY
                }
                
                startDelay = delay
                start()
            }
        }
    }
    
    private fun stopDotAnimation() {
        dotAnimators.forEach { it.cancel() }
        dotAnimators = emptyList()
        
        // Reset dot positions
        dot1.translationY = 0f
        dot2.translationY = 0f
        dot3.translationY = 0f
    }
    
    /** Check if indicator is currently showing */
    fun isShowing(): Boolean = isShowing.get()
}
