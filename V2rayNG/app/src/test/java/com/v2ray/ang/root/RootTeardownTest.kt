package com.v2ray.ang.root

import android.content.Context
import android.os.Process
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

class RootTeardownTest {
    @get:Rule val directory = TemporaryFolder()
    private val logLevel = LogUtil::class.java.getDeclaredField("cachedMinPriority").apply { isAccessible = true }
    private var previous = 0
    @Before fun suppressAndroidLogBoundary() { previous = logLevel.getInt(LogUtil); logLevel.setInt(LogUtil, Int.MAX_VALUE) }
    @After fun restoreLogBoundary() { logLevel.setInt(LogUtil, previous) }

    @Test fun teardownRejectsRemainingRoutesAndAcceptsAbsentRules() {
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(directory.root)
        val script = mockStatic(Process::class.java).use { process ->
            process.`when`<Int> { Process.myPid() }.thenReturn(0)
            RootProxyManager::class.java.getDeclaredMethod("buildTeardown", Context::class.java)
                .apply { isAccessible = true }.invoke(RootProxyManager, context) as String
        }
        val bin = directory.newFolder("commands")
        val remaining = File(directory.root, "remaining")
        val commands = """#!/bin/sh
            case "${'$'}*" in
              *"-S"*) [ -f '${remaining.absolutePath}' ] && printf '%s\n' '-N ${AppConfig.ROOT_IPTABLES_CHAIN}'; exit 0 ;;
              *"rule show"*) exit 0 ;;
              *) exit 1 ;;
            esac
        """.trimIndent()
        listOf("iptables", "ip6tables", "ip").forEach { name ->
            File(bin, name).apply { writeText(commands); setExecutable(true) }
        }
        val run = { RootShell.execute(listOf("/bin/sh", "-c", "PATH='${bin.absolutePath}':\$PATH\n$script"), 2000) }
        assertTrue(run().success)
        remaining.writeText("present")
        assertFalse(run().success)
        remaining.delete()
        assertTrue(run().success)
    }
}
