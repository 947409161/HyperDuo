plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.hyperduo.trio"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hyperduo.trio"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }

    signingConfigs {
        // Same keystore the hand-rolled pipeline used, so that in-place updates
        // over the already-installed module keep working.
        create("hyperduo") {
            storeFile = rootProject.file(".tools/debug.keystore")
            storePassword = "android"
            keyAlias = "hyperduo"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("hyperduo")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("hyperduo")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Keep META-INF/xposed/{java_init.list,scope.list,module.prop} in the APK.
    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Provided by the Xposed framework at runtime, never bundled.
    compileOnly("io.github.libxposed:api:102.0.0")
    // Needed by the settings app to talk to the framework service.
    implementation("io.github.libxposed:service:102.0.0")

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.3")
}
