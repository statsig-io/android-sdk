package com.statsig.gradle.plugins

import com.android.build.api.dsl.LibraryExtension
import com.android.build.gradle.LibraryPlugin
import com.statsig.gradle.utils.Versions
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformAndroidPlugin

/**
 * Wrapper plugin for any Android library module in this project.
 * This plugin only sets the common configuration used in this project, but can be overridden by the module's build.gradle file.
 */
class StatsigLibraryPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.apply<LibraryPlugin>() // com.android.library
        target.apply<KotlinPlatformAndroidPlugin>() // kotlin-android

        target.configure<LibraryExtension> { // android { }
            compileSdk = Versions.Android.CompileSdkVersion
            buildToolsVersion = Versions.Android.BuildToolVersion

            defaultConfig {
                minSdk = Versions.Android.MinSdkVersion
                targetSdk = Versions.Android.TargetSdkVersion

                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                consumerProguardFiles("consumer-rules.pro")
            }

            buildTypes {

                // Sets the common config for all build types
                all {
                    buildConfigField("String", "VERSION_NAME", "\"${target.version}\"")
                }

                debug {
                    // debug configuration... Nothing special for it yet.
                }

                release {
                    // release configuration
                    isMinifyEnabled = false
                    proguardFiles.addAll(listOf(getDefaultProguardFile("proguard-android-optimize.txt"), target.file("proguard-rules.pro")))
                }
            }

            testOptions {
                unitTests.all {
                    it.testLogging {
                        events("passed", "skipped", "failed")
                    }
                }
            }

            // Extension that allows publishing of javadoc and sources without setting it up in the publishing plugin
            publishing {
                singleVariant("release") { // We are only publishing the release build for now.
                    withJavadocJar()
                    withSourcesJar()
                }
            }
        }
    }
}
