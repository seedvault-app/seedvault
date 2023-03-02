//
// SPDX-FileCopyrightText: 2023 The Calyx Institute
// SPDX-License-Identifier: Apache-2.0
//

pluginManagement {
    buildscript {
        repositories {
            mavenCentral()
            maven {
                // https://issuetracker.google.com/issues/227160052#comment37
                // This can be removed when we switch to Android Gradle plugin 8.2.
                setUrl(uri("https://storage.googleapis.com/r8-releases/raw"))
            }
        }
        dependencies {
            classpath("com.android.tools:r8:8.2.28")
        }
    }

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
    versionCatalogs {
        create("libs") {
            from(files("build.libs.toml"))
        }
        create("plugins") {
            from(files("build.plugins.toml"))
        }
    }
}

rootProject.name = "Seedvault"
include(":app")
include(":contactsbackup")
include(":storage:lib")
include(":storage:demo")
