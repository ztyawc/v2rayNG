package com.v2ray.ang.service

import org.junit.Assert.*
import org.junit.Test

class ServiceLifecycleTest {
    @Test fun coldAndEquivalentStartsAcquireOneGeneration() {
        val state = ServiceLifecycle()
        assertEquals(ServiceLifecycle.Phase.IDLE, state.phase)
        val first = state.start()!!
        assertNull(state.start())
        assertTrue(state.running(first))
        assertNull(state.start())
        assertEquals(first, state.current())
    }
    @Test fun stopDuringPartialSetupRejectsCompletionAndRepeatedStarts() {
        val state = ServiceLifecycle()
        val setup = state.start()!!
        assertTrue(state.stop())
        assertFalse(state.running(setup))
        assertFalse(state.accepts(setup))
        assertNull(state.start())
        state.stopped()
        assertFalse(state.stop())
        assertNull(state.start())
    }
    @Test fun restartSucceedsWithNewGenerationAndStopInvalidatesPendingRestartOrHandover() {
        val state = ServiceLifecycle()
        val start = state.start()!!
        state.running(start)
        val restart = state.restart()!!
        assertFalse(state.accepts(start))
        assertNull(state.restart())
        assertTrue(state.running(restart))
        val handover = state.current()
        state.stop()
        assertFalse(state.accepts(handover))
        assertFalse(state.running(restart))
    }
    @Test fun idleAndStoppingCannotRestart() {
        val state = ServiceLifecycle()
        assertNull(state.restart())
        state.stop()
        assertNull(state.restart())
        state.stopped()
        assertNull(state.restart())
    }
}
