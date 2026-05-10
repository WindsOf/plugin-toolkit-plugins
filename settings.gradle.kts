rootProject.name = "pluginToolkitPlugins"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        val env = java.util.Properties().apply {
            val envFile = file(".env")
            if (envFile.exists()) {
                envFile.inputStream().use { load(it) }
            }
        }
        fun getEnv(key: String): String? = env.getProperty(key) ?: System.getenv(key)

        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/Wip-Sama/plugin-toolkit")
            credentials {
                username = getEnv("GITHUB_ACTOR")
                password = getEnv("GITHUB_TOKEN")
            }
        }
        maven { url = uri("https://jitpack.io") }
    }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

include(":slicer")
include(":betterimg")
include(":OCR_IA")