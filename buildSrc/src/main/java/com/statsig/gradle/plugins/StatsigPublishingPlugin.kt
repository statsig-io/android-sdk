package com.statsig.gradle.plugins

import com.statsig.gradle.extensions.StatsigPublishingConfigExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.get

/**
 * Wrapper plugin that helps setup publishing for any module that applies it.
 */
class StatsigPublishingPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.apply<MavenPublishPlugin>() // maven-publish

        // This creates the custom extension used in the build.gradle file to help generate the POM file for publishing.
        target.extensions.create<StatsigPublishingConfigExtension>("publishConfiguration")

        target.afterEvaluate {
            configure<PublishingExtension> { // publish { }
                publications {
                    create<MavenPublication>("release") {
                        from(components["release"])

                        groupId = "com.statsig"
                        // artifactName and version are omitted purposely.
                        // ArtifactName is taken from the name of the module
                        // Version is set from the gradle.properties in the module (not the root project)

                        // Get the extension that configures the POM file
                        val projectPom = project.extensions.findByType<StatsigPublishingConfigExtension>() ?: return@create
                        pom(projectPom.pomActionProperty.get())
                    }
                }

                repositories {
                    maven {
                        name = "StatsigBuild"
                        url = this@afterEvaluate.file("${project.buildDir}/repo").toURI()
                    }
                }
            }
        }
    }
}
