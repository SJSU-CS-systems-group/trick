import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }
    
    sourceSets {
        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)

            // REAL Signal Foundation libsignal - use only the Android native library
            // This gives us the native Rust libsignal without the problematic JVM wrapper
            implementation("org.signal:libsignal-android:0.79.0") {
                exclude(group = "org.signal", module = "libsignal-client")
            }
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

android {
    namespace = "net.discdd.trick"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "net.discdd.trick"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
        
        // Enable multidex to handle large libsignal-client
        multiDexEnabled = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Exclude non-Android libsignal native libraries as recommended by Signal
            excludes += setOf("libsignal_jni*.dylib", "signal_jni*.dll")
            // Exclude testing libraries if not needed
            excludes += "libsignal_jni_testing.so"
            // Exclude problematic META-INF files that can cause issues
            excludes += "/META-INF/versions/**"
        }
        jniLibs {
            // Keep only Android-compatible native libraries
            excludes += setOf("**/*.dylib", "**/*.dll")
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // Disable R8 optimizations that cause issues with libsignal
            proguardFiles(getDefaultProguardFile("proguard-android.txt"))
        }
        getByName("debug") {
            isMinifyEnabled = false
            isShrinkResources = false
            // Disable all optimizations for debug and handle libsignal-client JVM classes
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true  // Required by libsignal-android
    }
}

dependencies {
    debugImplementation(compose.uiTooling)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3") // Required by libsignal-android
}

