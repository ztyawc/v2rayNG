package com.v2ray.ang.service

/** Pure command generations; the shared service manager owns native resources and jobs. */
internal class ServiceLifecycle {
    enum class Phase { IDLE, STARTING, RUNNING, STOPPING, STOPPED }
    @Volatile var phase: Phase = Phase.IDLE
        private set
    private var generation = 0L

    @Synchronized
    fun start(): Long? {
        if (phase != Phase.IDLE) return null
        phase = Phase.STARTING
        return ++generation
    }

    @Synchronized
    fun restart(): Long? {
        if (phase != Phase.RUNNING) return null
        phase = Phase.STARTING
        return ++generation
    }

    @Synchronized
    fun current(): Long = generation

    @Synchronized
    fun accepts(token: Long): Boolean = token == generation && (phase == Phase.STARTING || phase == Phase.RUNNING)

    @Synchronized
    fun running(token: Long): Boolean {
        if (!accepts(token)) return false
        phase = Phase.RUNNING
        return true
    }

    @Synchronized
    fun stop(): Boolean {
        if (phase == Phase.STOPPED) return false
        if (phase != Phase.STOPPING) ++generation
        phase = Phase.STOPPING
        return true
    }

    @Synchronized
    fun stopped() { phase = Phase.STOPPED }
}
