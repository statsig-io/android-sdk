package com.statsig.gradle.extensions

import org.gradle.api.Action
import org.gradle.api.provider.Property
import org.gradle.api.publish.maven.MavenPom

/**
 * Custom extension that allows publishing modules to set the POM file in their own build.gradle file
 */
abstract class StatsigPublishingConfigExtension {

    abstract val pomActionProperty: Property<Action<MavenPom>>

    fun setupPom(action: Action<MavenPom>) {
        pomActionProperty.set(action)
    }
}
