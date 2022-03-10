plugins {
    `kotlin-dsl`
    kotlin("jvm") version "1.6.10"
}

repositories {
    google()
    mavenCentral()
    maven("https://plugins.gradle.org/m2/")
}

dependencies {
    implementation("com.android.tools.build:gradle:7.1.2")
    implementation("com.android.tools.build:gradle-api:7.1.2")

    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:1.6.10")
}

gradlePlugin {
    plugins {
        create("application") {
            id = "statsig-application"
            implementationClass = "com.statsig.gradle.plugins.StatsigAppPlugin"
        }
        create("library") {
            id = "statsig-library"
            implementationClass = "com.statsig.gradle.plugins.StatsigLibraryPlugin"
        }
        create("publishing") {
            id = "statsig-publishing"
            implementationClass = "com.statsig.gradle.plugins.StatsigPublishingPlugin"
        }
    }
}
