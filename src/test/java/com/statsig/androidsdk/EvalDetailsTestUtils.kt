package com.statsig.androidsdk

import org.junit.Assert.assertEquals

internal fun assertEvalDetails(details: EvalDetails, source: EvalSource, reason: EvalReason?) {
    assertEquals(source, details.source)
    assertEquals(reason, details.reason)
}
