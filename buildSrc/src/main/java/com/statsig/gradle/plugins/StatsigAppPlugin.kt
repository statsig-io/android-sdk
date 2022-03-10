package com.statsig.gradle.plugins

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.gradle.AppPlugin
import com.statsig.gradle.utils.Versions
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformAndroidPlugin

/**
 * Wrapper plugin for any Android application module in this project.
 * This plugin only sets the common configuration used in this project, but can be overridden by the module's build.gradle file.
 */
class StatsigAppPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.apply<AppPlugin>() // com.android.application
        target.apply<KotlinPlatformAndroidPlugin>() // kotlin-android

        target.configure<ApplicationExtension> { // android { }
            compileSdk = Versions.Android.CompileSdkVersion

            defaultConfig {
                minSdk = Versions.Android.MinSdkVersion
                targetSdk = Versions.Android.TargetSdkVersion
                versionCode = 1
                versionName = "1.0"

                buildConfigField("String", "STATSIG_CLIENT_KEY", "\"${System.getenv("STATSIG_CLIENT_KEY").orEmpty()}\"")

                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }

            buildTypes {
                release {
                    isMinifyEnabled = false
                    proguardFiles.addAll(listOf(getDefaultProguardFile("proguard-android-optimize.txt"), target.file("proguard-rules.pro")))
                }
            }

            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_11
                targetCompatibility = JavaVersion.VERSION_11
            }

            buildFeatures {
                viewBinding = true
            }

            testOptions {
                unitTests.all {
                    it.testLogging {
                        events("passed", "skipped", "failed")
                    }
                }
            }
        }
    }
}
