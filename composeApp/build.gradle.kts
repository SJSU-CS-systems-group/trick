import org.gradle.api.JavaVersion
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.wire)
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
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

        // Configure C interop for LibSignal FFI on iOS
        iosTarget.compilations.getByName("main") {
            cinterops {
                val libsignal by creating {
                    defFile(project.file("src/nativeInterop/cinterop/libsignal.def"))
                    packageName("net.discdd.trick.libsignal.bridge")
                    compilerOpts("-I${project.file("src/nativeInterop/cinterop").absolutePath}")
                }
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                // REMOVE these three lines from here:
                // implementation(compose.preview)
                // implementation(compose.components.resources)
                // implementation(compose.components.uiToolingPreview)

                // Keep resources (it’s multiplatform-safe)
                implementation(compose.components.resources)

                implementation(libs.navigation.compose)
                implementation(libs.lifecycle.runtime.compose)
                implementation(libs.material.icons.core)

                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)

                implementation(libs.coil.compose)
                implementation(libs.coil.network.ktor)
                implementation(libs.koin.core)
                implementation(libs.koin.compose.viewmodel)
                implementation(libs.wire.runtime)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.compose.ui.tooling.preview)
                implementation(libs.ktor.client.okhttp)

                // REAL Signal Foundation libsignal: include both per docs
                implementation("org.signal:libsignal-android:0.79.0")
                implementation("org.signal:libsignal-client:0.79.0")

                // QR Code generation and scanning
                implementation("com.google.zxing:core:3.5.3")
                implementation("com.journeyapps:zxing-android-embedded:4.3.0")
                implementation("com.google.mlkit:barcode-scanning:17.3.0")

                // CameraX for QR scanning
                implementation("androidx.camera:camera-camera2:1.3.1")
                implementation("androidx.camera:camera-lifecycle:1.3.1")
                implementation("androidx.camera:camera-view:1.3.1")

                // Permissions handling
                implementation("com.google.accompanist:accompanist-permissions:0.34.0")

                // JetBrains Compose preview/tooling ONLY on Android
                implementation(compose.preview)
                implementation(compose.components.uiToolingPreview)
            }
        }
        val androidUnitTest by getting

        val iosX64Main by getting
        val iosArm64Main by getting
        val iosSimulatorArm64Main by getting
        val iosMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }
        iosX64Main.dependsOn(iosMain)
        iosArm64Main.dependsOn(iosMain)
        iosSimulatorArm64Main.dependsOn(iosMain)

        val iosX64Test by getting
        val iosArm64Test by getting
        val iosSimulatorArm64Test by getting
        val iosTest by creating {
            dependsOn(commonTest)
        }
        iosX64Test.dependsOn(iosTest)
        iosArm64Test.dependsOn(iosTest)
        iosSimulatorArm64Test.dependsOn(iosTest)
    }
}

android {
    namespace = "net.discdd.trick"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.discdd.trick"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // Enable multidex to handle libsignal
        multiDexEnabled = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Exclude non-Android libsignal native libraries per Signal docs
            excludes += setOf("libsignal_jni*.dylib", "signal_jni*.dll")
            // Optional: exclude testing JNI if not used
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required by libsignal-android
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    debugImplementation(libs.androidx.compose.ui.tooling)
    // Required by libsignal-android when using Java 17 features
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
}

wire {
    kotlin {
        // Generate Kotlin code for all platforms
    }
    sourcePath {
        srcDir("src/commonMain/proto")
    }
}
