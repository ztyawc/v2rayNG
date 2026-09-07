package com.v2ray.ang.handler

import android.os.Build
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.GitHubRelease
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

object UpdateCheckerManager {
    suspend fun checkForUpdate(includePreRelease: Boolean = false): CheckUpdateResult = withContext(Dispatchers.IO) {
        // Flavors can opt out when they have no compatible release channel.
        if (!BuildConfig.SELF_UPDATE_ENABLED) {
            return@withContext CheckUpdateResult(hasUpdate = false)
        }

        // Use the releases list instead of /latest. A new fork has no /latest
        // endpoint until its first release, whereas /releases correctly returns
        // an empty array. It also lets us skip releases without a compatible APK.
        val url = BuildConfig.UPDATE_API_URL

        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()

        var response = HttpUtil.getUrlContent(
            UrlContentRequest(
                url = url,
                timeout = 5000
            )
        )
        if (response.isNullOrEmpty()) {
            val httpPort = SettingsManager.getHttpPort()
            response = HttpUtil.getUrlContent(
                UrlContentRequest(
                    url = url,
                    timeout = 5000,
                    httpPort = httpPort,
                    proxyUsername = proxyUsername,
                    proxyPassword = proxyPassword
                )
            )
                ?: throw IllegalStateException("Failed to get response")
        }

        val releases = JsonUtil.fromJsonSafe(response, Array<GitHubRelease>::class.java)
            ?.asList()
            .orEmpty()
        val result = findUpdate(
            releases = releases,
            currentVersion = BuildConfig.VERSION_NAME,
            supportedAbis = Build.SUPPORTED_ABIS.asList(),
            includePreRelease = includePreRelease
        )
        if (result.hasUpdate) {
            LogUtil.i(
                AppConfig.TAG,
                "Found new version: ${result.latestVersion} (current: ${BuildConfig.VERSION_NAME})"
            )
        }
        return@withContext result
    }

    internal fun compareVersions(version1: String, version2: String): Int {
        val v1 = version1.split(".")
        val v2 = version2.split(".")

        for (i in 0 until maxOf(v1.size, v2.size)) {
            val num1 = if (i < v1.size) v1[i].toInt() else 0
            val num2 = if (i < v2.size) v2[i].toInt() else 0
            if (num1 != num2) return num1 - num2
        }
        return 0
    }

    internal fun findUpdate(
        releases: List<GitHubRelease>,
        currentVersion: String,
        supportedAbis: List<String>,
        includePreRelease: Boolean
    ): CheckUpdateResult {
        var selected: UpdateCandidate? = null

        releases.forEach { release ->
            if (!includePreRelease && release.prerelease) {
                return@forEach
            }

            val version = release.tagName.removePrefix("v")
            val isNewer = runCatching { compareVersions(version, currentVersion) > 0 }
                .getOrDefault(false)
            if (!isNewer) {
                return@forEach
            }

            val downloadUrl = getDownloadUrl(release, supportedAbis, version)
            if (downloadUrl == null) {
                return@forEach
            }

            val currentSelection = selected
            if (currentSelection == null || compareVersions(version, currentSelection.version) > 0) {
                selected = UpdateCandidate(release, version, downloadUrl)
            }
        }

        return selected?.let {
            CheckUpdateResult(
                hasUpdate = true,
                latestVersion = it.version,
                releaseNotes = it.release.body,
                downloadUrl = it.downloadUrl,
                isPreRelease = it.release.prerelease
            )
        } ?: CheckUpdateResult(hasUpdate = false)
    }

    internal fun getDownloadUrl(
        release: GitHubRelease,
        supportedAbis: List<String>,
        version: String = release.tagName.removePrefix("v")
    ): String? {
        val assetNames = (supportedAbis + "universal")
            .distinct()
            .map { abi ->
                String.format(Locale.ROOT, BuildConfig.UPDATE_APK_TEMPLATE, version, abi)
            }

        return assetNames.firstNotNullOfOrNull { assetName ->
            release.assets.firstOrNull { it.name.equals(assetName, ignoreCase = true) }
                ?.browserDownloadUrl
        }
    }

    private data class UpdateCandidate(
        val release: GitHubRelease,
        val version: String,
        val downloadUrl: String
    )
}
