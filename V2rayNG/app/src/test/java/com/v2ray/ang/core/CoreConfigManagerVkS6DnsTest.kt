package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.dto.V2rayConfig
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock

class CoreConfigManagerVkS6DnsTest {
    private fun context() = CoreConfigContext(mock(Context::class.java), "stable-guid", routingDomainRules = listOf(
        CoreConfigContext.RoutingDomainRule(listOf("full:fixture.example"), AppConfig.TAG_PROXY),
        CoreConfigContext.RoutingDomainRule(listOf("full:blocked.example"), AppConfig.TAG_BLOCKED)
    ))

    private fun config(protocol: String) = V2rayConfig(
        log = V2rayConfig.LogBean(), inbounds = arrayListOf(),
        outbounds = arrayListOf(V2rayConfig.OutboundBean(protocol = protocol)),
        routing = V2rayConfig.RoutingBean(domainStrategy = "AsIs", rules = arrayListOf()),
        dns = V2rayConfig.DnsBean(servers = arrayListOf(AppConfig.DNS_PROXY))
    )

    @Test fun s6VpnInterceptsDnsForBothTunnelBackendsWithLocalToggleOff() {
        for (hev in listOf(false, true)) for (protocol in listOf("vks6", "VKS6")) {
            val config = config(protocol)
            CoreConfigManager.configureLocalDns(context(), config, localDnsEnabled = false,
                fakeDnsEnabled = true, vpnMode = true, usingHevTun = hev)
            val rule = config.routing.rules.single()
            assertEquals(listOf(if (hev) "socks" else "tun"), rule.inboundTag)
            assertEquals("53", rule.port)
            assertEquals("dns-out", rule.outboundTag)
            assertEquals(1, config.outbounds.count { it.protocol == "dns" && it.tag == "dns-out" })
            // An inactive saved FakeDNS toggle must not create a synthetic resolver.
            assertEquals(listOf(AppConfig.DNS_PROXY), config.dns!!.servers)
            assertNull(config.fakedns)
        }
    }

    @Test fun s6ChainOrGroupMemberAlsoGetsVpnDnsInterception() {
        val config = config("freedom")
        config.outbounds.add(V2rayConfig.OutboundBean(tag = "group-member-guid", protocol = "vks6"))
        CoreConfigManager.configureLocalDns(context(), config, localDnsEnabled = false,
            fakeDnsEnabled = false, vpnMode = true, usingHevTun = true)
        assertEquals("dns-out", config.routing.rules.first().outboundTag)
        assertEquals("group-member-guid", config.outbounds[1].tag)
    }

    @Test fun otherProtocolsAndNonVpnModesKeepDisabledLocalDnsBehavior() {
        for (protocol in listOf("vmess", "vless", "uusni", "vks6")) for (vpn in listOf(false, true)) {
            if (protocol == "vks6" && vpn) continue
            val config = config(protocol)
            CoreConfigManager.configureLocalDns(context(), config, localDnsEnabled = false,
                fakeDnsEnabled = true, vpnMode = vpn, usingHevTun = true)
            assertTrue(config.routing.rules.isEmpty())
            assertEquals(1, config.outbounds.size)
            assertEquals(listOf(AppConfig.DNS_PROXY), config.dns!!.servers)
        }
    }

    @Test fun explicitLocalDnsRetainsFakeDnsAndNonVpnBehavior() {
        for (protocol in listOf("vks6", "vless")) for (vpn in listOf(false, true)) {
            val config = config(protocol)
            CoreConfigManager.configureLocalDns(context(), config, localDnsEnabled = true,
                fakeDnsEnabled = true, vpnMode = vpn, usingHevTun = true)
            val fake = config.dns!!.servers!!.first() as V2rayConfig.DnsBean.ServersBean
            assertEquals("fakedns", fake.address)
            assertEquals(listOf(AppConfig.GEOSITE_CN, "full:fixture.example"), fake.domains)
            assertEquals(if (vpn) 1 else 0, config.routing.rules.size)
            assertEquals(1, config.outbounds.count { it.protocol == "dns" })
        }
    }

    @Test fun existingDnsOutboundIsPreserved() {
        val config = config("vks6")
        val dns = V2rayConfig.OutboundBean(tag = "dns-out", protocol = "dns")
        config.outbounds.add(dns)
        CoreConfigManager.configureLocalDns(context(), config, localDnsEnabled = false,
            fakeDnsEnabled = false, vpnMode = true, usingHevTun = true)
        assertEquals(1, config.outbounds.count { it.tag == "dns-out" })
        assertSame(dns, config.outbounds.last())
    }
}
