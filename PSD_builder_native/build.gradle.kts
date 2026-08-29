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
    implementation(libs.plugin.api)
    implementation(libs.kpsd)
    implementation(libs.twelvemonkeys.webp)
    ksp(libs.plugin.api)
    implementation(project(":common-models"))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

tasks.test {
    useJUnit()
}

tasks.withType<ProcessResources> {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

tasks.jar {
    dependsOn(tasks.classes)
    dependsOn(configurations.runtimeClasspath)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    isZip64 = true
    
    // Include all dependencies in the JAR except those provided by the host toolkit
    from(configurations.runtimeClasspath.get().filter { file ->
        val path = file.path
        !path.contains("plugin-api") &&
        !path.contains("kotlin-stdlib") &&
        !path.contains("kotlin-reflect") &&
        !path.contains("kotlinx-coroutines") &&
        !path.contains("kotlinx-serialization") &&
        !path.contains("koin-core") &&
        !path.contains("slf4j") &&
        !path.contains("ktor")
    }.map { if (it.isDirectory) it else zipTree(it) })
}

