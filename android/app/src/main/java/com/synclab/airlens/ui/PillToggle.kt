package com.synclab.airlens.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.Checkable
import android.widget.FrameLayout
import com.synclab.airlens.R

/**
 * iOS-style pill switch (52×32dp track, 24dp knob) built from plain views because the
 * project deliberately avoids AppCompat/Material. The framework Switch cannot match the
 * design's exact geometry without fighting its intrinsic thumb/track sizing.
 */
class PillToggle @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs), Checkable {

    var onCheckedChanged: ((Boolean) -> Unit)? = null

    private val track = View(context).apply {
        setBackgroundResource(R.drawable.bg_st_toggle_track)
    }
    private val knob = View(context).apply {
        setBackgroundResource(R.drawable.bg_st_toggle_knob)
        elevation = dp(2f)
    }
    private var checked = false

    init {
        addView(track, LayoutParams(dp(52f).toInt(), dp(32f).toInt()))
        addView(knob, LayoutParams(dp(24f).toInt(), dp(24f).toInt(), Gravity.CENTER_VERTICAL).apply {
            marginStart = dp(4f).toInt()
        })
        isClickable = true
        isFocusable = true
        setOnClickListener { toggle() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(dp(52f).toInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(dp(32f).toInt(), MeasureSpec.EXACTLY),
        )
    }

    override fun isChecked(): Boolean = checked

    override fun setChecked(value: Boolean) {
        if (checked == value) return
        checked = value
        applyVisual(animated = isLaidOut)
        onCheckedChanged?.invoke(checked)
    }

    /** Set state without firing the callback (used by render passes). */
    fun setCheckedSilently(value: Boolean) {
        if (checked == value) return
        checked = value
        applyVisual(animated = isLaidOut)
    }

    override fun toggle() {
        isChecked = !checked
    }

    private fun applyVisual(animated: Boolean) {
        track.isSelected = checked
        val target = if (checked) dp(20f) else 0f
        if (animated) {
            knob.animate().translationX(target).setDuration(200).start()
        } else {
            knob.translationX = target
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
