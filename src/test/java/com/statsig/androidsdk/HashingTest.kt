package com.statsig.androidsdk

import android.util.Base64
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class HashingTest {

    @Before
    fun setUp() {
        // Clear any existing mocks on Hashing object (from TestUtil.mockStatsigUtil)
        unmockkObject(Hashing)
        // Mock Android Base64 class to work in unit test environment
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any(), any()) } answers {
            // Simple mock implementation that returns a deterministic hash-like string
            val input = firstArg<ByteArray>()
            "mocked_hash_${input.contentHashCode()}"
        }
    }

    @After
    fun tearDown() {
        unmockkStatic(Base64::class)
    }

    @Test
    fun testMemoizationCacheHit() {
        val input = "test_gate"
        val algorithm = HashAlgorithm.SHA256

        val result1 = Hashing.getHashedString(input, algorithm)
        val result2 = Hashing.getHashedString(input, algorithm)

        assertEquals("Results should be identical", result1, result2)
    }

    @Test
    fun testDifferentAlgorithmsCacheSeparately() {
        val input = "test_gate"

        val sha256Result = Hashing.getHashedString(input, HashAlgorithm.SHA256)
        val djb2Result = Hashing.getHashedString(input, HashAlgorithm.DJB2)
        val noneResult = Hashing.getHashedString(input, HashAlgorithm.NONE)

        assertNotEquals("SHA256 and DJB2 should produce different results", sha256Result, djb2Result)
        assertNotEquals("SHA256 and NONE should produce different results", sha256Result, noneResult)
        assertEquals("NONE should return input unchanged", input, noneResult)
    }

    @Test
    fun testCacheBoundedSize() {
        val baseInput = "cache_test_${System.currentTimeMillis()}"

        // Fill SHA256 cache beyond the limit (MAX_CACHE_SIZE is now 1000)
        for (i in 0..1100) {
            Hashing.getHashedString("${baseInput}_sha256_$i", HashAlgorithm.SHA256)
        }

        // Fill DJB2 cache beyond the limit (MAX_CACHE_SIZE is now 1000)
        for (i in 0..1100) {
            Hashing.getHashedString("${baseInput}_djb2_$i", HashAlgorithm.DJB2)
        }

        // Verify that cache clearing actually happened by checking that previously cached items are no longer cached
        // We'll test this by ensuring that the same computation still works (memoization functionality intact)
        // but we can't directly verify cache clearing without exposing cache internals
        // Verify that memoization still works after cache clearing for both algorithms
        val sha256TestInput = "${baseInput}_sha256_verification"
        val sha256Result1 = Hashing.getHashedString(sha256TestInput, HashAlgorithm.SHA256)
        val sha256Result2 = Hashing.getHashedString(sha256TestInput, HashAlgorithm.SHA256)
        assertEquals("SHA256 memoization should still work after cache clearing", sha256Result1, sha256Result2)

        val djb2TestInput = "${baseInput}_djb2_verification"
        val djb2Result1 = Hashing.getHashedString(djb2TestInput, HashAlgorithm.DJB2)
        val djb2Result2 = Hashing.getHashedString(djb2TestInput, HashAlgorithm.DJB2)
        assertEquals("DJB2 memoization should still work after cache clearing", djb2Result1, djb2Result2)
        // Additional verification: ensure that different algorithms still produce different results
        assertNotEquals(
            "SHA256 and DJB2 should still produce different results after cache operations",
            sha256Result1,
            djb2Result1,
        )
    }

    @Test
    fun testThreadSafety() {
        val input = "thread_safety_test"
        val numThreads = 10
        val numCallsPerThread = 100
        val latch = CountDownLatch(numThreads)
        val results = mutableListOf<String>()

        val executor = Executors.newFixedThreadPool(numThreads)

        repeat(numThreads) {
            executor.submit {
                try {
                    repeat(numCallsPerThread) {
                        val result = Hashing.getHashedString(input, HashAlgorithm.SHA256)
                        synchronized(results) {
                            results.add(result)
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        val firstResult = results.first()
        assertTrue("All results should be identical", results.all { it == firstResult })
        assertEquals("Should have expected number of results", numThreads * numCallsPerThread, results.size)
    }

    @Test
    fun testNullAlgorithmHandling() {
        val input = "test_null_algorithm"

        val result1 = Hashing.getHashedString(input, null)
        val result2 = Hashing.getHashedString(input, HashAlgorithm.SHA256)

        assertEquals("Null algorithm should default to SHA256", result1, result2)
    }

    @Test
    fun testDifferentInputsSameAlgorithm() {
        val algorithm = HashAlgorithm.SHA256

        val result1 = Hashing.getHashedString("input1", algorithm)
        val result2 = Hashing.getHashedString("input2", algorithm)
        val result3 = Hashing.getHashedString("input1", algorithm)

        assertNotEquals("Different inputs should produce different hashes", result1, result2)
        assertEquals("Same input should produce same cached result", result1, result3)
    }

    @Test
    fun testEmptyStringInput() {
        val emptyResult1 = Hashing.getHashedString("", HashAlgorithm.SHA256)
        val emptyResult2 = Hashing.getHashedString("", HashAlgorithm.SHA256)

        assertEquals("Empty string should be cached correctly", emptyResult1, emptyResult2)
        assertNotEquals("Empty string should not equal non-empty string", emptyResult1, Hashing.getHashedString("test", HashAlgorithm.SHA256))
    }

    @Test
    fun testBoundedMemoBasicFunctionality() {
        val memo = BoundedMemo<String, String>()

        val result1 = memo.computeIfAbsent("key1") { "value1" }
        val result2 = memo.computeIfAbsent("key1") { "should_not_be_called" }

        assertEquals("Should return cached value", "value1", result1)
        assertEquals("Should return same cached value", "value1", result2)
    }

    @Test
    fun testBoundedMemoClearingBehavior() {
        val memo = BoundedMemo<String, String>()

        // Fill cache beyond the limit (1000 entries)
        for (i in 0..1100) {
            memo.computeIfAbsent("key_$i") { "value_$i" }
        }

        // Verify that early entries were cleared (cache was cleared when limit was reached)
        val earlyResult = memo.computeIfAbsent("key_5") { "new_value_for_key_5" }
        assertEquals("Early entry should be recomputed after cache clearing", "new_value_for_key_5", earlyResult)

        // Verify that a later entry (close to limit) was also cleared
        val laterResult = memo.computeIfAbsent("key_998") { "new_value_for_key_998" }
        assertEquals("Later entry should be recomputed after cache clearing", "new_value_for_key_998", laterResult)

        // Verify that cache clearing works and memoization still functions
        val result1 = memo.computeIfAbsent("test_key") { "test_value" }
        val result2 = memo.computeIfAbsent("test_key") { "should_not_be_called" }
        assertEquals("Should return cached value after clearing", "test_value", result1)
        assertEquals("Should return same cached value", result1, result2)
    }

    @Test
    fun testBoundedMemoThreadSafety() {
        val memo = BoundedMemo<String, String>()
        val input = "test_key"
        val numThreads = 10
        val numCallsPerThread = 50
        val latch = CountDownLatch(numThreads)
        val results = mutableListOf<String>()

        val executor = Executors.newFixedThreadPool(numThreads)

        repeat(numThreads) {
            executor.submit {
                try {
                    repeat(numCallsPerThread) {
                        val result = memo.computeIfAbsent(input) { "computed_value" }
                        synchronized(results) {
                            results.add(result)
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        val firstResult = results.first()
        assertTrue("All results should be identical", results.all { it == firstResult })
        assertEquals("Should have expected number of results", numThreads * numCallsPerThread, results.size)
        assertEquals("All results should be the computed value", "computed_value", firstResult)
    }
}
