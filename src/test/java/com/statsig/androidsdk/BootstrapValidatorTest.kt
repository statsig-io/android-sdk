package com.statsig.androidsdk

import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

class BootstrapValidatorTest {
    @Test
    fun testIsValid() {
        val user = StatsigUser("test_user")
        val initializeValues = mutableMapOf<String, Any>()
        val customIDs = mutableMapOf("id_1" to "value_1", "id_2" to "value_2")
        // Only userID
        initializeValues["evaluated_keys"] = mapOf("userID" to "test_user")
        assertTrue(BootstrapValidator.isValid(initializeValues, user))
        // With CustomIDs
        user.customIDs = HashMap(customIDs)
        initializeValues["evaluated_keys"] = mapOf("userID" to "test_user", "customIDs" to (HashMap(customIDs)))
        assertTrue(BootstrapValidator.isValid(mapOf(), user))
        // With StableID
        var newCustomIDs = HashMap(customIDs)
        newCustomIDs["stableID"] = "a"
        initializeValues["evaluated_keys"] = mapOf("userID" to "test_user", "customIDs" to newCustomIDs)
        assertTrue(BootstrapValidator.isValid(initializeValues, user))

        // mismatched user id
        val user2 = StatsigUser("test_user_2")
        initializeValues["evaluated_keys"] = mapOf("userID" to "test_user")
        assertFalse(BootstrapValidator.isValid(initializeValues, user2))
        // mismatched customID
        newCustomIDs = HashMap(customIDs)
        newCustomIDs["id_3"] = "value_3"
        initializeValues["evaluated_keys"] = mapOf("userID" to "test_user", "customIDs" to newCustomIDs)
        assertFalse(BootstrapValidator.isValid(initializeValues, user2))
    }
}
