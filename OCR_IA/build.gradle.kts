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
    implementation(libs.koog.agents)
    implementation(libs.koog.google)
    implementation(project(":common-inference"))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)
}

tasks.test {
    useJUnit()
}

tasks.withType<ProcessResources> {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    exclude("**/.venv/**")
    exclude("**/.env")
}

tasks.jar {
    dependsOn(tasks.classes)
    dependsOn(configurations.runtimeClasspath)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    isZip64 = true

    // Include all dependencies in the JAR except those provided by the host toolkit
    // Note: .filter is just to have a smaller jar, you can technically remove it for ease of configuration
    from(configurations.runtimeClasspath.get().filter { file ->
        val path = file.path
        file.isFile &&
        !path.contains("plugin-api") &&
        !path.contains("kotlin-stdlib") &&
        !path.contains("kotlinx-coroutines") &&
        !path.contains("kotlinx-serialization") &&
        !path.contains("koin-core") &&
        !path.contains("slf4j")
    }.map { zipTree(it) })

    exclude("**/.venv/**")
    exclude("**/.env")
    exclude("**/*.pdb")
    exclude("META-INF/*.RSA", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.EC")
    exclude("META-INF/versions/**/module-info.class")
    exclude("module-info.class")
    exclude("META-INF/INDEX.LIST")
    exclude("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
}

