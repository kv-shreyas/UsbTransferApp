package com.example.securequicktransferapp.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class AppColors(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val outlineVariant: Color,
    val error: Color
)

val LocalAppColors = staticCompositionLocalOf<AppColors> {
    error("No AppColors provided")
}

object AppTheme {
    val colors: AppColors
        @Composable
        get() = LocalAppColors.current
    val typography: Typography
        @Composable
        get() = MaterialTheme.typography
}

private val DarkAppColors = AppColors(
    primary = PrimaryColor,
    onPrimary = TextPrimaryLight, 
    primaryContainer = Color(0xFF0C4A6E), // Sky 900
    onPrimaryContainer = Color(0xFFBAE6FD), // Sky 200
    secondary = SecondaryColor,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF0C4A6E), // Sky 900
    onSecondaryContainer = Color(0xFFBAE6FD), // Sky 200
    tertiary = PrimaryLight,
    tertiaryContainer = TertiaryContainerDark,
    onTertiaryContainer = OnTertiaryContainerDark,
    background = BackgroundDark,
    onBackground = TextPrimaryDark,
    surface = SurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = SurfaceLightDark,
    onSurfaceVariant = TextSecondaryDark,
    outlineVariant = OutlineVariantDark,
    error = ErrorColor
)

private val LightAppColors = AppColors(
    primary = PrimaryDark,
    onPrimary = Color.White, 
    primaryContainer = Color(0xFFE0F2FE), // Sky 100
    onPrimaryContainer = Color(0xFF0C4A6E), // Sky 900
    secondary = SecondaryDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F2FE), // Sky 100
    onSecondaryContainer = Color(0xFF0C4A6E), // Sky 900
    tertiary = PrimaryColor,
    tertiaryContainer = TertiaryContainerLight,
    onTertiaryContainer = OnTertiaryContainerLight,
    background = BackgroundLight,
    onBackground = TextPrimaryLight,
    surface = SurfaceLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = Color(0xFFF1F5F9), // Slate 100
    onSurfaceVariant = TextSecondaryLight,
    outlineVariant = OutlineVariantLight,
    error = ErrorColor
)

val AppShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp)
)

val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    )
)

@Composable
fun SecureTransferTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val appColors = if (darkTheme) DarkAppColors else LightAppColors
    
    // Map to MaterialTheme for backward compatibility with standard components
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = appColors.primary,
            onPrimary = appColors.onPrimary,
            primaryContainer = appColors.primaryContainer,
            onPrimaryContainer = appColors.onPrimaryContainer,
            secondary = appColors.secondary,
            onSecondary = appColors.onSecondary,
            secondaryContainer = appColors.secondaryContainer,
            onSecondaryContainer = appColors.onSecondaryContainer,
            tertiary = appColors.tertiary,
            tertiaryContainer = appColors.tertiaryContainer,
            onTertiaryContainer = appColors.onTertiaryContainer,
            background = appColors.background,
            onBackground = appColors.onBackground,
            surface = appColors.surface,
            onSurface = appColors.onSurface,
            surfaceVariant = appColors.surfaceVariant,
            onSurfaceVariant = appColors.onSurfaceVariant,
            outlineVariant = appColors.outlineVariant,
            error = appColors.error
        )
    } else {
        lightColorScheme(
            primary = appColors.primary,
            onPrimary = appColors.onPrimary,
            primaryContainer = appColors.primaryContainer,
            onPrimaryContainer = appColors.onPrimaryContainer,
            secondary = appColors.secondary,
            onSecondary = appColors.onSecondary,
            secondaryContainer = appColors.secondaryContainer,
            onSecondaryContainer = appColors.onSecondaryContainer,
            tertiary = appColors.tertiary,
            tertiaryContainer = appColors.tertiaryContainer,
            onTertiaryContainer = appColors.onTertiaryContainer,
            background = appColors.background,
            onBackground = appColors.onBackground,
            surface = appColors.surface,
            onSurface = appColors.onSurface,
            surfaceVariant = appColors.surfaceVariant,
            onSurfaceVariant = appColors.onSurfaceVariant,
            outlineVariant = appColors.outlineVariant,
            error = appColors.error
        )
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalAppColors provides appColors
    ) {
        MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = {
            Surface(
                color = colorScheme.background,
                contentColor = colorScheme.onBackground
            ) {
                content()
            }
        }
    )
    }
}
