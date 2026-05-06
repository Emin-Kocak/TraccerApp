package com.example.traccerapp.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun RealAppIcon(packageName: String, appName: String, size: Dp = 36.dp, cornerRadius: Dp = 10.dp) {
    val context = LocalContext.current
    val bitmap = remember(packageName) {
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            android.graphics.Bitmap.createBitmap(108, 108, android.graphics.Bitmap.Config.ARGB_8888).also { bmp ->
                val canvas = android.graphics.Canvas(bmp)
                drawable.setBounds(0, 0, 108, 108)
                drawable.draw(canvas)
            }
        }.getOrNull()
    }
    Box(modifier = Modifier.size(size).clip(RoundedCornerShape(cornerRadius))) {
        if (bitmap != null) {
            Image(bitmap = bitmap.asImageBitmap(), contentDescription = appName, modifier = Modifier.fillMaxSize())
        } else {
            Box(
                modifier = Modifier.fillMaxSize().background(Color(0xFF3D1E7A)),
                contentAlignment = Alignment.Center
            ) {
                Text(appName.firstOrNull()?.uppercase() ?: "?", color = Color(0xFF9D5FF5), fontWeight = FontWeight.Bold)
            }
        }
    }
}
