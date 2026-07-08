package com.example.traccerapp.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// NOT: Renkler burada sabitlenmiyor; metin rengi MaterialTheme.colorScheme'den
// (onBackground/onSurface = TextPrimary) otomatik miras alınır, bu da tema
// (koyu/açık) her değiştiğinde doğru rengin uygulanmasını sağlar.
val Typography = Typography(
    displayLarge  = TextStyle(fontWeight = FontWeight.Bold,   fontSize = 36.sp),
    displayMedium = TextStyle(fontWeight = FontWeight.Bold,   fontSize = 28.sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp),
    headlineMedium= TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleLarge    = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    titleMedium   = TextStyle(fontWeight = FontWeight.Medium,  fontSize = 16.sp),
    bodyLarge     = TextStyle(fontWeight = FontWeight.Normal,  fontSize = 15.sp),
    bodyMedium    = TextStyle(fontWeight = FontWeight.Normal,  fontSize = 13.sp),
    labelLarge    = TextStyle(fontWeight = FontWeight.Medium,  fontSize = 12.sp),
    labelSmall    = TextStyle(fontWeight = FontWeight.Normal,  fontSize = 10.sp),
)