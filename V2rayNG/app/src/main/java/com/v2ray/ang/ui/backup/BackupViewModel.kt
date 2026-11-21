package com.v2ray.ang.ui.backup

import android.app.Application
import android.net.Uri
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.WebDavConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.WebDavManager
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.ui.base.ViewModelEvent
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.ZipUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class BackupViewModel(application: Application) : BaseViewModel(application) {

    private val _webDavConfig = MutableStateFlow(MmkvManager.decodeWebDavConfig())
    val webDavConfig: StateFlow<WebDavConfig?> = _webDavConfig.asStateFlow()

    sealed interface BackupViewModelEvent : ViewModelEvent {
        data class ShareFile(val filePath: String) : BackupViewModelEvent
        object RestoreSuccess : BackupViewModelEvent
    }

    fun saveWebDavConfig(config: WebDavConfig) {
        MmkvManager.encodeWebDavConfig(config)
        _webDavConfig.value = config
        toastSuccess(R.string.toast_success)
    }

    fun cleanupProfileStorage() {
        launchLoading {
            try {
                val removed = withContext(Dispatchers.IO) {
                    MmkvManager.removeOrphanedServerProfiles()
                }
                if (removed == null) {
                    toastError(R.string.toast_profile_storage_cleanup_skipped)
                } else {
                    toastSuccess(getString(R.string.toast_profile_storage_cleanup, removed))
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to clean up profile storage", e)
                toastError(R.string.toast_failure)
            }
        }
    }

    fun shareBackup(cacheDir: File, appName: String) {
        launchLoading {
            val ret = backupConfigurationToCache(cacheDir, appName)
            if (ret.first) {
                _viewModelEvent.send(BackupViewModelEvent.ShareFile(ret.second))
            } else {
                toastError(R.string.toast_failure)
            }
        }
    }

    fun prepareBackupForUri(cacheDir: File, appName: String, targetUri: Uri) {
        launchLoading {
            try {
                val ret = backupConfigurationToCache(cacheDir, appName)
                if (!ret.first) {
                    toastError(R.string.toast_failure)
                    return@launchLoading
                }
                copyBackupToUri(ret.second, targetUri)
                toastSuccess(R.string.toast_success)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Backup export to document failed", error)
                toastError(R.string.toast_failure)
            }
        }
    }

    fun exportLocal(cachePath: String, targetUri: Uri) {
        launchLoading {
            try {
                copyBackupToUri(cachePath, targetUri)
                toastSuccess(R.string.toast_success)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Backup export to document failed", error)
                toastError(R.string.toast_failure)
            }
        }
    }

    private suspend fun copyBackupToUri(cachePath: String, targetUri: Uri) = withContext(Dispatchers.IO) {
        val archive = File(cachePath)
        try {
            checkNotNull(app.contentResolver.openOutputStream(targetUri)) { "Unable to open backup destination" }.use { output ->
                archive.inputStream().use { it.copyTo(output) }
            }
        } finally {
            archive.delete()
        }
    }

    fun restoreFromUri(cacheDir: File, uri: Uri) {
        launchLoading {
            try {
                val success = withContext(Dispatchers.IO) {
                    val archive = File.createTempFile("restore_", ".zip", cacheDir)
                    try {
                        checkNotNull(app.contentResolver.openInputStream(uri)) { "Unable to open restore source" }.use { input ->
                            archive.outputStream().use { input.copyTo(it) }
                        }
                        performRestore(cacheDir, archive)
                    } finally {
                        archive.delete()
                    }
                }
                if (success) {
                    toastSuccess(R.string.toast_success)
                    _viewModelEvent.send(BackupViewModelEvent.RestoreSuccess)
                } else {
                    toastError(R.string.toast_failure)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Backup restore from document failed", error)
                toastError(R.string.toast_failure)
            }
        }
    }

    fun restoreConfiguration(cacheDir: File, zipFile: File) {
        launchLoading {
            val success = performRestore(cacheDir, zipFile)
            if (success) {
                toastSuccess(R.string.toast_success)
                _viewModelEvent.send(BackupViewModelEvent.RestoreSuccess)
            } else {
                toastError(R.string.toast_failure)
            }
        }
    }

    fun backupViaWebDav(cacheDir: File, appName: String) {
        val config = _webDavConfig.value
        if (config == null || (config.baseUrl.isEmpty())) {
            toastError(R.string.title_webdav_config_setting_unknown)
            return
        }

        launchLoading {
            var tempFile: File? = null
            try {
                val ret = backupConfigurationToCache(cacheDir, appName)
                if (!ret.first) {
                    toastError(R.string.toast_failure)
                    return@launchLoading
                }

                tempFile = File(ret.second)
                WebDavManager.init(config)

                val ok = try {
                    WebDavManager.uploadFile(tempFile, AppConfig.WEBDAV_BACKUP_FILE_NAME)
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "WebDAV upload error", e)
                    false
                }

                if (ok) {
                    toastSuccess(R.string.toast_success)
                } else {
                    toastError(R.string.toast_failure)
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "WebDAV backup error", e)
                toastError(R.string.toast_failure)
            } finally {
                tempFile?.delete()
            }
        }
    }

    fun restoreViaWebDav(cacheDir: File) {
        val config = _webDavConfig.value
        if (config == null || (config.baseUrl.isEmpty())) {
            toastError(R.string.title_webdav_config_setting_unknown)
            return
        }

        launchLoading {
            var target: File? = null
            try {
                target = File(cacheDir, "download_${System.currentTimeMillis()}.zip")
                WebDavManager.init(config)
                val ok = WebDavManager.downloadFile(AppConfig.WEBDAV_BACKUP_FILE_NAME, target)
                if (!ok) {
                    toastError(R.string.toast_failure)
                    return@launchLoading
                }

                if (performRestore(cacheDir, target)) {
                    toastSuccess(R.string.toast_success)
                    _viewModelEvent.send(BackupViewModelEvent.RestoreSuccess)
                } else {
                    toastError(R.string.toast_failure)
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "WebDAV download error", e)
                toastError(R.string.toast_failure)
            } finally {
                target?.delete()
            }
        }
    }

    private suspend fun backupConfigurationToCache(cacheDir: File, appName: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val dateFormatted = SimpleDateFormat(
            "yyyy-MM-dd-HH-mm-ss",
            Locale.getDefault()
        ).format(System.currentTimeMillis())
        val archiveDir = File(cacheDir, "configuration_backups")
        // Receivers can read after the share chooser returns. Retain archives for one day,
        // then clean them on the next backup instead of deleting an in-flight attachment.
        val folderName = "${appName}_${dateFormatted}_${System.nanoTime()}"
        val backupDir = File(archiveDir, folderName)
        val outputZip = File(archiveDir, "$folderName.zip")
        try {
            check(archiveDir.isDirectory || archiveDir.mkdirs()) { "Unable to create backup cache" }
            ZipUtil.removeExpiredArchives(archiveDir, System.currentTimeMillis() - 24 * 60 * 60 * 1000L)
            if (MmkvManager.backupConfigurationToDirectory(backupDir.absolutePath) <= 0 ||
                !ZipUtil.zipFromFolder(backupDir.absolutePath, outputZip.absolutePath)) {
                outputZip.delete()
                return@withContext false to ""
            }
            true to outputZip.absolutePath
        } catch (error: Exception) {
            outputZip.delete()
            if (error is kotlinx.coroutines.CancellationException) throw error
            LogUtil.e(AppConfig.TAG, "Backup archive creation failed", error)
            false to ""
        } finally {
            backupDir.deleteRecursively()
        }
    }

    private suspend fun performRestore(cacheDir: File, zipFile: File): Boolean =
        withContext(Dispatchers.IO) {
            val backupDir = File(cacheDir, "restore_${System.nanoTime()}")
            try {
                if (!ZipUtil.unzipToFolder(zipFile, backupDir.absolutePath)) {
                    return@withContext false
                }

                val count = MMKV.restoreAllFromDirectory(backupDir.absolutePath)
                SettingsChangeManager.makeSetupGroupTab()
                SettingsChangeManager.makeRestartService()
                count > 0
            } finally {
                backupDir.deleteRecursively()
            }
        }
}
