# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# --- FIX for NonExistentClass kapt stub errors ---
-keepattributes *Annotation*, Signature
-keep class **$$annotations$ { *; }
-keep class kotlin.annotation.** { *; }
-keep class androidx.annotation.** { *; }
-keep class javax.annotation.** { *; }
-keep class javax.inject.** { *; }
-keep class dagger.** { *; }

# ✅ Jetpack Compose Core
########################################
# Keep all @Composable functions
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# Keep Compose runtime internals
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Keep Kotlin metadata annotations (Compose compiler relies on it)
-keepclassmembers class kotlin.Metadata { *; }

########################################
# ✅ Navigation Compose
########################################
-keep class androidx.navigation.** { *; }
-dontwarn androidx.navigation.**

########################################
# ✅ Animations / Transitions
########################################
-keep class androidx.compose.animation.** { *; }
-keep class androidx.compose.animation.core.** { *; }

########################################
# ✅ Material3
########################################
-keep class androidx.compose.material3.** { *; }

########################################
# ✅ Lifecycle / ViewModel / Hilt
########################################
-keep class androidx.lifecycle.** { *; }
-dontwarn androidx.lifecycle.**

# Keep all ViewModels (used by Hilt/Compose)
-keep class * extends androidx.lifecycle.ViewModel
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# Hilt / Dagger
-keep class dagger.hilt.** { *; }
-dontwarn dagger.hilt.**
-keep class javax.inject.** { *; }

# Generated Hilt classes
-keep class * implements dagger.hilt.internal.GeneratedComponent
-keep class * extends dagger.hilt.internal.GeneratedComponentManager

########################################
# ✅ Enums (safe for persistence)
########################################
-keepclassmembers enum * { *; }

########################################
# ✅ JNI (Native)
########################################
-keepclasseswithmembernames class * {
    native <methods>;
}

########################################
# ✅ Usb Communication & SDK Data models
########################################
-keep class com.example.securequicktransferapp.domain.model.** { *; }
-keep class com.example.secureqt.sdk.protocol.** { *; }

# Gson / Serialization Safety Rules (Standard)
-keepattributes Signature
-keepattributes *Annotation*
-keepclassmembers enum * { *; }

########################################
# ✅ Logging (Optional: strip debug logs)
########################################
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    # keep errors and warnings in release mode optionally
    # public static *** e(...); 
}
