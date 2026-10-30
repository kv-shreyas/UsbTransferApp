plugins {
    kotlin("jvm")
    `java-library`
}

group = "com.example.usbdriverinstaller"
version = "1.0.0"

kotlin {
    jvmToolchain(11)
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName.set("usb-driver-installer")
    manifest {
        attributes(
            "Implementation-Title" to "USB Zadig Driver Installer for Windows",
            "Implementation-Version" to project.version,
            "Automatic-Module-Name" to "com.example.usbdriverinstaller"
        )
    }
}

// Standalone Fat-JAR task that bundles all dependencies and resources into a single distribution JAR
tasks.register<Jar>("standaloneJar") {
    archiveBaseName.set("usb-driver-installer-standalone")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
    manifest {
        attributes(
            "Implementation-Title" to "USB Zadig Driver Installer Standalone",
            "Implementation-Version" to project.version
        )
    }
}
