package com.v2ray.ang.handler

import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.GitHubRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class UpdateCheckerManagerTest {

    @Test
    fun flavorUsesItsOwnReleaseApiAndExactAssetTemplate() {
        when (BuildConfig.DISTRIBUTION) {
            "Telecom" -> {
                assertEquals(
                    "https://api.github.com/repos/ztyawc/v2rayNG/releases",
                    BuildConfig.UPDATE_API_URL
                )
                assertEquals("v2rayNG_telecom_%s_%s.apk", BuildConfig.UPDATE_APK_TEMPLATE)
            }

            "F-Droid" -> {
                assertEquals(
                    "https://api.github.com/repos/2dust/v2rayNG/releases",
                    BuildConfig.UPDATE_API_URL
                )
                assertEquals("v2rayNG_%s-fdroid_%s.apk", BuildConfig.UPDATE_APK_TEMPLATE)
            }

            else -> {
                assertEquals(
                    "https://api.github.com/repos/2dust/v2rayNG/releases",
                    BuildConfig.UPDATE_API_URL
                )
                assertEquals("v2rayNG_%s_%s.apk", BuildConfig.UPDATE_APK_TEMPLATE)
            }
        }
    }

    @Test
    fun selectsOnlyExactFlavorApkForSupportedAbi() {
        val release = release(
            asset("v2rayNG_other_2.2.7_arm64-v8a.apk", "wrong-flavor"),
            asset("${apkName("2.2.7", "arm64-v8a")}.sig", "signature"),
            asset(apkName("2.2.7", "arm64-v8a"), "expected"),
        )

        assertEquals(
            "https://example.test/expected",
            UpdateCheckerManager.getDownloadUrl(release, listOf("arm64-v8a"))
        )
    }

    @Test
    fun fallsBackToExactUniversalFlavorApk() {
        val release = release(asset(apkName("2.2.7", "universal"), "universal"))

        assertEquals(
            "https://example.test/universal",
            UpdateCheckerManager.getDownloadUrl(release, listOf("x86_64", "x86"))
        )
    }

    @Test
    fun returnsNoUrlForWrongFlavorAndWrongAbiAssets() {
        val release = release(
            asset("v2rayNG_other_2.2.7_arm64-v8a.apk", "wrong-flavor"),
            asset(apkName("2.2.7", "armeabi-v7a"), "wrong-abi"),
        )

        assertNull(UpdateCheckerManager.getDownloadUrl(release, listOf("arm64-v8a")))
    }

    @Test
    fun skipsIncompatibleAndPrereleaseReleases() {
        val result = UpdateCheckerManager.findUpdate(
            releases = listOf(
                release(
                    asset(apkName("9.0.0", "armeabi-v7a"), "wrong-abi"),
                    tagName = "v9.0.0"
                ),
                release(
                    asset(apkName("3.0.0", "arm64-v8a"), "pre-release"),
                    tagName = "v3.0.0",
                    prerelease = true
                ),
                release(
                    asset(apkName("2.2.7", "arm64-v8a"), "stable"),
                    tagName = "v2.2.7"
                ),
            ),
            currentVersion = "2.2.6",
            supportedAbis = listOf("arm64-v8a"),
            includePreRelease = false
        )

        assertTrue(result.hasUpdate)
        assertEquals("2.2.7", result.latestVersion)
        assertEquals("https://example.test/stable", result.downloadUrl)
        assertFalse(result.isPreRelease)
    }

    @Test
    fun returnsNoUpdateWhenThereAreNoReleases() {
        val result = UpdateCheckerManager.findUpdate(
            releases = emptyList(),
            currentVersion = "2.2.6",
            supportedAbis = listOf("arm64-v8a"),
            includePreRelease = false
        )

        assertFalse(result.hasUpdate)
    }

    @Test
    fun comparesNumericReleaseVersions() {
        assertTrue(UpdateCheckerManager.compareVersions("2.2.7", "2.2.6") > 0)
        assertTrue(UpdateCheckerManager.compareVersions("2.2.6.1", "2.2.6") > 0)
        assertEquals(0, UpdateCheckerManager.compareVersions("2.2.7", "2.2.7"))
    }

    private fun release(
        vararg assets: GitHubRelease.Asset,
        tagName: String = "v2.2.7",
        prerelease: Boolean = false
    ) = GitHubRelease(
        tagName = tagName,
        body = "",
        assets = assets.toList(),
        prerelease = prerelease
    )

    private fun asset(name: String, path: String) = GitHubRelease.Asset(
        name = name,
        browserDownloadUrl = "https://example.test/$path"
    )

    private fun apkName(version: String, abi: String): String =
        String.format(Locale.ROOT, BuildConfig.UPDATE_APK_TEMPLATE, version, abi)
}
