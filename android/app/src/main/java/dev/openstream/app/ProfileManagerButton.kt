package dev.openstream.app

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.widget.Button

/** Button tự chứa navigation để SettingsActivity không phải sở hữu thêm state UI. */
class ProfileManagerButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle,
) : Button(context, attrs, defStyleAttr) {
    init {
        setOnClickListener {
            context.startActivity(Intent(context, ProfileManagerActivity::class.java))
        }
    }
}
