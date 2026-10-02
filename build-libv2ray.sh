#!/usr/bin/env bash
set -euo pipefail

readonly REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly XRAY_DIR="$REPO_DIR/AndroidLibXrayLite/.build/xray-core"
readonly CMCC_UDP_PATCH="$REPO_DIR/patches/xray-core-cmcc-udp.patch"
readonly QYPROXY_PATCH="$REPO_DIR/patches/xray-core-qyproxy-cn2.patch"
# The submodule owns the pinned source checkout, patches, assets and native tests.
OUTPUT_AAR="$REPO_DIR/AndroidLibXrayLite/.build/libv2ray-base-arm64.aar" \
    bash "$REPO_DIR/AndroidLibXrayLite/scripts/build-libv2ray-arm64.sh"
# This app-owned overlay extends the pinned native source with CMCC UDP. Keeping
# it here makes a clean CI checkout reproduce the feature without a dirty gitlink.
git -C "$XRAY_DIR" apply --check "$CMCC_UDP_PATCH"
git -C "$XRAY_DIR" apply "$CMCC_UDP_PATCH"
git -C "$XRAY_DIR" apply --check "$QYPROXY_PATCH"
git -C "$XRAY_DIR" apply "$QYPROXY_PATCH"
(
    cd "$XRAY_DIR"
    GOWORK=off go test -race ./proxy/socks ./proxy/qyproxy/...
    GOWORK=off go test ./proxy/http ./proxy/socks ./proxy/qyproxy/...
    GOWORK=off go test ./infra/conf -run '^(TestSocks|TestQyProxy)'
)
# The requested phone build targets ARM64. Reuse the tested staging module.
(
    cd "$REPO_DIR/AndroidLibXrayLite/.build/androidlib"
    GOWORK=off go test .
    GOWORK=off gomobile bind -target=android/arm64 -androidapi 24 -trimpath \
        -ldflags='-s -w -buildid= -checklinkname=0' \
        -o "$REPO_DIR/AndroidLibXrayLite/.build/libv2ray-phone.aar" ./
)
readonly APP_LIBS="$REPO_DIR/V2rayNG/app/libs"
mkdir -p "$APP_LIBS"
aar_staging_file=$(mktemp "$APP_LIBS/.libv2ray.aar.XXXXXX")
trap 'rm -f "$aar_staging_file"' EXIT
install -m 0644 "$REPO_DIR/AndroidLibXrayLite/.build/libv2ray-phone.aar" "$aar_staging_file"
mv -f "$aar_staging_file" "$APP_LIBS/libv2ray.aar"
