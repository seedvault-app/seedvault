//
// SPDX-FileCopyrightText: 2023 The Calyx Institute
// SPDX-License-Identifier: Apache-2.0
//

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "Seedvault"
include(":core")
include(":app")
include(":contactsbackup")
include(":storage:lib")
include(":storage:demo")
