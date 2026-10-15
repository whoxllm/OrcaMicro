import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The OrcaSlicer release the engine and the bundled profiles come from.
val orcaTag: String = rootProject.file("native/orca.env").readLines()
    .first { it.startsWith("ORCA_TAG=") }.substringAfter('=')

// Release APKs are signed with the key named by these environment variables; CI fills them
// from the repository's secrets. Without them the release APK gets the local debug key, which
// is fine for testing but will not install over a published release.
//   ORCAMICRO_KEYSTORE           path to the keystore file
//   ORCAMICRO_KEYSTORE_PASSWORD  its password (also the key's password, as for PKCS12 keystores)
//   ORCAMICRO_KEY_ALIAS          the key's alias, "orcamicro" if unset
val releaseKeystore: File? = providers.environmentVariable("ORCAMICRO_KEYSTORE").orNull
    ?.takeIf { it.isNotBlank() }?.let { file(it) }

android {
    namespace = "io.github.whoxllm.orcamicro"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.whoxllm.orcamicro"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // The engine is built for 64-bit ARM phones only.
            abiFilters += "arm64-v8a"
        }
        buildConfigField("String", "ORCA_VERSION", "\"$orcaTag\"")
        buildConfigField("String", "SOURCE_URL", "\"https://github.com/whoxllm/OrcaMicro\"")
    }

    signingConfigs {
        releaseKeystore?.let { keystore ->
            require(keystore.isFile) { "ORCAMICRO_KEYSTORE points to a missing file: $keystore" }
            val password = requireNotNull(providers.environmentVariable("ORCAMICRO_KEYSTORE_PASSWORD").orNull) {
                "ORCAMICRO_KEYSTORE is set but ORCAMICRO_KEYSTORE_PASSWORD is not"
            }
            create("release") {
                storeFile = keystore
                storePassword = password
                keyAlias = providers.environmentVariable("ORCAMICRO_KEY_ALIAS").orNull ?: "orcamicro"
                keyPassword = password
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/orcaAssets").get().asFile)

    lint {
        // Release builds run lint; report issues without failing CI builds over them.
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

// ---- OrcaSlicer profiles and engine resources, packed as assets ------------------------------

val orcaResourcesDir = providers.gradleProperty("orcaResourcesDir")
val generatedAssets = layout.buildDirectory.dir("generated/orcaAssets")

val catalogTool: Configuration by configurations.creating {
    isCanBeConsumed = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}

dependencies {
    catalogTool(project(":core"))
}

val copyOrcaResources by tasks.registering(Sync::class) {
    description = "Copies OrcaSlicer's printer profiles and engine data into the app assets."
    val resources = orcaResourcesDir.map { file(it) }
    from(resources.map { it.resolve("profiles") }) {
        include("**/*.json")
        into("orca/profiles")
    }
    from(resources.map { it.resolve("info") }) { into("orca/engine/info") }
    from(resources.map { it.resolve("flush") }) { into("orca/engine/flush") }
    into(generatedAssets)
    preserve { include("orca/catalog.json") }
    doFirst {
        val dir = orcaResourcesDir.orNull?.let { file(it) }
        require(dir != null && dir.resolve("profiles").isDirectory) {
            "Pass -PorcaResourcesDir=<OrcaSlicer checkout>/resources (see native/scripts/fetch-orca.sh --profiles-only)"
        }
    }
}

val buildOrcaCatalog by tasks.registering(JavaExec::class) {
    description = "Indexes the bundled printer profiles into orca/catalog.json."
    dependsOn(copyOrcaResources)
    classpath = catalogTool
    mainClass.set("io.github.whoxllm.orcamicro.core.profiles.CatalogTool")
    val profiles = generatedAssets.map { it.dir("orca/profiles") }
    val catalog = generatedAssets.map { it.file("orca/catalog.json") }
    inputs.dir(profiles)
    outputs.file(catalog)
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(profiles.get().asFile.path, catalog.get().asFile.path, orcaTag)
    })
}

tasks.named("preBuild") { dependsOn(buildOrcaCatalog) }

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
