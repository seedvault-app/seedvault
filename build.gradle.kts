/*
 * SPDX-FileCopyrightText: 2023 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.google.ksp) apply false
    alias(libs.plugins.google.protobuf) apply false
    alias(libs.plugins.jetbrains.kotlin.android) apply false
    alias(libs.plugins.jetbrains.dokka) apply false
    alias(libs.plugins.jlleitschuh.ktlint) apply false
    alias(libs.plugins.jetbrains.kotlin.jvm) apply false
}

val aospLibs by extra {
    fileTree("$rootDir/libs/aosp") {
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
}

subprojects {
    if (path != ":storage:demo") {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")

        configure<KtlintExtension> {
            version.set("0.42.1")
            android.set(true)
            enableExperimentalRules.set(false)
            verbose.set(true)
            disabledRules.set(listOf("import-ordering", "no-blank-line-before-rbrace", "indent"))
        }
    }
}
