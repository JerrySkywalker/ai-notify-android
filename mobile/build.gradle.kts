import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import com.google.gms.googleservices.GoogleServicesTask
import java.nio.file.Files

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services") version "4.4.4"
}
googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.IGNORE }
tasks.withType<GoogleServicesTask>().configureEach {
    doFirst {
        if (googleServicesJsonFiles.get().none { it.isFile }) {
            // IGNORE leaves the previous variant's resources in place when its JSON is removed.
            Files.deleteIfExists(outputDirectory.file("values/values.xml").get().asFile.toPath())
            Files.deleteIfExists(outputDirectory.file("xml/global_tracker.xml").get().asFile.toPath())
            Files.deleteIfExists(gmpAppId.get().asFile.toPath())
        }
    }
}
android {
    namespace = "io.github.jerryskywalker.ainotify.mobile"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "io.github.jerryskywalker.ainotify"
        minSdk = 30
        targetSdk = 36
        versionCode = 5
        versionName = "0.4.1"
    }
    buildTypes.getByName("debug").applicationIdSuffix = ".dev"
    bundle { language { enableSplit = false } }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.google.firebase:firebase-messaging:24.1.0")
    implementation("com.google.firebase:firebase-installations:18.0.0")
    testImplementation("junit:junit:4.13.2")
}
