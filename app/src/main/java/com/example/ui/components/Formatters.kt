package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.example.ui.theme.BladeRed
import com.example.ui.theme.BladeRedAccent
import com.example.ui.theme.BladeRedDark
import com.example.ui.theme.TextWhite

fun formatCompactCount(count: Int): String = when {
    count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000f).replace(".0M", "M")
    count >= 1_000 -> String.format("%.1fK", count / 1_000f).replace(".0K", "K")
    else -> count.toString()
}

fun filterOverlayColor(filterName: String): Color = when (filterName) {
    "Cyber" -> Color(0x28FF1E2D)
    "Noir" -> Color(0x45090A0F)
    "Warm" -> Color(0x28FF8A00)
    "Blade Prism" -> Color(0x28E50914)
    else -> Color.Transparent
}

@Composable
fun BladeAvatar(
    avatarUrl: String,
    username: String,
    size: Dp = 44.dp,
    borderColor: Color = TextWhite,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .border(1.5.dp, borderColor.copy(alpha = 0.85f), CircleShape)
            .background(
                Brush.linearGradient(listOf(BladeRedDark, BladeRed, BladeRedAccent))
            ),
        contentAlignment = Alignment.Center
    ) {
        if (avatarUrl.isNotBlank()) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = username,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            val initial = username.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "B"
            Text(
                text = initial,
                color = TextWhite,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.42f).sp
            )
        }
    }
}

@Composable
fun VerifiedBadge(modifier: Modifier = Modifier, size: Dp = 15.dp) {
    Icon(
        imageVector = Icons.Filled.CheckCircle,
        contentDescription = "Verified",
        tint = BladeRed,
        modifier = modifier.size(size)
    )
}

@Composable
fun BladeBrandMark(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(id = R.drawable.blade_launcher_icon),
            contentDescription = "Blade Logo",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .border(1.dp, BladeRed, CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "BLADE",
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp
            ),
            color = BladeRed
        )
    }
}
