/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
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

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    packaging {
        resources {
            excludes += listOf("META-INF/LICENSE.md", "META-INF/LICENSE-notice.md")
        }
    }

    testOptions.unitTests {
        isReturnDefaultValues = true
    }

    // optional signingConfigs
    // On userdebug builds, you can use the testkey here to update the system app
    val keystorePropertiesFile = project.file("keystore.properties")
    if (keystorePropertiesFile.exists()) {
        val keystoreProperties = Properties()
        keystoreProperties.load(FileInputStream(keystorePropertiesFile))

        signingConfigs {
            create("release") {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
        buildTypes.getByName("debug").signingConfig = signingConfigs.getByName("release")
    }
}

val aospDeps = fileTree(mapOf("include" to listOf("com.android.vcard.jar"), "dir" to "libs"))

dependencies {
    implementation(aospDeps)

    testImplementation(libs.kotlin.stdlib.jdk8)
    testImplementation("junit:junit:${libs.versions.junit4.get()}")
    testImplementation("io.mockk:mockk:${libs.versions.mockk.get()}")

    androidTestImplementation(libs.kotlin.stdlib.jdk8)
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation(
        "androidx.test.espresso:espresso-core:${libs.versions.espresso.get()}")
    androidTestImplementation("io.mockk:mockk-android:${libs.versions.mockk.get()}")
}
