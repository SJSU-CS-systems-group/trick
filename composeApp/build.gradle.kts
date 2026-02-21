import org.gradle.api.JavaVersion
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.wire)
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // Map KMP iOS targets to Rust triple directories
    val rustTargetDir = "${project.rootDir}/rust/trick-signal-ffi/target"
    val iosTargetToRustTriple = mapOf(
        "iosArm64" to "aarch64-apple-ios",
        "iosSimulatorArm64" to "aarch64-apple-ios-sim",
        "iosX64" to "x86_64-apple-ios"
    )

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }

        val rustTriple = iosTargetToRustTriple[iosTarget.name]
            ?: error("Unknown iOS target: ${iosTarget.name}")

        // Configure C interop for LibSignal FFI on iOS
        iosTarget.compilations.getByName("main") {
            cinterops {
                val libsignal by creating {
                    defFile(project.file("src/nativeInterop/cinterop/libsignal.def"))
                    packageName("net.discdd.trick.libsignal.bridge")
                    compilerOpts("-I${project.rootDir}/rust/trick-signal-ffi")
                    extraOpts("-libraryPath", "$rustTargetDir/$rustTriple/release")
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
                implementation(compose.components.resources)

                implementation(libs.navigation.compose)
                implementation(libs.lifecycle.runtime.compose)
                implementation(libs.material.icons.core)
                implementation(libs.material.icons.extended)

                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)

                implementation(libs.coil.compose)
                implementation(libs.coil.network.ktor)
                implementation(libs.koin.core)
                implementation(libs.koin.compose.viewmodel)
                implementation(libs.sqldelight.runtime)
                implementation(libs.sqldelight.coroutines.extensions)
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
                implementation(libs.sqldelight.android.driver)

                // QR Code generation and scanning
                implementation("com.google.zxing:core:3.5.3")
                implementation("com.journeyapps:zxing-android-embedded:4.3.0")
                implementation("com.google.mlkit:barcode-scanning:17.3.0")

                // CameraX for QR scanning (1.4.1+ required for 16 KB page alignment on Android 15+)
                implementation("androidx.camera:camera-camera2:1.4.2")
                implementation("androidx.camera:camera-lifecycle:1.4.2")
                implementation("androidx.camera:camera-view:1.4.2")

                // Permissions handling
                implementation("com.google.accompanist:accompanist-permissions:0.34.0")

                // Force 16 KB-aligned version of AndroidX graphics path (Android 15+ compatibility)
                implementation("androidx.graphics:graphics-path:1.0.1")

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
                implementation(libs.sqldelight.native.driver)
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
        }
        jniLibs {
            // Ensure 16 KB alignment for Android 15+ compatibility
            // This forces proper alignment of all native libraries during APK packaging
            useLegacyPackaging = false
            // Pack all shared libraries uncompressed for proper alignment
            pickFirsts += "**/libc++_shared.so"
            pickFirsts += "**/libtrick_signal_ffi.so"
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
    }
}

dependencies {
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Rust FFI build task for Android — run with: ./gradlew buildRustAndroid
val cargoHome = System.getenv("CARGO_HOME") ?: "${System.getProperty("user.home")}/.cargo"
val cargoBin = "$cargoHome/bin/cargo"

tasks.register<Exec>("buildRustAndroid") {
    workingDir = file("../rust/trick-signal-ffi")
    environment("ANDROID_NDK_HOME", System.getenv("ANDROID_NDK_HOME") ?: "")
    commandLine(cargoBin, "ndk",
        "-t", "aarch64-linux-android",
        "-t", "x86_64-linux-android",
        "-o", "${project.projectDir}/src/androidMain/jniLibs",
        "build", "--release"
    )
}

// iOS Rust FFI: use rust/trick-signal-ffi/build-ios.sh

sqldelight {
    databases {
        create("TrickDatabase") {
            packageName.set("net.discdd.trick")
            verifyMigrations.set(true)
        }
    }
}

wire {
    kotlin {
        // Generate Kotlin code for all platforms
    }
    sourcePath {
        srcDir("src/commonMain/proto")
    }
}
