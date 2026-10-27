import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.jaudiotagger)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.newpipe.extractor)
    // MPRIS: media keys and the desktop's player controls on Linux.
    implementation(libs.dbus.java.core)
    implementation(libs.dbus.java.unixsocket)
}

// Screenshots of the main screens without a window (see src/test/.../Shots.kt).
tasks.register<JavaExec>("shots") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "dev.xvin.palimpsest.desktop.ShotsKt"
    jvmArgs("-Dfile.encoding=UTF-8")
    args(
        providers.gradleProperty("out").getOrElse(layout.buildDirectory.dir("shots").get().asFile.path),
        providers.gradleProperty("width").getOrElse("1280"),
        providers.gradleProperty("height").getOrElse("820"),
    )
}

val appVersion = providers.provider {
    // One version for the whole app: taken from the Android build.
    Regex("""versionName = "([^"]+)"""").find(rootProject.file("app/build.gradle.kts").readText())!!.groupValues[1]
}

compose.desktop {
    application {
        mainClass = "dev.xvin.palimpsest.desktop.ui.AppKt"
        jvmArgs += listOf("-Xmx768m", "-Dfile.encoding=UTF-8")
        // A JVM crash report beside the app (the temp folder when that isn't writable): CrashGuard folds it into crash.log.
        jvmArgs += "-XX:ErrorFile=\$APPDIR/../palimpsest-crash-%p.log"
        nativeDistributions {
            // Linux packages build here; Windows (.msi/.exe) and macOS (.dmg) on their own CI runners.
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.AppImage, TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg)
            packageName = "Palimpsest"
            packageVersion = appVersion.get().substringBefore('-')
            description = "Music player: local files first, Yandex Music, YouTube Music and Bandcamp"
            vendor = "xvin84"
            copyright = "GPL-3.0"
            licenseFile.set(rootProject.file("LICENSE"))
            // NewPipe's JavaScript engine, TLS, logging… simpler and safer than hand-picking JDK modules.
            includeAllModules = true
            // Per-OS extras: a bundled ffmpeg goes in resources/<os>/ (see .github/workflows/desktop.yml).
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            linux {
                iconFile.set(project.file("icons/palimpsest.png"))
                debMaintainer = "xvin84"
                menuGroup = "AudioVideo;Audio;Player"
                appCategory = "sound"
                shortcut = true
            }
            windows {
                iconFile.set(project.file("icons/palimpsest.ico"))
                menuGroup = "Palimpsest"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "6b0f0c1e-3a4d-4d8f-9a39-2f1d6e7b5a11"
            }
            macOS {
                // macOS refuses versions starting with 0; the app itself shows the real one.
                packageVersion = "1." + appVersion.get().substringBefore('-').substringAfter('.')
                iconFile.set(project.file("icons/palimpsest.icns"))
                bundleID = "dev.xvin.palimpsest"
            }
        }
    }
}
