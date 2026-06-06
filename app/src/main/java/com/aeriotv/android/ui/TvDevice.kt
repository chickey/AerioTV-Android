package com.aeriotv.android.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * True when running on a TV device (Fire TV / Android TV), where input is a
 * D-pad remote and touch-only affordances (drag handles, swipe-to-dismiss,
 * drag-to-reorder) don't work. Use it to hide those affordances and provide
 * D-pad equivalents (auto-focus, explicit buttons).
 */
@Composable
fun rememberIsTvDevice(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION
    }
}
