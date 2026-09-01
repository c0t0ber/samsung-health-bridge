import org.jetbrains.kotlin.gradle.dsl.JvmTarget

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

val canonicalSpreadsheetId = providers.gradleProperty("canonicalSpreadsheetId").orElse("")
val legacySpreadsheetIds = providers.gradleProperty("legacySpreadsheetIds").orElse("")

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.roktober.samsunghealthbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.roktober.samsunghealthbridge"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "1.1.7"

        buildConfigField(
            "String",
            "CANONICAL_SPREADSHEET_ID",
            canonicalSpreadsheetId.get().asBuildConfigString(),
        )
        buildConfigField(
            "String",
            "LEGACY_SPREADSHEET_IDS",
            legacySpreadsheetIds.get().asBuildConfigString(),
        )

    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.work:work-testing:2.11.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
