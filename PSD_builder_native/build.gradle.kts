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

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)
}

tasks.test {
    useJUnit()
}

tasks.withType<ProcessResources> {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    // Include all dependencies in the JAR except those provided by the host toolkit
    from(configurations.runtimeClasspath.get().filter { file ->
        val path = file.path
        !path.contains("plugin-api") &&
        !path.contains("kotlin-stdlib") &&
        !path.contains("kotlinx-coroutines") &&
        !path.contains("kotlinx-serialization") &&
        !path.contains("koin-core") &&
        !path.contains("slf4j")
    }.map { if (it.isDirectory) it else zipTree(it) })

    exclude("**/.venv/**")
    exclude("**/.env")
    exclude("META-INF/*.RSA", "META-INF/*.SF", "META-INF/*.DSA")
    exclude("META-INF/versions/**/module-info.class")
    exclude("module-info.class")
    exclude("META-INF/INDEX.LIST")
    exclude("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
}

