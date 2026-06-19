import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "org.calyxos.backup.contacts"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.calyxos.backup.contacts"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["disableAnalytics"] = "true"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += listOf("META-INF/LICENSE.md", "META-INF/LICENSE-notice.md")
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    testOptions.unitTests {
        isReturnDefaultValues = true
    }

    signingConfigs {
        create("aosp") {
            keyAlias = "android"
            keyPassword = "android"
            storePassword = "android"
            storeFile = file("testkey.jks")
        }
    }

    buildTypes {
        getByName("release").signingConfig = signingConfigs.getByName("aosp")
        getByName("debug").signingConfig = signingConfigs.getByName("aosp")
    }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

// out/soong/.intermediates/frameworks/opt/vcard/com.android.vcard/android_common/javac/com.android.vcard.jar
val aospDeps = fileTree(mapOf("include" to listOf("com.android.vcard.jar"), "dir" to "libs"))

dependencies {
    implementation(aospDeps)

    testImplementation(libs.kotlin.stdlib.jdk8)
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)

    androidTestImplementation(libs.kotlin.stdlib.jdk8)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.mockk.android)
}
