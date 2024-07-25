plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

android {
    namespace = "org.calyxos.seedvault.core"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["disableAnalytics"] = "true"
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
}

dependencies {
    val aospLibs: FileTree by rootProject.extra
    compileOnly(aospLibs)
    compileOnly("org.ogce:xpp3:1.1.6")
    compileOnly(kotlin("test"))
    implementation(libs.bundles.kotlin)
    implementation(libs.bundles.coroutines)
    implementation(libs.androidx.documentfile)
    // implementation(fileTree("${rootProject.rootDir}/libs/dav4jvm").include("okio-jvm-3.7.0.jar"))
    implementation(fileTree("${rootProject.rootDir}/libs/dav4jvm").include("*.jar"))
    implementation("io.github.oshai:kotlin-logging-jvm:6.0.3")
    implementation("org.slf4j:slf4j-simple:2.0.3")

    testImplementation(kotlin("test"))
    testImplementation("org.ogce:xpp3:1.1.6")
}
