package com.v2ray.ang.root

import com.v2ray.ang.util.LogUtil
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class RootShellTest {
    @get:Rule val directory = TemporaryFolder()
    private val logLevel = LogUtil::class.java.getDeclaredField("cachedMinPriority").apply { isAccessible = true }
    private var previous = 0
    @Before fun suppressAndroidLogBoundary() { previous = logLevel.getInt(LogUtil); logLevel.setInt(LogUtil, Int.MAX_VALUE) }
    @After fun restoreLogBoundary() { logLevel.setInt(LogUtil, previous) }

    @Test fun timeoutBoundsProcessEvenWhenStdoutStaysOpen() {
        val start = System.nanoTime()
        val result = RootShell.execute(listOf("sh", "-c", "exec sleep 10"), 100)
        assertFalse(result.success)
        assertTrue("Timeout should bound the stream read", (System.nanoTime() - start) / 1_000_000 < 1500)
    }

    @Test fun drainsLargeOutputWithoutDeadlockAndCapsRetainedOutput() {
        val result = RootShell.execute(listOf("sh", "-c", "head -c 200000 /dev/zero"), 3000)
        assertTrue(result.success)
        assertEquals(64 * 1024, result.output.length)
    }

    @Test fun cancellationStopsOwnedProcess() {
        val cancelled = AtomicBoolean()
        val worker = Thread {
            try { RootShell.execute(listOf("sh", "-c", "exec sleep 10"), 30_000) }
            catch (_: InterruptedException) { cancelled.set(true) }
        }
        worker.start()
        Thread.sleep(100)
        worker.interrupt()
        worker.join(1500)
        assertFalse(worker.isAlive)
        assertTrue(cancelled.get())
    }

    @Test fun lateGrantCannotExecuteANewerScriptAfterItsOriginalLeaseEnds() {
        val folder = directory.newFolder("path with ' quotes")
        val script = File(folder, "setup.sh")
        val lease = File(folder, "old.lease").apply { writeText("") }
        val marker = File(folder, "executed")
        val command = RootShell.scriptCommand(script, lease).last()
        lease.delete()
        script.writeText("touch \"${marker.absolutePath}\"")
        val rejected = RootShell.execute(listOf("sh", "-c", command), 1000)
        assertFalse(rejected.success)
        assertFalse(marker.exists())
        lease.writeText("")
        assertTrue(RootShell.execute(listOf("sh", "-c", command), 1000).success)
        assertTrue(marker.exists())
    }
}
