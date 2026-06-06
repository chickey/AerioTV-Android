package com.aeriotv.android.feature.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aeriotv.android.R

/**
 * Cold-start welcome surface. Mirrors iOS App Store screenshot IMG_1076: AerioTV
 * brand block + supported source types + Sync / Detect-Home-WiFi info cards +
 * "Connect a Server" CTA + "Skip for now" link.
 *
 * Layout adapts to viewport: a short-but-wide screen (Android TV at 960dp x
 * 540dp, tablets in landscape, foldables unfolded) switches to a two-column
 * presentation so the CTA stays above the fold and the user can reach it with
 * a D-pad without scrolling. Portrait phones get the original vertical stack.
 */
@Composable
fun WelcomeScreen(
    onConnectServer: () -> Unit,
    onSkip: () -> Unit,
    /**
     * Optional Sign-in-with-Google handler. When provided, a pill appears
     * between Connect-a-Server and Skip on the welcome screen so the user
     * can authenticate Drive Sync up front instead of being routed back to
     * Settings later. Setting this null hides the row entirely (e.g. on
     * builds that ship without a Google Cloud OAuth client ID baked in).
     */
    onSignInWithGoogle: (() -> Unit)? = null,
    googleSignInInProgress: Boolean = false,
    /**
     * Optional Dispatcharr pairing handler. When provided (Fire TV builds), a
     * "Pair with Dispatcharr" button appears under Connect-a-Server so the user
     * can pair from the very first screen instead of going through Connect ->
     * Choose Source Type. Null hides it (e.g. Play builds).
     */
    onPairDispatcharr: (() -> Unit)? = null,
) {
    val config = LocalConfiguration.current
    // Two-column when the viewport is meaningfully wider than tall (TV, tablet
    // landscape, foldable unfolded). A 1080p TV is ~960x540dp, where a single
    // vertical column doesn't fit and scrolling pushes the logo off-screen; the
    // two-column layout keeps the brand block visible (left) while the CTAs sit
    // in the right column, so everything fits without losing the header.
    val twoColumn = config.screenWidthDp >= 720 && config.screenHeightDp < 720

    if (twoColumn) WelcomeTwoColumn(onConnectServer, onSkip, onSignInWithGoogle, googleSignInInProgress, onPairDispatcharr)
    else WelcomeSingleColumn(onConnectServer, onSkip, onSignInWithGoogle, googleSignInInProgress, onPairDispatcharr)
}

@Composable
private fun WelcomeSingleColumn(
    onConnectServer: () -> Unit,
    onSkip: () -> Unit,
    onSignInWithGoogle: (() -> Unit)?,
    googleSignInInProgress: Boolean,
    onPairDispatcharr: (() -> Unit)?,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        WelcomeAmbientOrbs(modifier = Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                // Constrain + center so the column doesn't stretch edge-to-edge
                // on wide screens (TV) -- mirrors the centered tvOS layout while
                // staying full-width on a narrow phone. 560dp matches the rest
                // of onboarding (Choose Source Type / Configure) so the brand
                // block, source rows and CTA share one column width; a no-op on
                // phones, which are narrower than the cap.
                .align(Alignment.TopCenter)
                .fillMaxHeight()
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(36.dp))
            BrandBlock()
            Spacer(Modifier.height(28.dp))
            SupportedTypesGroup(alignStart = false)
            Spacer(Modifier.height(20.dp))
            InfoCardsGroup()
            Spacer(Modifier.height(28.dp))
            ActionButtons(
                onConnectServer = onConnectServer,
                onSkip = onSkip,
                onSignInWithGoogle = onSignInWithGoogle,
                googleSignInInProgress = googleSignInInProgress,
                onPairDispatcharr = onPairDispatcharr,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun WelcomeTwoColumn(
    onConnectServer: () -> Unit,
    onSkip: () -> Unit,
    onSignInWithGoogle: (() -> Unit)?,
    googleSignInInProgress: Boolean,
    onPairDispatcharr: (() -> Unit)?,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        WelcomeAmbientOrbs(modifier = Modifier.fillMaxSize())
        WelcomeTwoColumnRow(onConnectServer, onSkip, onSignInWithGoogle, googleSignInInProgress, onPairDispatcharr)
    }
}

@Composable
private fun WelcomeTwoColumnRow(
    onConnectServer: () -> Unit,
    onSkip: () -> Unit,
    onSignInWithGoogle: (() -> Unit)?,
    googleSignInInProgress: Boolean,
    onPairDispatcharr: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        // Left column: brand + supported source types. Centered vertically.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            BrandBlock(alignStart = true)
            Spacer(Modifier.height(20.dp))
            SupportedTypesGroup(alignStart = true)
        }
        // Right column: info cards + CTAs. The Connect button sits in the
        // viewport center on TV so the focus ring lands on it at cold start,
        // which is what a remote-driven flow wants.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .widthIn(max = 540.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            InfoCardsGroup()
            Spacer(Modifier.height(20.dp))
            ActionButtons(
                onConnectServer = onConnectServer,
                onSkip = onSkip,
                onSignInWithGoogle = onSignInWithGoogle,
                googleSignInInProgress = googleSignInInProgress,
                onPairDispatcharr = onPairDispatcharr,
            )
        }
    }
}

@Composable
private fun BrandBlock(alignStart: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (alignStart) Alignment.Start else Alignment.CenterHorizontally,
    ) {
        BrandLogo()
        Spacer(Modifier.height(20.dp))
        Text(
            text = "AerioTV",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Your IPTV & Media Hub",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SupportedTypesGroup(alignStart: Boolean = false) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SupportedTypeRow(icon = Icons.Filled.Key, label = "Dispatcharr Direct Connect", alignStart = alignStart)
        SupportedTypeRow(icon = Icons.Filled.Tv, label = "Xtream Codes", alignStart = alignStart)
        SupportedTypeRow(icon = Icons.Filled.Description, label = "M3U + EPG", alignStart = alignStart)
    }
}

@Composable
private fun InfoCardsGroup() {
    // These are informational hints, not actions. They use a flat, borderless
    // style (icon + text) so they don't read as focusable buttons on TV — the
    // SourceTypeCard look is reserved for the tappable source-type list.
    Column(modifier = Modifier.fillMaxWidth()) {
        // Dispatcharr sync is offered on every build (any Android device can pair
        // with a Dispatcharr server). Google Drive sync is Play-only and is hidden
        // on builds without Google services (e.g. Fire TV).
        WelcomeInfoCard(
            icon = Icons.Outlined.Hub,
            title = "Sync via Dispatcharr",
            subtitle = "Pair with your Dispatcharr server to sync settings, favourites, and watch progress across devices — no Google account needed.",
        )
        if (com.aeriotv.android.BuildConfig.GOOGLE_SERVICES_AVAILABLE) {
            Spacer(Modifier.height(8.dp))
            WelcomeInfoCard(
                icon = Icons.Filled.CloudOff,
                title = "Sync via Google Account",
                subtitle = "After setup, sign in to Drive in Settings > Sync to mirror playlists, watch progress, reminders, and preferences across devices.",
            )
        }
        Spacer(Modifier.height(8.dp))
        WelcomeInfoCard(
            icon = Icons.Outlined.Wifi,
            title = "Detect Home WiFi",
            subtitle = "After setup, add a LAN URL to your playlist in Settings and AerioTV will switch to it automatically when you're on your home network.",
        )
    }
}

/**
 * Flat informational row for the welcome screen: a small accent icon and
 * title/subtitle text, with no border, fill, or icon-chip. Deliberately not
 * focusable so it reads as a hint rather than a button on a 10-foot TV UI.
 */
@Composable
private fun WelcomeInfoCard(icon: ImageVector, title: String, subtitle: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
            modifier = Modifier.padding(top = 2.dp).size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActionButtons(
    onConnectServer: () -> Unit,
    onSkip: () -> Unit,
    onSignInWithGoogle: (() -> Unit)? = null,
    googleSignInInProgress: Boolean = false,
    onPairDispatcharr: (() -> Unit)? = null,
) {
    val gradient = accentBrush()
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Connect + Pair share one style so focus is the only differentiator:
        // outlined when unfocused, gradient-filled + white ring + slight scale
        // when focused. The default (square) focus indication is suppressed so
        // the highlight follows the rounded shape.
        WelcomeActionButton(
            label = "Connect a Server",
            icon = Icons.Outlined.Hub,
            shape = shape,
            gradient = gradient,
            onClick = onConnectServer,
        )
        if (onPairDispatcharr != null) {
            Spacer(Modifier.height(12.dp))
            WelcomeActionButton(
                label = "Pair with Dispatcharr",
                icon = Icons.Outlined.Hub,
                shape = shape,
                gradient = gradient,
                onClick = onPairDispatcharr,
            )
        }
        if (onSignInWithGoogle != null) {
            Spacer(Modifier.height(12.dp))
            // Optional Drive Sign-in path. Lives between Connect (primary) and
            // Skip (secondary) so it reads as a "I already have devices set
            // up, pull my data" alternative to fresh-server onboarding. The
            // visual is the Google-compliant dark variant (#131314 BG +
            // four-color G mark) so it stays on-brand against AerioTV navy.
            WelcomeGoogleSignInButton(
                enabled = !googleSignInInProgress,
                onClick = onSignInWithGoogle,
            )
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSkip) {
            Text(
                text = "Skip for now",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * Shared welcome CTA: outlined when unfocused, gradient-filled with a white ring
 * and slight scale when focused. Identical styling for Connect and Pair so the
 * focus state is the only visual difference between them.
 */
@Composable
private fun WelcomeActionButton(
    label: String,
    icon: ImageVector,
    shape: androidx.compose.ui.graphics.Shape,
    gradient: Brush,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val contentColor = if (focused) Color.White else MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .scale(if (focused) 1.02f else 1f)
            .clip(shape)
            .then(
                if (focused) Modifier.background(brush = gradient, shape = shape)
                else Modifier.background(Color.Transparent, shape = shape),
            )
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) Color.White else MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                shape = shape,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = contentColor,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Onboarding-flavour of the Google Sign-In button. Mirrors the dark variant
 * in [com.aeriotv.android.feature.settings.SyncSettingsScreen.SignInWithGoogleButton]
 * — same #131314 background, #8E918F border, four-color G mark drawn from
 * res/drawable/ic_google_g.xml. Kept local so the welcome screen doesn't
 * depend on Settings internals.
 */
@Composable
private fun WelcomeGoogleSignInButton(enabled: Boolean, onClick: () -> Unit) {
    val bg = if (enabled) Color(0xFF131314) else Color(0xFF131314).copy(alpha = 0.55f)
    val stroke = Color(0xFF8E918F).copy(alpha = if (enabled) 1f else 0.55f)
    val fg = if (enabled) Color(0xFFE3E3E3) else Color(0xFFE3E3E3).copy(alpha = 0.55f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, stroke, RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = androidx.compose.ui.res.painterResource(
                id = com.aeriotv.android.R.drawable.ic_google_g,
            ),
            contentDescription = null,
            // Tint.Unspecified preserves the four-color brand mark — Google
            // brand guidelines forbid tinting the G to a single colour.
            tint = Color.Unspecified,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = "Sign in with Google",
            color = fg,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
        )
    }
    // Stroke goes after content so the rounded outline reads cleanly on top
    // of the BG fill regardless of which composable above happens to clip
    // its descendants.
}

@Composable
private fun BrandLogo() {
    // Mirrors iOS WelcomeView's `.shadow(color: aerioCyan @ 0.45, radius: 20, y: 8)`
    // under the logo. Compose's shadow modifier only renders elevation drop-shadows,
    // not colored glows, so we draw a soft radial gradient halo behind the logo
    // bitmap instead — same visual effect, API-agnostic.
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier.size(140.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(140.dp)) {
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.0f to accent.copy(alpha = 0.55f),
                        0.35f to accent.copy(alpha = 0.28f),
                        0.7f to accent.copy(alpha = 0.06f),
                        1.0f to Color.Transparent,
                    ),
                    center = Offset(size.width / 2f, size.height / 2f + 8.dp.toPx()),
                    radius = size.minDimension / 2f,
                ),
                center = Offset(size.width / 2f, size.height / 2f + 8.dp.toPx()),
                radius = size.minDimension / 2f,
            )
        }
        Image(
            painter = painterResource(id = R.drawable.aerio_logo),
            contentDescription = null,
            modifier = Modifier.size(96.dp),
        )
    }
}

/**
 * Two soft, blurred accent orbs in opposite corners — mirrors iOS WelcomeView
 * lines 22-35 where SwiftUI uses `Circle().blur(radius: 80)` on top of the
 * navy background. Compose's Modifier.blur is API 31+, so the same look here
 * comes from radial gradients with carefully tuned color stops.
 */
@Composable
private fun WelcomeAmbientOrbs(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    Canvas(modifier = modifier) {
        // Top-left orb (cyan)
        val tlCenter = Offset(-50.dp.toPx(), -30.dp.toPx())
        val tlRadius = 360.dp.toPx()
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.0f to primary.copy(alpha = 0.18f),
                    0.4f to primary.copy(alpha = 0.10f),
                    0.75f to primary.copy(alpha = 0.03f),
                    1.0f to Color.Transparent,
                ),
                center = tlCenter,
                radius = tlRadius,
            ),
            center = tlCenter,
            radius = tlRadius,
        )
        // Bottom-right orb (teal)
        val brCenter = Offset(size.width + 50.dp.toPx(), size.height - 80.dp.toPx())
        val brRadius = 320.dp.toPx()
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.0f to secondary.copy(alpha = 0.16f),
                    0.4f to secondary.copy(alpha = 0.08f),
                    0.75f to secondary.copy(alpha = 0.02f),
                    1.0f to Color.Transparent,
                ),
                center = brCenter,
                radius = brRadius,
            ),
            center = brCenter,
            radius = brRadius,
        )
    }
}

/** Linear cyan→teal gradient matching iOS LinearGradient.accentGradient
 * (top-leading to bottom-trailing). */
@Composable
private fun accentBrush(): Brush = Brush.linearGradient(
    colors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
    ),
)

/** Icon filled with a Brush instead of a solid tint — mirrors iOS FeaturePill's
 * `.foregroundStyle(LinearGradient.accentGradient)` on the SF Symbol. */
@Composable
private fun GradientIcon(
    imageVector: ImageVector,
    brush: Brush,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    drawRect(brush = brush, blendMode = BlendMode.SrcIn)
                },
        )
    }
}

@Composable
private fun SupportedTypeRow(icon: ImageVector, label: String, alignStart: Boolean = false) {
    val gradient = accentBrush()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(
            10.dp,
            if (alignStart) Alignment.Start else Alignment.CenterHorizontally,
        ),
    ) {
        GradientIcon(
            imageVector = icon,
            brush = gradient,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
