/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.ksp)
    alias(libs.plugins.google.protobuf)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    namespace = "de.grobox.storagebackuptester"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "de.grobox.storagebackuptester"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 20
        versionName = "0.9.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments.clear()
        testInstrumentationRunnerArguments.putAll(mapOf("disableAnalytics" to "true"))
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }

    lint {
        disable += setOf(
            "DialogFragmentCallbacksDetector",
            "InvalidFragmentVersionForActivityResult"
        )
    }

    packaging {
        jniLibs {
            excludes += listOf("META-INF/services/kotlin*")
        }
        resources {
            excludes += listOf(
                "META-INF/*.kotlin_module",
                "META-INF/androidx.*.version",
                "META-INF/services/kotlin*",
                "kotlin/internal/internal.kotlin_builtins"
            )
        }
    }
}

dependencies {
    implementation(project(":storage:lib"))

    implementation(libs.bundles.kotlin)

    implementation(libs.androidx.core)
    // A newer version gets pulled in with AOSP via core, so we include fragment here explicitly
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.google.material)

    implementation(libs.google.protobuf.javalite)
}
