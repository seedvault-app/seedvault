import com.google.protobuf.gradle.id

/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.google.ksp)
    alias(libs.plugins.google.protobuf)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.jetbrains.dokka)
}

android {
    namespace = "org.calyxos.backup.storage"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["disableAnalytics"] = "true"
    }

    buildTypes {
        all {
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
        languageVersion = "1.8"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-Xexplicit-api=strict"
        )
    }

    protobuf {
        protoc {
            artifact = if ("aarch64" == System.getProperty("os.arch")) {
                // mac m1
                "com.google.protobuf:protoc:${libs.versions.protobuf.get()}:osx-x86_64"
            } else {
                // other
                "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
            }
        }
        generateProtoTasks {
            all().forEach { task ->
                task.builtins {
                    id("java") {
                        option("lite")
                    }
                }
            }
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false

        disable.clear()
        disable += setOf(
            "DialogFragmentCallbacksDetector",
            "InvalidFragmentVersionForActivityResult",
            "CheckedExceptions"
        )
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.bundles.kotlin)
    implementation(libs.androidx.core)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.documentfile)
    implementation(libs.google.material)
    implementation(libs.androidx.room.runtime)
    implementation(libs.google.protobuf.javalite)

    ksp(group = "androidx.room", name = "room-compiler", version = libs.versions.room.get())
    lintChecks(libs.thirdegg.lint.rules)
    testImplementation("junit:junit:${libs.versions.junit4.get()}")
    testImplementation("io.mockk:mockk:${libs.versions.mockk.get()}")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:${libs.versions.kotlin.get()}")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation(
        "androidx.test.espresso:espresso-core:${libs.versions.espresso.get()}"
    )
}
