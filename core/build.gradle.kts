plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

// Bytecode for Java 17 (what Android tooling expects), built with whatever JDK runs Gradle.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Builds the printer catalog the app ships (see CatalogTool).
application {
    mainClass.set("io.github.whoxllm.orcamicro.core.profiles.CatalogTool")
}

tasks.test {
    useJUnitPlatform()
    // Tests that read OrcaSlicer's real profiles run when -PorcaResourcesDir points at them.
    providers.gradleProperty("orcaResourcesDir").orNull?.let { systemProperty("orca.resources", it) }
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
