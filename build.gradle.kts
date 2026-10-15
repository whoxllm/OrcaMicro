buildscript {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
    dependencies {
        // Left out with -PcoreOnly so the core module builds where Google's Maven repository
        // is unreachable. On the root classpath it shares a classloader with the Kotlin plugin.
        if (!gradle.startParameter.projectProperties.containsKey("coreOnly")) {
            classpath("com.android.tools.build:gradle:8.7.3")
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
