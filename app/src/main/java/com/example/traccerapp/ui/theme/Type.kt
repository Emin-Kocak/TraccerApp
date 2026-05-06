package com.example.traccerapp.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Typography = Typography(
    displayLarge  = TextStyle(fontWeight = FontWeight.Bold,   fontSize = 36.sp, color = TextPrimary),
    displayMedium = TextStyle(fontWeight = FontWeight.Bold,   fontSize = 28.sp, color = TextPrimary),
    headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, color = TextPrimary),
    headlineMedium= TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = TextPrimary),
    titleLarge    = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = TextPrimary),
    titleMedium   = TextStyle(fontWeight = FontWeight.Medium,  fontSize = 16.sp, color = TextPrimary),
    bodyLarge     = TextStyle(fontWeight = FontWeight.Normal,  fontSize = 15.sp, color = TextPrimary),
    bodyMedium    = TextStyle(fontWeight = FontWeight.Normal,  fontSize = 13.sp, color = TextSecondary),
    labelLarge    = TextStyle(fontWeight = FontWeight.Medium,  fontSize = 12.sp, color = TextSecondary),
    labelSmall    = TextStyle(fontWeight = FontWeight.Normal,  fontSize = 10.sp, color = TextHint),
)