# VK S6 download TCP

This fork adds the native Xray outbound `vks6`, a **VK S6 (TCP)** editor and
`vks6://` sharing, QR/clipboard/file import and profile backup support. It runs
inside `libgojni.so`; it does not launch Python, mihomo or a second local proxy.

## Enter a node

Open **+ → Type manually [VK S6]** and enter:

| Field | Meaning |
| --- | --- |
| Address | Numeric IPv4 of the VK entry node |
| Port | First TCP port from the current session's download entry |
| Last node port | Inclusive range limit; leave empty for a fixed port |
| Session username/password | Original strings from the current VK session |

Use the session credentials passed to VK's `startVpn`, rather than the account
login password. Both fields must contain 1–255 UTF-8 bytes. Spaces and special
characters are preserved. Each new connection independently chooses a port in
the range; failure returns an error without trying a direct connection.

Synthetic sharing example (not a working account):

```text
vks6://vk-test:0123456789abcdef@203.0.113.10:8050?port-end=8150#VK%20S6
```

Encode the username and password separately when constructing links. An omitted
`port-end` selects the single stated port. Unknown options, duplicate options,
malformed UTF-8 credentials, IPv6 nodes and invalid ranges are rejected.

## TCP, routing and DNS

Only the observed IPv4/TCP download variant is implemented. UDP/UDCP, IPv6,
other S6 commands, account login and automatic credential renewal are outside
its supported scope. Replace the credentials when the VK session expires.

The existing Xray dispatcher resolves target domains using `ForceIPv4`, which
retains configured DNS and hosts and excludes FakeDNS replies. The S6 outbound
also rejects the default Fake-IP range and addresses in a configured FakeDNS
pool. Resolution failures never fall back to direct traffic. Keep remote DNS
on DoH or TCP; the app's default Cloudflare DoH already uses TCP. A custom UDP
DNS resolver cannot be carried through this TCP-only node.

Normal v2rayNG rules select which connections use S6; VK's per-game rules are
not imported. VPN, proxy-only and root modes retain their existing service,
protected socket and tunnel owners. Mux and outer TLS/other transports are
disabled for this protocol. HTTPS websites still use end-to-end application TLS.

Equivalent native custom configuration:

```json
{
  "protocol": "vks6",
  "tag": "proxy",
  "targetStrategy": "ForceIPv4",
  "settings": {
    "address": "203.0.113.10",
    "port": 8050,
    "portEnd": 8150,
    "username": "vk-test",
    "password": "0123456789abcdef"
  },
  "mux": { "enabled": false, "concurrency": -1 }
}
```

## Wire format and source

The request is `06 | Lu | encoded_user | Lp | encoded_password | 09 01 |
IPv4[4] | port_BE16`. Each credential independently starts the repeated key
`3c 26 44 63 4c 2b 43 40` at index zero. Read exactly two plain ACK bytes,
`06 00`, before applying bytewise XOR `5a` in both directions. ACK and target
payload can arrive together. Fixed XOR is protocol obfuscation, not encryption.

Protocol reference: [VK S6 porting manual](https://github.com/ztyawc/uu-sni-capture/blob/1ffa3731e468831b81e3f70e5f4145c1241f534f/docs/VK-S6-PORTING.md).
The reference mihomo implementation was inspected at
`184e2757fb5200958aa5d4f6f71d150c499fe6a6`. This port uses Xray's own dialer,
buffer relay, policy timers and flow cancellation, with no mihomo dependency.

`build-libv2ray.sh` applies `patches/xray-core-vk-s6.patch` after the existing
overlays to pinned Xray source `5ca6f4b7d4dc20a881d4330e498892697627ec0c`.
The `AndroidLibXrayLite` gitlink stays at
`a211a9bf4436b12736a754f26dee579d0126a90b`. Generated AAR/HEV binaries are not
committed; GitHub builds them from source for the app's sole ABI, arm64-v8a.

## Validation and device limits

The native build runs S6 race tests for the independent wire vector, credential
byte limits, short writes, ACK fragmentation/coalescing, bidirectional transfer,
TCP FIN, node rejection, cancellation and joined shutdown. Configuration tests
exercise JSON/protobuf/factory registration, port defaults, unsupported settings,
transport chaining and verified HTTPS through a local mock S6 node with DNS hosts.
JVM tests cover native settings, URI/backup compatibility, menu mapping, editor
validation, load/save failures, restoration by GUID and owner cancellation.
GitHub's build also runs all JVM variants, debug assemblies, lint and native APK
packaging checks. The release workflow verifies package/version and the existing
Telecom signing certificate before publishing the ARM64 APK and SHA-256 file.

**Not run:** this v2rayNG port's real VK-node interoperability; no current VK
session credentials were supplied for this task. Historical mihomo tests are
not runtime validation of this Xray port.

**Not run:** phone VPN/proxy-only/root start, transfer, stop, reconnect and
network-switch scenarios; no Android device is connected. Also pending on a
device: touch/keyboard/D-pad navigation and activation, TalkBack semantics and
spoken labels, first-to-last field traversal/save with IME visible, activity
recreation, and close/reopen persistence through the owning repository. An
assembled APK and JVM state tests do not verify those device behaviors.
