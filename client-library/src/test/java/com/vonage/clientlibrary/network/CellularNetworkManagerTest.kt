package com.vonage.clientlibrary.network

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Tests for the execute() concurrency contract in CellularNetworkManager:
 * - onCompletion is called exactly once even if the callback fires after a timeout
 * - spurious wakeups are handled (loop continues waiting)
 * - timeout calls onCompletion(false)
 *
 * These tests replicate the lock/condition/signaled pattern used in execute() directly,
 * since CellularNetworkManager requires Android system services that can't be unit-tested.
 */
class CellularNetworkManagerTest {

    /**
     * Simulates the execute() logic: the callback fires before the timeout expires.
     * onCompletion should be called exactly once with true.
     */
    @Test
    fun `execute logic calls onCompletion once when callback fires before timeout`() {
        val lock = ReentrantLock()
        val condition = lock.newCondition()
        var signaled = false
        val completions = mutableListOf<Boolean>()
        val timeoutMs = 500L

        // Simulate forceCellular firing the callback immediately on another thread
        Thread {
            Thread.sleep(50)
            lock.withLock {
                if (!signaled) {
                    signaled = true
                    completions.add(true)
                    condition.signal()
                }
            }
        }.start()

        // Simulate the await loop from execute()
        lock.withLock {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!signaled) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0 || !condition.await(remaining, TimeUnit.MILLISECONDS)) break
            }
            if (!signaled) {
                signaled = true
                completions.add(false)
            }
        }

        assertEquals("onCompletion should be called exactly once", 1, completions.size)
        assertTrue("onCompletion should be called with true", completions[0])
    }

    /**
     * Simulates execute() timing out: callback never fires.
     * onCompletion(false) should be called exactly once.
     */
    @Test
    fun `execute logic calls onCompletion(false) exactly once on timeout`() {
        val lock = ReentrantLock()
        val condition = lock.newCondition()
        var signaled = false
        val completions = mutableListOf<Boolean>()
        val timeoutMs = 100L

        // forceCellular fires late — after the timeout
        Thread {
            Thread.sleep(300)
            lock.withLock {
                if (!signaled) {
                    signaled = true
                    completions.add(true) // this should NOT happen
                    condition.signal()
                }
            }
        }.start()

        lock.withLock {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!signaled) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0 || !condition.await(remaining, TimeUnit.MILLISECONDS)) break
            }
            if (!signaled) {
                signaled = true
                completions.add(false)
            }
        }

        // Give the late callback thread time to try to fire
        Thread.sleep(400)

        assertEquals("onCompletion should be called exactly once", 1, completions.size)
        assertFalse("onCompletion should be called with false on timeout", completions[0])
    }

    /**
     * Simulates a race where the callback fires at the same time as the timeout.
     * onCompletion should still only be called once.
     */
    @Test
    fun `execute logic prevents double-completion on race between callback and timeout`() {
        repeat(20) { // run many times to expose races
            val lock = ReentrantLock()
            val condition = lock.newCondition()
            var signaled = false
            var completionCount = 0
            val timeoutMs = 50L

            // Callback fires at exactly the same time as timeout
            Thread {
                Thread.sleep(timeoutMs)
                lock.withLock {
                    if (!signaled) {
                        signaled = true
                        completionCount++
                        condition.signal()
                    }
                }
            }.start()

            lock.withLock {
                val deadline = System.currentTimeMillis() + timeoutMs
                while (!signaled) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0 || !condition.await(remaining, TimeUnit.MILLISECONDS)) break
                }
                if (!signaled) {
                    signaled = true
                    completionCount++
                }
            }

            Thread.sleep(100) // let late callback thread finish
            assertEquals("onCompletion called more than once in iteration $it", 1, completionCount)
        }
    }
}
