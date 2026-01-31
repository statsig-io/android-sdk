import org.jetbrains.kotlin.config.JvmDefaultMode

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.gradle.ktlint)
    alias(libs.plugins.gradle.nexus.publish)
    id("maven-publish")
    id("signing")
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

android {
    namespace = "com.statsig.androidsdk"
    android.buildFeatures.buildConfig = true

    compileSdk = 34

    defaultConfig {
        minSdk = 21
        aarMetadata {
            minCompileSdk = 33
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("String", "VERSION_NAME", "\"${project.property("libraryVersion")}\"")
        }
        getByName("release") {
            buildConfigField("String", "VERSION_NAME", "\"${project.property("libraryVersion")}\"")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "consumer-rules.pro"
            )
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(17)
        }
    }
    kotlin {
        jvmToolchain(17)
        compilerOptions {
            freeCompilerArgs.add("-Xjvm-default=all")
        }
    }
}

ktlint {
    version = "1.7.1"
    verbose = true
    relative = true
    android = true
}

tasks.withType<Test> {
    testLogging {
        events("passed", "skipped", "failed")
    }
    maxParallelForks = Runtime.getRuntime().availableProcessors()
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.core.ktx)
    implementation(libs.appcompat)

    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.okhttp.dnsoverhttps)
    implementation(libs.annotation.experimental)
    implementation(libs.datastore.core)
    implementation(libs.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.wiremock)
    testImplementation(libs.slf4j.simple)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                group = "com.statsig"
                artifactId = "android-sdk"
                version = project.property("libraryVersion") as String

                pom {
                    name = "Statsig Android SDK"
                    description = "A SDK for integrating Statsig with Android Apps"
                    url = "https://github.com/statsig-io/android-sdk"
                    organization {
                        name = "Statsig"
                        url = "https://www.statsig.com"
                    }
                    licenses {
                        license {
                            name = "Apache License, Version 2.0"
                            url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                            distribution = "repo"
                        }
                    }
                    developers {
                        developer {
                            id = "tore"
                            name = "Tore Hanssen"
                            email = "tore@statsig.com"
                        }
                        developer {
                            id = "kevin"
                            name = "Kevin Maurin"
                            email = "kevin@statsig.com"
                        }
                    }
                    scm {
                        connection.set("scm:git:git://github.com/statsig-io/android-sdk.git")
                        developerConnection.set(
                            "scm:git:ssh://git@github.com:statsig-io/android-sdk.git"
                        )
                        url.set("https://github.com/statsig-io/android-sdk")
                    }
                }
                afterEvaluate {
                    from(components["release"])
                }
            }
        }
        repositories {
            maven {
                name = "sonatype"
                url = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
                credentials {
                    username = System.getenv("ORG_GRADLE_PROJECT_MAVEN_USERNAME")
                    password = System.getenv("ORG_GRADLE_PROJECT_MAVEN_PASSWORD")
                }
            }
        }
    }

    signing {
        val signingKeyId = System.getenv("ORG_GRADLE_PROJECT_SIGNING_KEY_ID") ?: ""
        val signingKey = System.getenv("ORG_GRADLE_PROJECT_SIGNING_KEY") ?: ""
        val signingPassword = System.getenv("ORG_GRADLE_PROJECT_SIGNING_PASSWORD") ?: ""
        useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
        if (signingKeyId.isNotEmpty()) {
            sign(publishing.publications["release"])
        }
    }
}

if (project == rootProject) {
    nexusPublishing {
        repositories {
            sonatype {
                nexusUrl =
                    uri("https://ossrh-staging-api.central.sonatype.com/service/local/")
                snapshotRepositoryUrl =
                    uri("https://central.sonatype.com/repository/maven-snapshots/")
                username = System.getenv("ORG_GRADLE_PROJECT_MAVEN_USERNAME")
                password = System.getenv("ORG_GRADLE_PROJECT_MAVEN_PASSWORD")
            }
        }
    }
}
