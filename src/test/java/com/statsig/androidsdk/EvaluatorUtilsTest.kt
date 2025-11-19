package com.statsig.androidsdk

import com.google.common.truth.Truth.assertThat
import com.statsig.androidsdk.evaluator.EvaluatorUtils
import org.junit.Test

class EvaluatorUtilsTest {

    @Test
    fun versionCompare_similarVersions() {
        val old = "25.45"
        val new = "25.46"

        assertThat(EvaluatorUtils.versionCompare(old, new)).isEqualTo(-1)
        assertThat(EvaluatorUtils.versionCompare(new, old)).isEqualTo(1)
        assertThat(EvaluatorUtils.versionCompare(old, old)).isEqualTo(0)
    }

    @Test
    fun versionCompare_mismatchedLengths() {
        val old = "1.0.1"
        val new = "1.1"

        assertThat(EvaluatorUtils.versionCompare(old, new)).isEqualTo(-1)
        assertThat(EvaluatorUtils.versionCompare(new, old)).isEqualTo(1)
    }
}
