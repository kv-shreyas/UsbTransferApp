import org.gradle.kotlin.dsl.implementation
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val appVersion = file("src/commonMain/kotlin/com/example/securequicktransferapp/domain/constants/Constants.kt")
    .useLines { lines ->
        lines.firstOrNull { it.contains("PACKAGE_VERSION =") }
            ?.substringAfter("PACKAGE_VERSION =")
            ?.trim()
            ?.removeSurrounding("\"")
    } ?: "1.0.0"

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    jvm()
    
    sourceSets {
        commonMain.dependencies {
            implementation(project(":secureqt-sdk"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(compose.materialIconsExtended)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            
            // ConstraintLayout Multiplatform
            implementation(libs.constraintlayout.compose)
        }
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.hilt.navigation.compose)
            implementation(libs.hilt.android)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)

            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

            // Koin DI
            implementation("io.insert-koin:koin-core:3.5.0")

            // Compose Desktop
            implementation("org.jetbrains.compose.desktop:desktop:1.10.3")

            // USB
            implementation(libs.usb4java)
        }
    }
}

android {
    namespace = "com.example.securequicktransferapp"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.example.securequicktransferapp"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = appVersion
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = null
        }
    }
    
    applicationVariants.all {
        val variant = this
        val appName = "SecureQuickTransferApp"
        val versionName = variant.versionName ?: appVersion
        val buildTypeName = variant.buildType.name

        variant.outputs.all {
            val output = this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl
            if (output != null) {
                output.outputFileName = "${appName}_v${versionName}_${buildTypeName}.apk"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(libs.compose.uiTooling)
    add("kspAndroid", libs.hilt.compiler)
    add("kspAndroid", libs.androidx.hilt.compiler)
}

compose.desktop {
    application {
        mainClass = "com.example.securequicktransferapp.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb, TargetFormat.AppImage)
            packageName = "SecureQuickTransferApp"
            packageVersion = appVersion
            includeAllModules = true
            buildTypes.release.proguard {
                isEnabled.set(false)
                configurationFiles.from(file("proguard-rules.pro"))
            }
            windows {
                console = true
            }
        }
    }
}