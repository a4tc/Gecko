import org.gradle.api.GradleException
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val configuredDiscordAppId = providers.gradleProperty("DISCORD_APP_ID").orNull?.trim().orEmpty()
val discordAppId = configuredDiscordAppId.toLongOrNull() ?: 0L

if (configuredDiscordAppId.isNotEmpty() && discordAppId <= 0L) {
    throw GradleException("DISCORD_APP_ID must be a positive numeric Discord Application ID")
}

android {
    namespace = "app.geckopresence.host"
    compileSdk = 35

    flavorDimensions += "capability"
    productFlavors {
        create("secure") {
            dimension = "capability"
            buildConfigField("boolean", "AUTOMATION_SOURCES_ENABLED", "false")
        }
        create("automation") {
            dimension = "capability"
            buildConfigField("boolean", "AUTOMATION_SOURCES_ENABLED", "true")
        }
    }

    defaultConfig {
        applicationId = "app.geckopresence.host"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "0.6.6"

        buildConfigField("long", "DISCORD_APP_ID", "${discordAppId}L")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Kizzy's published Gateway implementation uses Ktor 2.x. Pin 2.3.13
    // rather than taking Ktor 3.3+, whose documented release baseline has moved to Kotlin 2.2 and later.
    implementation("io.ktor:ktor-client-core:2.3.13")
    implementation("io.ktor:ktor-client-cio:2.3.13")
    implementation("io.ktor:ktor-client-websockets:2.3.13")

    testImplementation("junit:junit:4.13.2")
}
