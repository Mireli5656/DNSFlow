package com.dnsflow.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.dnsflow.app.state.ConnectionState

@Composable
fun ConnectButton(
    state: ConnectionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val connected = state is ConnectionState.Connected
    val connecting = state is ConnectionState.Connecting

    val containerColor by animateColorAsState(
        targetValue = if (connected) colors.primary else colors.surface,
        label = "connectContainer"
    )
    val contentColor by animateColorAsState(
        targetValue = if (connected) colors.onPrimary else colors.primary,
        label = "connectContent"
    )
    val borderColor by animateColorAsState(
        targetValue = if (connected || connecting) colors.primary else colors.outline,
        label = "connectBorder"
    )

    val label = when {
        connected -> "Disconnect"
        connecting -> "Connecting… tap to cancel"
        else -> "Connect"
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(176.dp)
                .clip(CircleShape)
                .background(containerColor)
                .border(width = 2.dp, color = borderColor, shape = CircleShape)
                .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (connecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(72.dp),
                    color = colors.primary,
                    strokeWidth = 5.dp
                )
            } else {
                PowerIcon(color = contentColor, modifier = Modifier.size(72.dp))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = colors.onBackground
        )
    }
}

@Composable
private fun PowerIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val strokeWidth = size.minDimension * 0.09f
        val radius = size.minDimension / 2f - strokeWidth
        val center = Offset(size.width / 2f, size.height / 2f)

        drawArc(
            color = color,
            startAngle = -50f,
            sweepAngle = 280f,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
        drawLine(
            color = color,
            start = Offset(center.x, center.y - radius),
            end = center,
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
    }
}
