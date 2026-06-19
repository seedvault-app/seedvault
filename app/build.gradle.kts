//
// SPDX-FileCopyrightText: 2023 The Calyx Institute
// SPDX-License-Identifier: Apache-2.0
//

import com.google.protobuf.gradle.id
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.protobuf)
}

val gitDescribe: String
    get() {
        val process =
            ProcessBuilder("git", "describe", "--always", "--tags", "--dirty=-dirty")
                .directory(rootDir)
                .redirectErrorStream(true)
                .start()
        process.waitFor() // Ensure the command completes
        return process.inputStream.use { it.readBytes().decodeToString().trim() }
    }

android {
    namespace = "com.stevesoltys.seedvault"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionNameSuffix = "-$gitDescribe"
        testInstrumentationRunner = "com.stevesoltys.seedvault.KoinInstrumentationTestRunner"
        testInstrumentationRunnerArguments["disableAnalytics"] = "true"
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
        getByName("test") {
            kotlin.directories += "src/sharedTest/java"
        }
        getByName("androidTest") {
            kotlin.directories += "src/sharedTest/java"
        }
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
                task.plugins {
                    id("java") {
                        option("lite")
                    }
                    id("kotlin") {
                        option("lite")
                    }
                }
            }
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

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
    val aospLibs: FileTree by rootProject.extra
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

    implementation(libs.google.protobuf.javalite)
    implementation(libs.google.tink.android)
    implementation(libs.kotlin.logging)
    implementation(libs.squareup.okio)

    /**
     * Storage Dependencies
     */
    implementation(project(":core"))
    implementation(project(":storage:lib"))

    /**
     * External Dependencies
     *
     * If the dependencies below are updated,
     * please make sure to update the prebuilt libraries and the Android.bp files
     * in the top-level `libs` folder to reflect that.
     * You can copy these libraries from ~/.gradle/caches/modules-2/files-2.1
     */
    // implementation("io.insert-koin:koin-core-viewmodel-jvm:4.2.2")
    implementation(fileTree("${rootProject.rootDir}/libs/koin-android").include("*.jar"))
    implementation(fileTree("${rootProject.rootDir}/libs/koin-android").include("*.aar"))

    // implementation("com.google.protobuf:protobuf-kotlin-lite:4.35.1")
    implementation(
        fileTree("${rootProject.rootDir}/libs").include("protobuf-kotlin-lite-*.jar")
    )
    implementation(fileTree("${rootProject.rootDir}/libs").include("seedvault-chunker-*.jar"))
    // implementation("com.github.luben:zstd-jni:1.5.7-11@aar")
    implementation(fileTree("${rootProject.rootDir}/libs").include("zstd-jni-*.aar"))
    implementation(fileTree("${rootProject.rootDir}/libs").include("kotlin-bip39-jvm-*.jar"))
    implementation(fileTree("${rootProject.rootDir}/libs").include("logback-android-*.aar"))

    /**
     * Test Dependencies (do not concern the AOSP build)
     */
    lintChecks(libs.thirdegg.lint.rules)

    // anything less than 'implementation' fails tests run with gradlew
    testImplementation(aospLibs)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.slf4j.simple)
    testImplementation(libs.robolectric)
    testImplementation(libs.hamcrest)
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.bitcoinj.core)
    testImplementation(libs.zstd.jni)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.vintage.engine)

    androidTestImplementation(aospLibs)
    androidTestImplementation(kotlin("test"))
    androidTestImplementation(libs.androidx.runner)
    androidTestImplementation(libs.androidx.rules)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.androidx.uiautomator)
}

gradle.projectsEvaluated {
    tasks.withType(JavaCompile::class) {
        options.compilerArgs.add("-Xbootclasspath/p:libs/aosp/android.jar:libs/aosp/libcore.jar")
    }
}

tasks.withType<KotlinCompile> {
    doFirst {
        val aospFiles = project.files(
            "$rootDir/libs/aosp/android.jar",
            "$rootDir/libs/aosp/libcore.jar",
        )
        val list = buildList {
            add(aospFiles)
            addAll(libraries.from)
        }
        libraries.setFrom(list)
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

androidComponents {
    val sdkDirProvider = sdkComponents.sdkDirectory

    tasks.register<Exec>("provisionEmulator") {
        group = "emulator"
        inputs.dir(sdkDirProvider)

        dependsOn(tasks.getByName("assembleRelease"))

        doFirst {
            commandLine(
                "${project.projectDir}/development/scripts/provision_emulator.sh",
                "seedvault",
                "system-images;android-34;default;x86_64"
            )
            val sdkDirFile = sdkDirProvider.get().asFile
            environment("ANDROID_HOME", sdkDirFile.absolutePath)
            environment("JAVA_HOME", System.getProperty("java.home"))
        }
    }

    tasks.register<Exec>("startEmulator") {
        group = "emulator"
        inputs.dir(sdkDirProvider)

        doFirst {
            commandLine("${project.projectDir}/development/scripts/start_emulator.sh", "seedvault")

            val sdkDirFile = sdkDirProvider.get().asFile
            environment("ANDROID_HOME", sdkDirFile.absolutePath)
            environment("JAVA_HOME", System.getProperty("java.home"))
        }
    }

    tasks.register<Exec>("installEmulatorRelease") {
        group = "emulator"
        inputs.dir(sdkDirProvider)

        dependsOn(tasks.getByName("assembleRelease"))

        doFirst {
            commandLine("${project.projectDir}/development/scripts/install_app.sh")

            val sdkDirFile = sdkDirProvider.get().asFile
            environment("ANDROID_HOME", sdkDirFile.absolutePath)
            environment("JAVA_HOME", System.getProperty("java.home"))
        }
    }

    tasks.register<Exec>("clearEmulatorAppData") {
        group = "emulator"
        inputs.dir(sdkDirProvider)

        doFirst {
            commandLine("${project.projectDir}/development/scripts/clear_app_data.sh")

            val sdkDirFile = sdkDirProvider.get().asFile
            environment("ANDROID_HOME", sdkDirFile.absolutePath)
            environment("JAVA_HOME", System.getProperty("java.home"))
        }
    }
}
