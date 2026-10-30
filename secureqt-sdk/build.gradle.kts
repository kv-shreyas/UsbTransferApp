plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

group = "com.example.secureqt"
version = "1.0.0"

kotlin {
    jvmToolchain(11)

    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Public API exposes StateFlow and suspend functions
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        jvmMain.dependencies {
            // Public API exposes usb4java types (Device, DeviceHandle)
            api(libs.usb4java)
        }
        androidMain.dependencies {
            // Android platform USB APIs are part of compileSdk android.jar
        }
    }
}

android {
    namespace = "com.example.secureqt.sdk"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Self-contained JAR for release: SDK classes + usb4java (with natives), commons-lang3, coroutines, Kotlin stdlib.
// jvmJar stays thin so Gradle consumers such as :composeApp still get normal dependency version resolution.
tasks.register<Jar>("jvmStandaloneJar") {
    group = "build"
    description = "Assembles the JVM SDK JAR with all runtime dependencies bundled."
    archiveBaseName.set("secureqt-sdk-jvm")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    val thinJar = tasks.named<Jar>("jvmJar")
    dependsOn(thinJar)
    from({ zipTree(thinJar.get().archiveFile) })
    val runtimeClasspath = configurations.named("jvmRuntimeClasspath")
    dependsOn(runtimeClasspath)
    from({
        runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
    exclude(
        "module-info.class",
        "META-INF/versions/*/module-info.class",
        "META-INF/*.SF",
        "META-INF/*.DSA",
        "META-INF/*.RSA"
    )
    manifest {
        attributes(
            "Implementation-Title" to "SecureQT SDK Standalone",
            "Implementation-Version" to project.version
        )
    }
}
