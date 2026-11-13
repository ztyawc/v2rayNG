package com.v2ray.ang

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.Utils
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Dedicated device only. Both protocol peers use loopback and synthetic credentials. */
@RunWith(AndroidJUnit4::class)
class CmccUdpRegressionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val guid = "cmcc-udp-regression-fixture"
    private val events = LinkedBlockingQueue<Int>()
    private val strings = listOf(AppConfig.PREF_MODE, AppConfig.PREF_ROUTING_RULESET,
        AppConfig.PREF_SOCKS_USERNAME, AppConfig.PREF_SOCKS_PASSWORD,
        AppConfig.PREF_ROUTING_DOMAIN_STRATEGY)
    private val booleans = listOf(AppConfig.PREF_ROOT_MODE_ENABLE, AppConfig.PREF_DYNAMIC_SOCKS_PORT,
        AppConfig.PREF_MUX_ENABLED, AppConfig.PREF_LOCAL_DNS_ENABLED, AppConfig.PREF_FAKE_DNS_ENABLED,
        AppConfig.PREF_SNIFFING_ENABLED)
    private var previousStrings = emptyMap<String, String?>()
    private var previousBooleans = emptyMap<String, Boolean>()
    private var previousSelection = ""
    private var running = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            events.offer(intent?.getIntExtra("key", 0) ?: 0)
        }
    }

    @Before fun prepare() {
        previousSelection = MmkvManager.getSelectServer().orEmpty()
        previousStrings = strings.associateWith(MmkvManager::decodeSettingsString)
        previousBooleans = booleans.associateWith { MmkvManager.decodeSettingsBool(it, false) }
        booleans.forEach { MmkvManager.encodeSettings(it, it == AppConfig.PREF_DYNAMIC_SOCKS_PORT) }
        MmkvManager.encodeSettings(AppConfig.PREF_MODE, "PROXY")
        MmkvManager.encodeSettings(AppConfig.PREF_SOCKS_USERNAME, "")
        MmkvManager.encodeSettings(AppConfig.PREF_SOCKS_PASSWORD, "")
        MmkvManager.encodeSettings(AppConfig.PREF_ROUTING_DOMAIN_STRATEGY, "AsIs")
        MmkvManager.encodeSettings(AppConfig.PREF_ROUTING_RULESET, JsonUtil.toJson(listOf(
            RulesetItem(id = "cmcc-udp-fixture-rule", ip = listOf("0.0.0.0/0", "::/0"), outboundTag = AppConfig.TAG_PROXY)
        )))
        ContextCompat.registerReceiver(context, receiver, IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY), Utils.receiverFlags())
    }

    @After fun cleanup() {
        try {
            if (running) stop()
        } finally {
            context.unregisterReceiver(receiver)
            MmkvManager.removeServer(guid)
            MmkvManager.setSelectServer(previousSelection)
            previousStrings.forEach { (key, value) -> MmkvManager.encodeSettings(key, value) }
            previousBooleans.forEach { (key, value) -> MmkvManager.encodeSettings(key, value) }
        }
    }

    @Test fun private80TcpAndUdpRoundTrips() = roundTrip(0x80, false)
    @Test fun private82TcpAndUdpRoundTrips() = roundTrip(0x82, false)
    @Test fun private80TcpAndUdpWhileVpnRuns() = roundTrip(0x80, true)
    @Test fun private82TcpAndUdpWhileVpnRuns() = roundTrip(0x82, true)
    @Test fun private80StopClosesActiveAssociation() = stopActive(0x80)
    @Test fun private82StopClosesActiveAssociation() = stopActive(0x82)

    @Test fun stoppingDuringPrivateHandshakeClosesControlSocket() {
        CmccNode(0x82, stallChallenge = true).use { node ->
            start(node, false)
            localSocks().use { control ->
                val relay = associate(control)
                DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)).use { udp ->
                    val packet = udpPacket(false)
                    udp.send(DatagramPacket(packet, packet.size, relay))
                    assertTrue("Private handshake must be in flight", node.handshakeStarted.await(15, TimeUnit.SECONDS))
                    stop()
                    assertTrue("Stopping the core must cancel the blocked private handshake", node.controlClosed.await(10, TimeUnit.SECONDS))
                }
            }
        }
    }

    private fun roundTrip(method: Int, vpn: Boolean) {
        CmccNode(method).use { node ->
            start(node, vpn)
            localSocks().use { control ->
                control.getOutputStream().write(byteArrayOf(5, 1, 0, 1, -58, 51, 100, 9, 0x14, -23))
                assertEquals(0, readReply(control)[1].toInt())
                val payload = byteArrayOf(0, 1, 0x7f, -128, -1, 42)
                control.getOutputStream().write(payload)
                assertArrayEquals("TCP must retain its one-way XOR behavior", payload, readExactly(control.getInputStream(), payload.size))
            }
            localSocks().use { control ->
                val relay = associate(control)
                DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)).use { udp ->
                    udp.soTimeout = 15000
                    for (ipv6 in listOf(false, true)) {
                        val packet = udpPacket(ipv6)
                        udp.send(DatagramPacket(packet, packet.size, relay))
                        val reply = DatagramPacket(ByteArray(65536), 65536)
                        udp.receive(reply)
                        val expected = packet.clone()
                        val headerLength = if (ipv6) 22 else 10
                        expected[headerLength + 2] = -127
                        expected[headerLength + 3] = -128
                        assertArrayEquals("UDP DNS bytes and source address must survive the complete path", expected, reply.data.copyOfRange(reply.offset, reply.offset + reply.length))
                    }
                }
            }
            assertEquals(2, node.udpPackets.get())
            node.failure.get()?.let { throw AssertionError("Mock node rejected wire protocol", it) }
            stop()
        }
    }

    private fun stopActive(method: Int) {
        CmccNode(method).use { node ->
            start(node, false)
            localSocks().use { control ->
                val relay = associate(control)
                DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)).use { udp ->
                    udp.soTimeout = 15000
                    val packet = udpPacket(false)
                    udp.send(DatagramPacket(packet, packet.size, relay))
                    udp.receive(DatagramPacket(ByteArray(2048), 2048))
                    stop()
                    assertTrue("UDP association's TCP socket must close when the daemon stops", node.controlClosed.await(10, TimeUnit.SECONDS))
                }
            }
            node.failure.get()?.let { throw AssertionError("Mock node failed", it) }
        }
    }

    private fun start(node: CmccNode, vpn: Boolean) {
        if (vpn) {
            val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "appops set ${BuildConfig.APPLICATION_ID} ACTIVATE_VPN allow")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            assertNull(VpnService.prepare(context))
            MmkvManager.encodeSettings(AppConfig.PREF_MODE, AppConfig.VPN)
        }
        MmkvManager.encodeServerConfig(guid, ProfileItem.create(EConfigType.PRIVATE_SOCKS).apply {
            subscriptionId = AppConfig.DEFAULT_SUBSCRIPTION_ID
            remarks = "CMCC UDP synthetic fixture"
            server = "127.0.0.1"
            serverPort = node.port.toString()
            username = "cmcc-udp-test"
            password = "synthetic-password"
            cmccProtocol = if (node.method == 0x80) "0x80" else "0x82"
        })
        MmkvManager.setSelectServer(guid)
        events.clear()
        LauncherManager.startService(context)
        running = true
        awaitEvent(AppConfig.MSG_STATE_START_SUCCESS)
    }

    private fun stop() {
        events.clear()
        LauncherManager.stopService(context)
        awaitEvent(AppConfig.MSG_STATE_STOP_SUCCESS)
        running = false
    }

    private fun awaitEvent(expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        while (System.nanoTime() < deadline) {
            val event = events.poll(1, TimeUnit.SECONDS) ?: continue
            assertNotEquals("Daemon setup failed", AppConfig.MSG_STATE_START_FAILURE, event)
            if (event == expected) return
        }
        fail("Timed out waiting for daemon acknowledgement $expected")
    }

    private fun localSocks(): Socket {
        val socket = Socket()
        socket.soTimeout = 15000
        socket.connect(InetSocketAddress("127.0.0.1", SettingsManager.getSocksPort()), 15000)
        socket.getOutputStream().write(byteArrayOf(5, 1, 0))
        assertArrayEquals(byteArrayOf(5, 0), readExactly(socket.getInputStream(), 2))
        return socket
    }

    private fun associate(control: Socket): InetSocketAddress {
        control.getOutputStream().write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0))
        val reply = readReply(control)
        assertEquals(0, reply[1].toInt())
        val port = ((reply[reply.size - 2].toInt() and 255) shl 8) or (reply.last().toInt() and 255)
        assertTrue(port > 0)
        return InetSocketAddress("127.0.0.1", port)
    }

    private fun readReply(socket: Socket): ByteArray {
        val input = socket.getInputStream()
        val header = readExactly(input, 3)
        assertEquals(5, header[0].toInt())
        return header + readAddress(input, false)
    }

    private fun udpPacket(ipv6: Boolean): ByteArray {
        val address = if (ipv6) byteArrayOf(4) + InetAddress.getByName("2001:db8::9").address else byteArrayOf(1, -58, 51, 100, 9)
        val dns = byteArrayOf(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
            7, 101, 120, 97, 109, 112, 108, 101, 4, 116, 101, 115, 116, 0, 0, 1, 0, 1)
        return byteArrayOf(0, 0, 0) + address + byteArrayOf(0x14, -23) + dns
    }

    private fun readExactly(input: InputStream, length: Int, xor: Boolean = false): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(bytes, offset, length - offset)
            if (n < 0) throw EOFException()
            offset += n
        }
        if (xor) bytes.indices.forEach { bytes[it] = (bytes[it].toInt() xor 255).toByte() }
        return bytes
    }

    private fun readAddress(input: InputStream, xor: Boolean): ByteArray {
        val type = readExactly(input, 1, xor)
        val prefix = if (type[0].toInt() == 3) type + readExactly(input, 1, xor) else type
        val size = when (type[0].toInt()) { 1 -> 4; 4 -> 16; 3 -> prefix[1].toInt() and 255; else -> error("Invalid address type") }
        return prefix + readExactly(input, size + 2, xor)
    }

    private inner class CmccNode(val method: Int, private val stallChallenge: Boolean = false) : Closeable {
        private val address = InetAddress.getByName("127.0.0.1")
        private val tcp = ServerSocket(0, 16, address)
        private val udp = DatagramSocket(InetSocketAddress(address, 0))
        private val closed = AtomicBoolean()
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val workers = Executors.newCachedThreadPool { Thread(it, "cmcc-udp-fixture") }
        val port: Int get() = tcp.localPort
        val handshakeStarted = CountDownLatch(1)
        val controlClosed = CountDownLatch(1)
        val udpPackets = AtomicInteger()
        val failure = AtomicReference<Throwable?>()

        init {
            workers.submit {
                try {
                    while (!closed.get()) {
                        val socket = tcp.accept()
                        sockets.add(socket)
                        workers.submit { serve(socket) }
                    }
                } catch (e: Exception) { if (!closed.get()) failure.compareAndSet(null, e) }
            }
            workers.submit { receiveUdp() }
        }

        private fun serve(socket: Socket) {
            try {
                socket.use {
                    socket.soTimeout = 30000
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    check(readExactly(input, 3, true).contentEquals(byteArrayOf(5, 1, method.toByte())))
                    if (stallChallenge) {
                        output.write(byteArrayOf(5, method.toByte()))
                        handshakeStarted.countDown()
                        while (input.read() >= 0) { }
                        return
                    }
                    val challenge = if (method == 0x80) byteArrayOf(0x7a) else byteArrayOf(1, 2, 3, 4)
                    output.write(if (method == 0x80) byteArrayOf(5, 0x7a) else byteArrayOf(5, method.toByte()) + challenge)
                    handshakeStarted.countDown()
                    val auth = readExactly(input, 2, true)
                    check(auth[0].toInt() == 1)
                    val username = readExactly(input, auth[1].toInt() and 255, true).toString(Charsets.UTF_8)
                    check(username == "cmcc-udp-test")
                    check(readExactly(input, 1, true)[0].toInt() == 32)
                    val digest = readExactly(input, 32, true)
                    val secret = if (method == 0x80) "synthetic-password" else MessageDigest.getInstance("MD5")
                        .digest("synthetic-password".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
                    val mac = Mac.getInstance("HmacSHA256")
                    mac.init(SecretKeySpec((username + secret).toByteArray(Charsets.UTF_8), "HmacSHA256"))
                    check(MessageDigest.isEqual(digest, mac.doFinal(challenge)))
                    if (method == 0x82) check(readExactly(input, 21, true).contentEquals(byteArrayOf(
                        0x14, 1, 1, 1, 2, 4, 0, 0, 0, 0, 3, 2, 0x27, 0x10, 4, 1, 1, 5, 2, 0, 4)))
                    output.write(byteArrayOf(1, 0))
                    val command = readExactly(input, 3, true)
                    check(command[0].toInt() == 5 && command[2].toInt() == 0)
                    val target = readAddress(input, true)
                    if (command[1].toInt() == 1) {
                        output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0))
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            for (i in 0 until n) buffer[i] = (buffer[i].toInt() xor 255).toByte()
                            output.write(buffer, 0, n)
                        }
                    } else {
                        check(command[1].toInt() == 3)
                        check(target.contentEquals(byteArrayOf(1, 0, 0, 0, 0, 0, 0)))
                        val relay = if (method == 0x80) byteArrayOf(1, 0, 0, 0, 0) else byteArrayOf(4) + ByteArray(16)
                        val port = udp.localPort
                        output.write(byteArrayOf(5, 0, 0) + relay + byteArrayOf((port shr 8).toByte(), port.toByte()))
                        while (input.read() >= 0) { }
                    }
                }
            } catch (_: EOFException) {
                // A cancelled handshake or terminated association closes its control socket.
            } catch (e: Exception) { if (!closed.get()) failure.compareAndSet(null, e) }
            finally {
                sockets.remove(socket)
                controlClosed.countDown()
            }
        }

        private fun receiveUdp() {
            try {
                while (!closed.get()) {
                    val packet = DatagramPacket(ByteArray(65536), 65536)
                    udp.receive(packet)
                    val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    data.indices.forEach { data[it] = (data[it].toInt() xor 255).toByte() }
                    check(data.size >= 4 && data.take(3) == listOf<Byte>(0, 0, 0))
                    val headerLength = when (data[3].toInt()) { 1 -> 10; 4 -> 22; else -> error("Unexpected UDP address type") }
                    check(data.size >= headerLength + 12)
                    data[headerLength + 2] = -127
                    data[headerLength + 3] = -128
                    udpPackets.incrementAndGet()
                    // A truncated downlink packet must be dropped before this valid reply.
                    val invalid = byteArrayOf(0, 0, 0, 1)
                    udp.send(DatagramPacket(invalid, invalid.size, packet.socketAddress))
                    udp.send(DatagramPacket(data, data.size, packet.socketAddress))
                }
            } catch (e: Exception) { if (!closed.get()) failure.compareAndSet(null, e) }
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                tcp.close()
                udp.close()
                sockets.forEach { it.close() }
                workers.shutdownNow()
                check(workers.awaitTermination(10, TimeUnit.SECONDS))
            }
        }
    }
}
