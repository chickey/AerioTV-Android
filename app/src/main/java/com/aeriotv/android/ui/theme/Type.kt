package com.aeriotv.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.isSpecified

/**
 * Material 3 type scale. Phone / tablet / foldable use the stock Material
 * defaults. Android TV scales every token up so text is legible at 10 feet,
 * mirroring how the tvOS app keeps a SEPARATE, ~1.5x-larger Typography table
 * (iOS Design/Typography.swift: tvOS bodyMedium 24 vs iOS 15, labelSmall 18 vs
 * 11, displayLarge 52 vs 34). A stock Material port renders TV text at roughly
 * 60% of the tvOS reference, which is the "doesn't scale like the tvOS app /
 * too small" complaint. Because the whole app reads `MaterialTheme.typography`,
 * swapping in this scaled table on TV fixes the size app-wide in one place.
 *
 * The factor is a single tunable knob. 1.5x lands the smallest label near
 * 16-17sp and body near 21sp; the tvOS floor is ~18sp, so this is a deliberate,
 * slightly-conservative starting point that pairs with the per-surface sizing
 * work (guide / posters / rows) in the scaling + foldable tasks.
 */
private const val TV_TYPE_SCALE = 1.5f

private val PhoneTypography = Typography()

private fun TextStyle.scaledForTv(factor: Float): TextStyle = copy(
    fontSize = if (fontSize.isSpecified) fontSize * factor else fontSize,
    lineHeight = if (lineHeight.isSpecified) lineHeight * factor else lineHeight,
)

private val TvTypography = with(PhoneTypography) {
    Typography(
        displayLarge = displayLarge.scaledForTv(TV_TYPE_SCALE),
        displayMedium = displayMedium.scaledForTv(TV_TYPE_SCALE),
        displaySmall = displaySmall.scaledForTv(TV_TYPE_SCALE),
        headlineLarge = headlineLarge.scaledForTv(TV_TYPE_SCALE),
        headlineMedium = headlineMedium.scaledForTv(TV_TYPE_SCALE),
        headlineSmall = headlineSmall.scaledForTv(TV_TYPE_SCALE),
        titleLarge = titleLarge.scaledForTv(TV_TYPE_SCALE),
        titleMedium = titleMedium.scaledForTv(TV_TYPE_SCALE),
        titleSmall = titleSmall.scaledForTv(TV_TYPE_SCALE),
        bodyLarge = bodyLarge.scaledForTv(TV_TYPE_SCALE),
        bodyMedium = bodyMedium.scaledForTv(TV_TYPE_SCALE),
        bodySmall = bodySmall.scaledForTv(TV_TYPE_SCALE),
        labelLarge = labelLarge.scaledForTv(TV_TYPE_SCALE),
        labelMedium = labelMedium.scaledForTv(TV_TYPE_SCALE),
        labelSmall = labelSmall.scaledForTv(TV_TYPE_SCALE),
    )
}

/** Material type scale for the active form factor (TV gets the scaled table). */
fun aerioTypography(isTv: Boolean): Typography = if (isTv) TvTypography else PhoneTypography

/** Back-compat alias for existing references; equals the phone (default) scale. */
val Typography: Typography = PhoneTypography
