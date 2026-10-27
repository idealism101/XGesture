package com.slideindex.app.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PassthroughEchoGuardTest {

    private val guard = PassthroughEchoGuard()

    @Test
    fun notArmedNeverSwallows() {
        assertFalse(guard.isEcho(100f, 200f, nowMs = 0L))
    }

    @Test
    fun echoAtSamePointDuringTransactionIsSwallowed() {
        guard.arm(1050f, 1594f, nowMs = 1_000L)
        assertTrue(guard.isEcho(1050f, 1594f, nowMs = 1_400L))
    }

    @Test
    fun echoWithinSlopIsSwallowed() {
        guard.arm(1050f, 1594f, nowMs = 1_000L)
        assertTrue(guard.isEcho(1070f, 1610f, nowMs = 1_100L))
    }

    @Test
    fun farTouchIsNotSwallowed() {
        guard.arm(1050f, 1594f, nowMs = 1_000L)
        assertFalse(guard.isEcho(1050f, 1100f, nowMs = 1_100L))
    }

    /**
     * 这条是本轮修复的回归网：旧实现用「注入后固定 400ms」挡住回声，
     * 用户紧接着的第二次点击（同一点）会被误判成回声吞掉。
     * 现在窗口随事务结束（disarm）关闭，事务结束后同一点必须放行。
     */
    @Test
    fun samePointTapAfterTransactionEndsIsNotEcho() {
        guard.arm(1050f, 1594f, nowMs = 1_000L)
        assertTrue(guard.isEcho(1050f, 1594f, nowMs = 1_200L))
        guard.disarm()
        assertFalse(guard.isEcho(1050f, 1594f, nowMs = 1_201L))
    }

    @Test
    fun safetyExpiryDisarmsGuard() {
        guard.arm(1050f, 1594f, nowMs = 1_000L)
        val expiry = 1_000L + PassthroughEchoGuard.MAX_ARMED_MS
        assertTrue(guard.isEcho(1050f, 1594f, nowMs = expiry - 1))
        assertFalse(guard.isEcho(1050f, 1594f, nowMs = expiry))
    }

    @Test
    fun resetDisarmsGuard() {
        guard.arm(1050f, 1594f, nowMs = 1_000L)
        guard.disarm()
        assertFalse(guard.isEcho(1050f, 1594f, nowMs = 1_100L))
    }
}
