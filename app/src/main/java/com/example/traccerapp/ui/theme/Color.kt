package com.example.traccerapp.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.example.traccerapp.utils.AppCategory

val LocalIsDarkTheme = staticCompositionLocalOf { true }

// ─── Ortak arka planlar (koyu/açık) ───
private val DarkBgDark        = Color(0xFF0D0D14)
private val DarkBgLight       = Color(0xFFF7F7FB)
private val DarkSurfaceDark   = Color(0xFF16161F)
private val DarkSurfaceLight  = Color(0xFFFFFFFF)
private val DarkElevatedDark  = Color(0xFF1E1E2C)
private val DarkElevatedLight = Color(0xFFE8E8F0)
private val DarkBorderDark    = Color(0xFF2A2A3D)
private val DarkBorderLight   = Color(0xFFD8D8E3)

val DarkBg:       Color @Composable get() = if (LocalIsDarkTheme.current) DarkBgDark else DarkBgLight
val DarkSurface:  Color @Composable get() = if (LocalIsDarkTheme.current) DarkSurfaceDark else DarkSurfaceLight
val DarkElevated: Color @Composable get() = if (LocalIsDarkTheme.current) DarkElevatedDark else DarkElevatedLight
val DarkBorder:   Color @Composable get() = if (LocalIsDarkTheme.current) DarkBorderDark else DarkBorderLight

// ─── Koyu Mor vurgu ailesi (her iki temada da sabit) ───
val PurplePrimary  = Color(0xFF7C3AED)  // Ana vurgu
val PurpleLight    = Color(0xFF9D5FF5)  // Hover / açık ton

private val PurpleDimDark  = Color(0xFF3D1E7A)
private val PurpleDimLight = Color(0xFFE4D9FA)
val PurpleDim: Color @Composable get() = if (LocalIsDarkTheme.current) PurpleDimDark else PurpleDimLight

// ─── Metin (koyu/açık) ───
private val TextPrimaryDark    = Color(0xFFF1F1F8)
private val TextPrimaryLight   = Color(0xFF16161F)
private val TextSecondaryDark  = Color(0xFF9898B0)
private val TextSecondaryLight = Color(0xFF5B5B70)
private val TextHintDark       = Color(0xFF55556A)
private val TextHintLight      = Color(0xFF9494A8)

val TextPrimary:   Color @Composable get() = if (LocalIsDarkTheme.current) TextPrimaryDark else TextPrimaryLight
val TextSecondary: Color @Composable get() = if (LocalIsDarkTheme.current) TextSecondaryDark else TextSecondaryLight
val TextHint:      Color @Composable get() = if (LocalIsDarkTheme.current) TextHintDark else TextHintLight

// ─── Durum renkleri (her iki temada da sabit) ───
val StatusGreen    = Color(0xFF34D399)
val StatusRed      = Color(0xFFF87171)
val StatusAmber    = Color(0xFFFBBF24)
val StatusBlue     = Color(0xFF60A5FA)

// ─── Grafik / App renkleri ───
val AppColors = listOf(
    Color(0xFF7C3AED),
    Color(0xFF2563EB),
    Color(0xFF059669),
    Color(0xFFD97706),
    Color(0xFFDC2626),
    Color(0xFF7C3AED),
    Color(0xFF0891B2),
    Color(0xFF65A30D),
    Color(0xFFDB2777)
)

// ─── Kategori renkleri (Raporlar haftalık grafiği) ───
val CategorySocial        = Color(0xFFEC4899) // Sosyal Medya — pembe
val CategoryGame          = Color(0xFFF97316) // Oyunlar — turuncu
val CategoryVideo         = Color(0xFFEF4444) // Video/Medya — kırmızı
val CategoryCommunication = Color(0xFF22C55E) // İletişim — yeşil
val CategoryProductivity  = Color(0xFF3B82F6) // Üretkenlik — mavi
val CategoryOther         = Color(0xFF6B7280) // Diğer — gri

/** Kategori → renk eşlemesi. Raporlar haftalık grafiği ve Dashboard kategori çemberi ortak kullanır. */
fun categoryColor(category: AppCategory): Color = when (category) {
    AppCategory.SOCIAL -> CategorySocial
    AppCategory.GAME -> CategoryGame
    AppCategory.VIDEO -> CategoryVideo
    AppCategory.COMMUNICATION -> CategoryCommunication
    AppCategory.PRODUCTIVITY -> CategoryProductivity
    AppCategory.OTHER -> CategoryOther
}
