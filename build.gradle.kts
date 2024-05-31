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
