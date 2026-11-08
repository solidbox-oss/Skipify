import java.util.Properties

plugins { id("com.android.application") }

// Release signing is read from a properties file kept OUTSIDE the repository
// (default ~/.gradle/skipify-signing.properties, or the path in SKIPIFY_SIGNING).
// Keys: storeFile, storePassword, keyAlias, keyPassword. Without it, release builds are unsigned.
val signingFile = file(System.getenv("SKIPIFY_SIGNING") ?: "${System.getProperty("user.home")}/.gradle/skipify-signing.properties")
val signing = Properties().apply { if (signingFile.exists()) signingFile.inputStream().use { load(it) } }

android {
    namespace = "app.skipify.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.skipify.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (signing.containsKey("storeFile")) create("release") {
            storeFile = file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
        // Release-optimized staging build, installable next to it, with debug test triggers for
        // device checks of the R8 output without replacing an installed release.
        create("staging") {
            initWith(getByName("release"))
            applicationIdSuffix = ".staging"
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    sourceSets {
        getByName("staging") {
            java.srcDir("src/debug/java")
            manifest.srcFile("src/debug/AndroidManifest.xml")
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        disable += setOf("GradleDependency", "OldTargetApi", "IconMissingDensityFolder", "IconLauncherShape")
    }

    testOptions { unitTests.isReturnDefaultValues = true }

    dependenciesInfo {
        // No Google-encrypted dependency blob in the APK: keeps it reproducible for F-Droid-style checks.
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
}
