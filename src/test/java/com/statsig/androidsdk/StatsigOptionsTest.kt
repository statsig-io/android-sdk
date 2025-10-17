package com.statsig.androidsdk

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StatsigOptionsTest {
    @Test
    fun autoValueUpdateInterval_valueLessThanMinimum_enforcesMinimum() {
        val options = StatsigOptions(autoValueUpdateIntervalMinutes = 0.5)
        assertThat(options.autoValueUpdateIntervalMinutes).isEqualTo(
            AUTO_VALUE_UPDATE_INTERVAL_MINIMUM_VALUE
        )

        options.autoValueUpdateIntervalMinutes = 1.5
        assertThat(options.autoValueUpdateIntervalMinutes).isEqualTo(1.5)
    }

    @Test
    fun setTier_writesLowerCaseEnvironmentVariable() {
        val options = StatsigOptions()
        options.setTier(Tier.PRODUCTION)

        assertThat(options.getEnvironment()?.values).contains(
            Tier.PRODUCTION.toString().lowercase()
        )
    }
}
