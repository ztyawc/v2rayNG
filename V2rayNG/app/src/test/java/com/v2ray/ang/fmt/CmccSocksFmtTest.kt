package com.v2ray.ang.fmt

import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CmccSocksFmtTest {
    @Test fun roundTripPreservesEncodedColonPlusPercentAndFragment() {
        val original = profile().apply {
            username = "user:name+%"
            password = "pass:word+%"
            remarks = "Node%2FShanghai + 東京"
        }
        val restored = CmccSocksFmt.parse(AppConfig.CMCC_SOCKS + CmccSocksFmt.toUri(original))!!
        assertEquals(original.username, restored.username)
        assertEquals(original.password, restored.password)
        assertEquals(original.remarks, restored.remarks)
    }

    @Test fun readsPreviousReleasedWholeCredentialEncoding() {
        val restored = CmccSocksFmt.parse("cmcc://test-user%3Atest%3Apassword@private-socks.example.test:10800?auth=0x80#Node%252FShanghai")!!
        assertEquals("test-user", restored.username)
        assertEquals("test:password", restored.password)
        assertEquals("Node%2FShanghai", restored.remarks)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedAuthCannotSilentlyShareAsAuth80() { CmccSocksFmt.toUri(profile("0x81")) }

    private fun profile(auth: String = "0x80") = ProfileItem.create(EConfigType.PRIVATE_SOCKS).apply {
        remarks = "Private SOCKS test node"
        server = "private-socks.example.test"
        serverPort = "10800"
        username = "test-user"
        password = "test:password"
        cmccProtocol = auth
    }

    @Test
    fun cmccUriRoundTripPreservesBothPrivateAuthenticationModes() {
        listOf("0x80", "0x82").forEach { auth ->
            val original = profile(auth)

            val exported = AppConfig.CMCC_SOCKS + CmccSocksFmt.toUri(original)
            assertTrue(exported.startsWith(AppConfig.CMCC_SOCKS))
            assertTrue(exported.contains("auth=$auth"))

            val restored = CmccSocksFmt.parse(exported)
            assertNotNull(restored)
            assertEquals(EConfigType.PRIVATE_SOCKS, restored?.configType)
            assertEquals(original.remarks, restored?.remarks)
            assertEquals(original.server, restored?.server)
            assertEquals(original.serverPort, restored?.serverPort)
            assertEquals(original.username, restored?.username)
            assertEquals(original.password, restored?.password)
            assertEquals(auth, restored?.cmccProtocol)
        }
    }

    @Test
    fun cmccUriRejectsMissingOrUnsupportedAuthenticationModes() {
        val invalidUris = listOf(
            "cmcc://test-user:test-password@private-socks.example.test:10800#NoAuth",
            "cmcc://test-user:test-password@private-socks.example.test:10800?auth=0x81#Unsupported",
            "cmcc://test-user:test-password@private-socks.example.test:10800?auth=80#NotCanonical",
            "cmcc://test-user:test-password@private-socks.example.test:10800?auth=#Blank",
        )

        invalidUris.forEach { uri ->
            assertNull("URI must reject private SOCKS auth mode: $uri", CmccSocksFmt.parse(uri))
        }
    }

    @Test
    fun cmccUriRejectsMissingCredentials() {
        assertNull(
            CmccSocksFmt.parse(
                "cmcc://:test-password@private-socks.example.test:10800?auth=0x80#MissingUser"
            )
        )
        assertNull(
            CmccSocksFmt.parse(
                "cmcc://test-user:@private-socks.example.test:10800?auth=0x80#MissingPassword"
            )
        )
    }

    @Test
    fun cmccProtocolSurvivesProfileSerializationAndParticipatesInEquality() {
        val original = profile("0x82")

        val serialized = JsonUtil.toJson(original)
        assertEquals(
            "0x82",
            JsonParser.parseString(serialized).asJsonObject.get("cmccProtocol").asString
        )

        val restored = JsonUtil.fromJson(serialized, ProfileItem::class.java)
        assertNotNull(restored)
        assertEquals(original, restored)
        assertEquals("0x82", restored?.cmccProtocol)

        val differentAuthMode = original.copy(cmccProtocol = "0x80")
        assertNotEquals(original, differentAuthMode)
    }
}
