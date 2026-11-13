# UU custom SNI/TCP

This adds the native Xray outbound `uusni` and the **UU SNI (TCP)** profile editor.
It implements the custom protocol captured from UU Android 11.3.5 / 1595, with
no outer TLS. It does not require UU, LSPosed, the original Android `.so`, a
separate local proxy, SProxy credentials, a MainLink, or a heartbeat.

## Fill in your parameters

Use **+ → Type manually [UU SNI]**, then enter a remark and:

| Field | Value |
| --- | --- |
| Address | Captured numeric IPv4 of the node |
| Port | Captured node port, 1–65535 |
| Obfuscation value | Actual `obfs_key` / `sniXorKey`, decimal 0–255 |
| Encrypt target address | Captured `sni_crypt` flag |

The XOR value is required. An omitted value does not silently become zero.
Do not enter the game download domain: every request keeps its own destination.
The editor supports the custom protocol without outer TLS only; unsupported
IPv6 targets and UDP requests fail explicitly. Existing routing can still
select other outbounds according to the user's rules. Mux is disabled for this
outbound. DNS should use TCP/DoH when routed through this TCP-only node.

Sharing, QR import, clipboard import and backup retain the new profile options.
An example with **synthetic parameters**, not the user's real XOR byte:

```text
uusni://203.0.113.10:5556?obfs-key=77&sni-crypt=1#UU%20SNI
```

Native custom-config settings:

```json
{
  "protocol": "uusni",
  "tag": "proxy",
  "settings": {
    "address": "203.0.113.10",
    "port": 5556,
    "obfsKey": 77,
    "sniCrypt": true
  },
  "mux": {"enabled": false, "concurrency": -1}
}
```

## Wire compatibility

Before XOR: `55 55 01 crypt target_port_BE16 field_length_u8 field`.
When encrypted, field is `10 || zero_IV_16 || AES-128-GCM(host) || tag_16`,
with empty AAD. The key is ASCII `node_ip:node_port`, truncated to 16 bytes or
padded with `U`. The captured native fixed IV is preserved for compatibility,
not endorsed for a new cryptographic design. Encrypted host length is capped
at 222 bytes; overflow is rejected before dialing.

The entire header and all subsequent bytes in both directions are XORed with
the configured byte. No success ACK is read. Target port 80 stays 80. The
underlying Xray dialer retains Android socket protection, statistics and
configured outbound chaining; no alternate direct/SProxy transport is tried
on failure. Half-close, cancellation, inactivity and native outbound shutdown
are handled by the core implementation.

## Source and reproducible build

The app's existing `AndroidLibXrayLite` gitlink is unchanged. `build-libv2ray.sh`
applies `patches/xray-core-uu-custom-sni.patch` after the existing native
overlays, onto the pinned Xray revision
`5ca6f4b7d4dc20a881d4330e498892697627ec0c`. This supplies Go/protobuf registration,
wire encoding, the native outbound and tests. It is built into `libgojni.so`
instead of loading the old UU library. The declared app ABI is arm64-v8a.

The build workflow runs native tests, all three existing JVM test variants,
assemblies, lint and native ZIP packaging checks. It also assembles
`:app:assembleTelecomSniTest` and uploads `uusni-test-arm64-v8a`. The test
application ID is `com.v2ray.ang.telecom.uusnitest`, with a debug signer and
self-updates disabled, so it can coexist with the installed Telecom release.
This workflow does not merge a PR or publish a Release.

## Validation scope

Native tests include independent cipher vectors, explicit parameter validation,
bounded hostname lengths, partial writes, no-ACK banner delivery, upload and
download XOR, TCP FIN, cancellation during a blocked handshake, no fallback,
JSON/protobuf/core factory creation and verified HTTPS through a local mock
SNI node. JVM tests cover native settings, URI/backup compatibility, menu
mapping and editor load/edit/save/restore/failure/cancellation.

**Not run:** real UU node interoperability and phone VPN/proxy/root modes;
these require the user's node parameters and device. Also pending device
checks: touch/keyboard/D-pad activation, TalkBack semantics, first-to-last IME
form traversal, activity recreation and screen reopen/persistence. JVM state
tests and an assembled APK are not substitutes for these checks.

The earlier Android local-proxy 0.5.0 was phone-tested using the original
native library. That result must not be presented as device validation of this
new Go outbound. Test this APK with your own parameters before merging or
publishing.
