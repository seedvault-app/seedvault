//
// SPDX-FileCopyrightText: 2023 The Calyx Institute
// SPDX-License-Identifier: Apache-2.0
//

import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import java.io.ByteArrayOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

val gitDescribe = {
    val stdout = ByteArrayOutputStream()
    exec {
        commandLine("git", "describe", "--always", "--tags", "--dirty=-dirty")
        standardOutput = stdout
    }
    stdout.toString().trim()
}

android {
    namespace = "com.stevesoltys.seedvault"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionNameSuffix = "-${gitDescribe()}"
        testInstrumentationRunner = "com.stevesoltys.seedvault.KoinInstrumentationTestRunner"
        testInstrumentationRunnerArguments["disableAnalytics"] = "true"

        if (project.hasProperty("instrumented_test_size")) {
            val testSize = project.property("instrumented_test_size").toString()
            println("Instrumented test size: $testSize")

            testInstrumentationRunnerArguments["size"] = testSize
        }

        val d2dBackupTest = project.findProperty("d2d_backup_test")?.toString() ?: "true"
        testInstrumentationRunnerArguments["d2d_backup_test"] = d2dBackupTest
    }

    signingConfigs {
        create("aosp") {
            // Generated from the AOSP platform key:
            // https://android.googlesource.com/platform/build/+/refs/tags/android-11.0.0_r29/target/product/security/platform.pk8
            keyAlias = "platform"
            keyPassword = "platform"
            storeFile = file("development/platform.jks")
            storePassword = "platform"
        }
    }

    buildTypes {
        all {
            isMinifyEnabled = false
        }

        getByName("release").signingConfig = signingConfigs.getByName("aosp")
        getByName("debug").signingConfig = signingConfigs.getByName("aosp")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
        languageVersion = "1.8"
    }

    packaging {
        resources {
            excludes += listOf("META-INF/LICENSE.md", "META-INF/LICENSE-notice.md")
        }
    }

    testOptions.unitTests {
        all { it.useJUnitPlatform() }

        isIncludeAndroidResources = true
    }

    sourceSets {
        named("test") {
            java.srcDirs("$projectDir/src/sharedTest/java")
        }
        named("androidTest") {
            java.srcDirs("$projectDir/src/sharedTest/java")
        }
    }

    lint {
        abortOnError = true

        disable.clear()
        disable += setOf(
            "DialogFragmentCallbacksDetector",
            "InvalidFragmentVersionForActivityResult",
            "CheckedExceptions"
        )
    }
}

dependencies {

    val aospLibs = fileTree("$projectDir/libs") {
        // For more information about this module:
        // https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r3/Android.bp#507
        // framework_intermediates/classes-header.jar works for gradle build as well,
        // but not unit tests, so we use the actual classes (without updatable modules).
        //
        // out/target/common/obj/JAVA_LIBRARIES/framework-minus-apex_intermediates/classes.jar
        include("android.jar")
        // out/target/common/obj/JAVA_LIBRARIES/core-libart.com.android.art_intermediates/classes.jar
        include("libcore.jar")
    }

    compileOnly(aospLibs)

    /**
     * Dependencies in AOSP
     *
     * We try to keep the dependencies in sync with what AOSP ships as Seedvault is meant to be built
     * with the AOSP build system and gradle builds are just for more pleasant development.
     * Using the AOSP versions in gradle builds allows us to spot issues early on.
     */
    implementation(libs.bundles.kotlin)
    // These coroutine libraries get upgraded otherwise to versions incompatible with kotlin version
    implementation(libs.bundles.coroutines)

    implementation(libs.androidx.core)
    // A newer version gets pulled in with AOSP via core, so we include fragment here explicitly
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.google.material)

    implementation(libs.google.tink.android)

    /**
     * Storage Dependencies
     */
    implementation(project(":storage:lib"))

    /**
     * External Dependencies
     *
     * If the dependencies below are updated,
     * please make sure to update the prebuilt libraries and the Android.bp files
     * in the top-level `libs` folder to reflect that.
     * You can copy these libraries from ~/.gradle/caches/modules-2/files-2.1
     */
    implementation(fileTree("${rootProject.rootDir}/libs/koin-android").include("*.jar"))
    implementation(fileTree("${rootProject.rootDir}/libs/koin-android").include("*.aar"))

    implementation(fileTree("${rootProject.rootDir}/libs").include("kotlin-bip39-jvm-1.0.6.jar"))

    implementation(fileTree("${rootProject.rootDir}/libs/dav4jvm").include("*.jar"))

    /**
     * Test Dependencies (do not concern the AOSP build)
     */
    lintChecks(libs.thirdegg.lint.rules)

    // anything less than 'implementation' fails tests run with gradlew
    testImplementation(aospLibs)
    testImplementation("androidx.test.ext:junit:1.1.5")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("org.hamcrest:hamcrest:2.2")
    testImplementation("org.junit.jupiter:junit-jupiter-api:${libs.versions.junit5.get()}")
    testImplementation("org.junit.jupiter:junit-jupiter-params:${libs.versions.junit5.get()}")
    testImplementation("io.mockk:mockk:${libs.versions.mockk.get()}")
    testImplementation(
        "org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.coroutines.get()}"
    )
    testImplementation("app.cash.turbine:turbine:1.0.0")
    testImplementation("org.bitcoinj:bitcoinj-core:0.16.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:${libs.versions.junit5.get()}")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:${libs.versions.junit5.get()}")

    androidTestImplementation(aospLibs)
    androidTestImplementation("androidx.test:runner:1.4.0")
    androidTestImplementation("androidx.test:rules:1.4.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.3")
    androidTestImplementation("io.mockk:mockk-android:1.13.8")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.2.0")
}

gradle.projectsEvaluated {
    tasks.withType(JavaCompile::class) {
        options.compilerArgs.add("-Xbootclasspath/p:app/libs/android.jar:app/libs/libcore.jar")
    }
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")

        showExceptions = true
        showCauses = true
        showStackTraces = true
        exceptionFormat = TestExceptionFormat.FULL
    }
}

configurations.all {
    resolutionStrategy {
        failOnNonReproducibleResolution()
    }
}

tasks.register<Exec>("provisionEmulator") {
    group = "emulator"

    dependsOn(tasks.getByName("assembleRelease"))

    doFirst {
        commandLine(
            "${project.projectDir}/development/scripts/provision_emulator.sh",
            "seedvault",
            "system-images;android-34;default;x86_64"
        )

        environment("ANDROID_HOME", android.sdkDirectory.absolutePath)
        environment("JAVA_HOME", System.getProperty("java.home"))
    }
}

tasks.register<Exec>("startEmulator") {
    group = "emulator"

    doFirst {
        commandLine("${project.projectDir}/development/scripts/start_emulator.sh", "seedvault")

        environment("ANDROID_HOME", android.sdkDirectory.absolutePath)
        environment("JAVA_HOME", System.getProperty("java.home"))
    }
}

tasks.register<Exec>("installEmulatorRelease") {
    group = "emulator"

    dependsOn(tasks.getByName("assembleRelease"))

    doFirst {
        commandLine("${project.projectDir}/development/scripts/install_app.sh")

        environment("ANDROID_HOME", android.sdkDirectory.absolutePath)
        environment("JAVA_HOME", System.getProperty("java.home"))
    }
}

tasks.register<Exec>("clearEmulatorAppData") {
    group = "emulator"

    doFirst {
        commandLine("${project.projectDir}/development/scripts/clear_app_data.sh")

        environment("ANDROID_HOME", android.sdkDirectory.absolutePath)
        environment("JAVA_HOME", System.getProperty("java.home"))
    }
}
