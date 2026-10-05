import java.util.Properties

// Nightly is identified by its commit; CI also passes github.run_number, which only
// serves as versionCode so that each nightly installs over the previous one.
val nightlySha = System.getenv("GITHUB_SHA")?.take(7) ?: "local"
val nightlyBuild = System.getenv("NIGHTLY_BUILD")?.toIntOrNull() ?: 0

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "sgnv.anubis.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "sgnv.anubis.app"
        minSdk = 29
        targetSdk = 34
        versionCode = 11
        versionName = "0.1.6-beta.1"
        buildConfigField("boolean", "IS_NIGHTLY", "false")
        buildConfigField("String", "NIGHTLY_SHA", "\"\"")
    }

    signingConfigs {
        create("release") {
            val props = Properties().apply {
                rootProject.file("signing.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
            }
            storeFile = rootProject.file(props.getProperty("storeFile", "release.keystore"))
            storePassword = props.getProperty("storePassword", "")
            keyAlias = props.getProperty("keyAlias", "")
            keyPassword = props.getProperty("keyPassword", "")
        }
        // Nightly key comes from CI env (see .github/workflows/nightly.yml); it is
        // deliberately separate from the release key.
        System.getenv("NIGHTLY_STORE_FILE")?.let { nightlyStoreFile ->
            create("nightly") {
                storeFile = file(nightlyStoreFile)
                storePassword = System.getenv("NIGHTLY_STORE_PASSWORD")
                keyAlias = System.getenv("NIGHTLY_KEY_ALIAS")
                keyPassword = System.getenv("NIGHTLY_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // Separate package with its own key and self-update channel (the "nightly"
        // GitHub release); stable and beta distribution is untouched.
        create("nightly") {
            initWith(getByName("release"))
            applicationIdSuffix = ".nightly"
            versionNameSuffix = "-nightly.$nightlySha"
            // Local builds without the CI key fall back to the debug key.
            signingConfig = signingConfigs.findByName("nightly") ?: signingConfigs.getByName("debug")
            buildConfigField("boolean", "IS_NIGHTLY", "true")
            buildConfigField("String", "NIGHTLY_SHA", "\"$nightlySha\"")
        }
    }

    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = if (variant.buildType.name == "nightly") {
                // UpdateChecker reads the commit from this name.
                "anubis-nightly-$nightlySha.apk"
            } else {
                "anubis-${variant.versionName}-${variant.name}.apk"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
}

androidComponents {
    onVariants(selector().withBuildType("nightly")) { variant ->
        variant.outputs.forEach { it.versionCode.set(maxOf(nightlyBuild, 1)) }
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.05.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.startup:startup-runtime:1.2.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Shizuku
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // HiddenApiBypass — access to @hide IPackageManager/IActivityManager methods on Android 9+
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
