package com.leslie.tot.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The screens of the app. */
object TotScreen {
    const val CAMERA = "camera"
    const val HAND_READING = "handreading"
}

@Composable
fun TotSidebar(current: String, onSelect: (String) -> Unit) {
    Column(
        modifier = Modifier
            .width(220.dp)
            .fillMaxHeight()
            .background(Color(0xFF020617))
            .padding(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TotSidebarItem(TotScreen.CAMERA, "Camera & Depth", Icons.Default.Videocam, current, onSelect)
        TotSidebarItem(TotScreen.HAND_READING, "Hand Reading", Icons.Default.PanTool, current, onSelect)
    }
}

@Composable
private fun TotSidebarItem(
    route: String,
    title: String,
    icon: ImageVector,
    current: String,
    onSelect: (String) -> Unit
) {
    val isActive = route == current
    val bgBrush = if (isActive) {
        Brush.horizontalGradient(listOf(Color(0xFF2563EB), Color(0xFF1D4ED8)))
    } else {
        Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
    }
    val textColor = if (isActive) Color.White else Color(0xFF94A3B8)

    Row(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .shadow(
                elevation = if (isActive) 10.dp else 0.dp,
                shape = RoundedCornerShape(14.dp),
                ambientColor = Color(0xFF2563EB),
                spotColor = Color(0xFF2563EB)
            )
            .clip(RoundedCornerShape(14.dp))
            .background(bgBrush)
            .clickable { if (!isActive) onSelect(route) }
            .padding(vertical = 14.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = title, tint = textColor, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = title,
            color = textColor,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
