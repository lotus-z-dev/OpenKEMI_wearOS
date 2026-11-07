package com.supplices.kemimotowear.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Text
import com.supplices.kemimotowear.R

/**
 * Radar Pulse Animation Composable tailored for circular Wear OS displays.
 * Displays concentric pulsing radar waves during BLE peripheral pairing discovery.
 */
@Composable
fun RadarScanner(
    modifier: Modifier = Modifier,
    onCancelScan: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "RadarTransition")

    // Pulsing circle 1
    val wave1Progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Wave1"
    )

    // Pulsing circle 2 (staggered)
    val wave2Progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, delayMillis = 730, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Wave2"
    )

    // Pulsing circle 3 (staggered)
    val wave3Progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, delayMillis = 1460, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Wave3"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(100.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val maxRadius = size.width / 2f

                // Draw static radar crosshairs
                drawCircle(
                    color = Color(0xFF00E5FF).copy(alpha = 0.15f),
                    radius = maxRadius,
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )
                drawCircle(
                    color = Color(0xFF00E5FF).copy(alpha = 0.25f),
                    radius = maxRadius * 0.6f,
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )

                // Animated wave 1
                val r1 = maxRadius * wave1Progress
                val alpha1 = (1f - wave1Progress).coerceIn(0f, 1f)
                drawCircle(
                    color = Color(0xFF00E5FF).copy(alpha = alpha1 * 0.7f),
                    radius = r1,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )

                // Animated wave 2
                val r2 = maxRadius * wave2Progress
                val alpha2 = (1f - wave2Progress).coerceIn(0f, 1f)
                drawCircle(
                    color = Color(0xFF00E5FF).copy(alpha = alpha2 * 0.7f),
                    radius = r2,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )

                // Animated wave 3
                val r3 = maxRadius * wave3Progress
                val alpha3 = (1f - wave3Progress).coerceIn(0f, 1f)
                drawCircle(
                    color = Color(0xFF00E5FF).copy(alpha = alpha3 * 0.7f),
                    radius = r3,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )

                // Center glowing beacon
                drawCircle(
                    color = Color(0xFF00E5FF),
                    radius = 5.dp.toPx(),
                    center = center
                )
            }

            Text(
                text = "📡",
                fontSize = 18.sp
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = stringResource(R.string.radar_scanning_text),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF00E5FF),
            textAlign = TextAlign.Center
        )

        Text(
            text = stringResource(R.string.radar_proximity_hint),
            fontSize = 9.sp,
            color = Color.LightGray,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(4.dp))

        Button(
            onClick = onCancelScan,
            modifier = Modifier
                .height(26.dp)
                .width(90.dp),
            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF37474F)),
            shape = CircleShape
        ) {
            Text(
                text = stringResource(R.string.stop_action),
                fontSize = 10.sp,
                color = Color.White
            )
        }
    }
}
