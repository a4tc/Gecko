import org.gradle.api.GradleException
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val discordSdkAar = file("libs/discord_partner_sdk.aar")
val configuredDiscordAppId = providers.gradleProperty("DISCORD_APP_ID").orNull?.trim().orEmpty()
val discordAppId = configuredDiscordAppId.toLongOrNull() ?: 0L
val discordSdkEnabled = discordSdkAar.exists() && discordAppId > 0L

if (configuredDiscordAppId.isNotEmpty() && discordAppId <= 0L) {
    throw GradleException("DISCORD_APP_ID must be a positive numeric Discord Application ID")
}

if (discordSdkAar.exists() && discordAppId <= 0L) {
    logger.warn("Discord SDK AAR found, but DISCORD_APP_ID is not configured. Building local preview mode.")
}

android {
    namespace = "app.geckopresence.host"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.geckopresence.host"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.6.2"

        buildConfigField("boolean", "DISCORD_SDK_ENABLED", discordSdkEnabled.toString())
        buildConfigField("long", "DISCORD_APP_ID", "${discordAppId}L")

        if (discordSdkEnabled) {
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
            externalNativeBuild {
                cmake {
                    cppFlags += listOf("-std=c++17", "-fvisibility=hidden")
                }
            }
        }
    }

    buildFeatures {
        buildConfig = true
        prefab = discordSdkEnabled
    }

    if (discordSdkEnabled) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
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
    testImplementation("junit:junit:4.13.2")

    if (discordSdkEnabled) {
        implementation(files(discordSdkAar))
    }
}
