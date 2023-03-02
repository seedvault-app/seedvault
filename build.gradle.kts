/*
 * SPDX-FileCopyrightText: 2023 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

import org.jlleitschuh.gradle.ktlint.KtlintExtension

buildscript {
    repositories {
        google()
    }
}

plugins {
    id("com.android.application") version plugins.versions.androidGradle apply false
    id("com.android.library") version plugins.versions.androidGradle apply false
    id("com.google.protobuf") version plugins.versions.protobuf apply false
    id("org.jetbrains.kotlin.android") version plugins.versions.kotlin apply false
    id("org.jetbrains.kotlin.kapt") version plugins.versions.kotlin apply false
    id("org.jetbrains.dokka") version plugins.versions.kotlin apply false
    id("org.jlleitschuh.gradle.ktlint") version plugins.versions.ktlint apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
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
