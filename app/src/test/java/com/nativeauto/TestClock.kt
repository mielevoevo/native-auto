package com.nativeauto

import com.nativeauto.core.MonotonicClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler

/** A [MonotonicClock] driven by the coroutine test scheduler's virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class TestClock(private val scheduler: TestCoroutineScheduler) : MonotonicClock {
    override fun nowNanos(): Long = scheduler.currentTime * 1_000_000L
}
