plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    // values/ is English, values-ru/ Russian; the generated locale config lists them for the
    // per-app language picker (Android Settings → Apps → Palimpsest → Language).
    androidResources { generateLocaleConfig = true }
    namespace = "dev.xvin.palimpsest"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.xvin.palimpsest"
        minSdk = 33
        targetSdk = 36
        versionCode = 16
        versionName = "0.4.9"
        // Where self-update looks for releases; forks can point it at their own repository.
        buildConfigField("String", "GITHUB_REPO", "\"xvin84/palimpsest\"")
        // GitHub API base; a debug build can be pointed at a local stand-in to test updating end to end.
        buildConfigField("String", "UPDATE_API", "\"${providers.gradleProperty("palimpsest.updateApi").orNull ?: "https://api.github.com"}\"")
        // Its web and raw-file counterparts: the fallbacks when the API's hourly limit runs out.
        buildConfigField("String", "UPDATE_WEB", "\"${providers.gradleProperty("palimpsest.updateWeb").orNull ?: "https://github.com"}\"")
        buildConfigField("String", "UPDATE_RAW", "\"${providers.gradleProperty("palimpsest.updateRaw").orNull ?: "https://raw.githubusercontent.com"}\"")
    }

    // Official releases are signed with the maintainer's key, configured outside the repository in
    // ~/.gradle/gradle.properties (palimpsest.keystore, .keystore.password, .key.alias, .key.password).
    // Without it (forks, CI) release builds fall back to the debug key so they still install.
    val releaseKeystore = providers.gradleProperty("palimpsest.keystore").orNull
    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = file(releaseKeystore)
            storePassword = providers.gradleProperty("palimpsest.keystore.password").get()
            keyAlias = providers.gradleProperty("palimpsest.key.alias").get()
            keyPassword = providers.gradleProperty("palimpsest.key.password").get()
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.database)
    // Rendering a track to a video (cover or lyrics + visualiser), with the same audio effects as playback.
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.palette)
    implementation(libs.androidx.webkit)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.jaudiotagger)

    testImplementation(libs.junit)
}
