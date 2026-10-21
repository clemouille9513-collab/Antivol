package com.antivol.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val cBg = Color(0xFF0B0E14)
internal val cPanel = Color(0xFF141923)
internal val cPanel2 = Color(0xFF1B2230)
internal val cBlue = Color(0xFF3D7BFF)
internal val cRed = Color(0xFFFF3B4E)
internal val cGreen = Color(0xFF2ECC8F)
internal val cDim = Color(0xFF9AA7BD)

@Composable
internal fun AntivolTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = cBlue,
            onPrimary = Color.White,
            secondary = cRed,
            background = cBg,
            surface = cPanel,
            onSurface = Color.White,
            onBackground = Color.White,
            error = cRed
        ),
        content = content
    )
}

@Composable
internal fun Section(text: String) {
    Text(
        text,
        color = cDim,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp)
    )
}

@Composable
internal fun AppCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cPanel2)
    ) {
        Column(Modifier.padding(18.dp), content = content)
    }
}

@Composable
internal fun Header(title: String, subtitle: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(listOf(Color(0xFF7A0C1E), Color(0xFF0B0E14), Color(0xFF123A99)))
            )
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Shield, contentDescription = null, tint = Color.White, modifier = Modifier.size(44.dp))
            Spacer(Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(subtitle, color = Color(0xFFB8C4DA), fontSize = 14.sp)
            }
        }
    }
}
