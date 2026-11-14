package com.aplusconnect.companion.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.aplusconnect.companion.qr.QrScannerView
import com.aplusconnect.companion.ui.components.TonalButton
import com.aplusconnect.companion.ui.theme.AplusTheme
import com.aplusconnect.companion.ui.theme.AplusType
import com.aplusconnect.companion.ui.theme.Radius
import com.aplusconnect.companion.ui.theme.Size
import com.aplusconnect.companion.ui.theme.Space

/**
 * The QR scanner (DESIGN.md §10.6): full-bleed camera, dimmed surround with a
 * rounded 260 dp cut-out, corner brackets that breathe (static under reduced
 * motion), a top bar with Close, and a bottom hint with the typed fallback.
 *
 * The bars use the overlay tint without blur: Haze cannot sample a camera
 * surface, and the camera is the only thing behind them.
 */
@Composable
internal fun ScannerOverlay(
    onScanned: (String) -> Unit,
    onClose: () -> Unit,
    onEnterCode: () -> Unit,
) {
    val c = AplusTheme.colors
    val haptics = LocalHapticFeedback.current
    val animate = !AplusTheme.motion.reduced
    val bracketScale = if (animate) {
        val t = rememberInfiniteTransition(label = "brackets")
        val s by t.animateFloat(1f, 1.035f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "bracketScale")
        s
    } else 1f
    // The scanner always draws on a camera image, so it is dark in both themes.
    val dim = Color.Black.copy(alpha = 0.55f)
    val bracket = Color.White

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        QrScannerView(
            onScanned = { text ->
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onScanned(text)
            },
            modifier = Modifier.fillMaxSize(),
        )

        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        ) {
            val side = 260.dp.toPx()
            val topLeft = Offset((size.width - side) / 2, (size.height - side) / 2.2f)
            val r = CornerRadius(Radius.xl.toPx())
            drawRect(dim)
            drawRoundRect(Color.Transparent, topLeft, GeoSize(side, side), r, blendMode = BlendMode.Clear)

            val grow = (bracketScale - 1f) * side / 2
            val l = topLeft.x - grow
            val t = topLeft.y - grow
            val s = side + grow * 2
            val arm = 36.dp.toPx()
            val w = 4.dp.toPx()
            fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                drawLine(bracket, Offset(x, y), Offset(x + dx * arm, y), w, StrokeCap.Round)
                drawLine(bracket, Offset(x, y), Offset(x, y + dy * arm), w, StrokeCap.Round)
            }
            corner(l, t, 1f, 1f)
            corner(l + s, t, -1f, 1f)
            corner(l, t + s, 1f, -1f)
            corner(l + s, t + s, -1f, -1f)
        }

        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(Space.s3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(Size.target).clip(RoundedCornerShape(Radius.md)).background(c.glassOverlay),
            ) { Icon(Icons.Outlined.Close, contentDescription = "Close the scanner", tint = c.textPrimary) }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(Space.s4)
                .clip(RoundedCornerShape(Radius.xl))
                .background(c.glassOverlay)
                .padding(Space.s5),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            Text("Point at the QR code on your computer", style = AplusType.title, color = c.textPrimary)
            Text(
                "In A+ Connect on the computer: Connect → Pair.",
                style = AplusType.bodySm,
                color = c.textSecondary,
            )
            TonalButton("Enter code instead", onEnterCode, fullWidth = true)
        }
    }
}
