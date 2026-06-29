plugins {
    id("java-library")
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}

dependencies {
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.io.core)
    implementation(libs.plugin.api)
    ksp(libs.plugin.api)
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.10.1")
}

tasks.jar {
    dependsOn(configurations.runtimeClasspath)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(configurations.runtimeClasspath.get().filter { file ->
        val path = file.path
        !path.contains("plugin-api") &&
        !path.contains("kotlin-stdlib") &&
        !path.contains("kotlinx-coroutines") &&
        !path.contains("kotlinx-serialization") &&
        !path.contains("koin-core") &&
        !path.contains("slf4j")
    }.map { if (it.isDirectory) it else zipTree(it) })
}


tasks.withType<ProcessResources> {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}