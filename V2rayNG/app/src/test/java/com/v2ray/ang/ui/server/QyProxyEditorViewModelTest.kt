package com.v2ray.ang.ui.server

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.QyProxyOptions
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class QyProxyEditorViewModelTest {
    private fun profile() = ProfileItem.create(EConfigType.QYPROXY).apply {
        remarks = "CN2 fixture"
        server = "198.51.100.7"
        serverPort = "7025"
        username = "test-user"
        password = "test-secret"
        subscriptionId = "stable-group"
        qyProxy = QyProxyOptions(sn = "test-device", gameId = 456, serverId = 123, gameArea = "hk")
    }

    @Test fun loadEditSaveAndReopenPreserveStableIdsAndRole() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(d)
        var stored = profile()
        try {
            val vm = QyProxyEditorViewModel(SavedStateHandle(), { assertEquals("guid-1", it); stored }, { guid, p ->
                assertEquals("guid-1", guid); stored = p; guid
            }, { "guid-1" }, d)
            assertTrue(vm.uiState.value.loading)
            vm.open("guid-1", "another-group", true)
            advanceUntilIdle()
            assertFalse(vm.uiState.value.loading)
            assertTrue(vm.uiState.value.ready)
            assertEquals("123", vm.uiState.value.fields[QyProxyField.ServerId])
            vm.onAction(QyProxyEditorAction.Role("cn2_download"))
            vm.onAction(QyProxyEditorAction.Edit(QyProxyField.GameArea, "sg"))
            vm.onAction(QyProxyEditorAction.Save)
            assertTrue(vm.uiState.value.saving)
            advanceUntilIdle()
            assertEquals("guid-1", vm.uiState.value.savedGuid)
            assertTrue(vm.uiState.value.restartService)
            assertEquals("stable-group", stored.subscriptionId)
            assertEquals("cn2_download", stored.qyProxy!!.role)
            assertEquals("sg", stored.qyProxy!!.gameArea)
            val reopen = QyProxyEditorViewModel(SavedStateHandle(), { stored }, { _, _ -> error("Unexpected save") }, { "" }, d)
            reopen.open("guid-1", "", false)
            advanceUntilIdle()
            assertEquals("cn2_download", reopen.uiState.value.role)
            assertEquals("sg", reopen.uiState.value.fields[QyProxyField.GameArea])
        } finally { Dispatchers.resetMain() }
    }

    @Test fun newEmptyAndInvalidFormsNeverWriteProfiles() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(d)
        try {
            val vm = QyProxyEditorViewModel(SavedStateHandle(), { error("Unexpected load") }, { _, _ -> error("Unexpected save") }, { "" }, d)
            vm.open("", "group-new", false)
            advanceUntilIdle()
            vm.onAction(QyProxyEditorAction.Save)
            assertEquals(R.string.qyproxy_invalid_config, vm.uiState.value.error)
            assertNull(vm.uiState.value.savedGuid)
            assertFalse(vm.uiState.value.saving)
            val invalid = QyProxyEditorViewModel(SavedStateHandle(), { profile() }, { _, _ -> error("Unexpected save") }, { "" }, d)
            invalid.open("guid-1", "", false)
            advanceUntilIdle()
            invalid.onAction(QyProxyEditorAction.Edit(QyProxyField.ServerId, "4294967296"))
            invalid.onAction(QyProxyEditorAction.Save)
            assertEquals(R.string.qyproxy_invalid_config, invalid.uiState.value.error)
            invalid.onAction(QyProxyEditorAction.Edit(QyProxyField.ServerId, "123"))
            assertNull(invalid.uiState.value.error)
            invalid.onAction(QyProxyEditorAction.Role("primary"))
            invalid.onAction(QyProxyEditorAction.Save)
            assertEquals(R.string.qyproxy_invalid_config, invalid.uiState.value.error)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun savedStateRestoresUnsavedNegotiationFieldsByProfileGuid() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(d)
        try {
            val handle = SavedStateHandle()
            val first = QyProxyEditorViewModel(handle, { profile() }, { _, _ -> error("Unexpected save") }, { "" }, d)
            first.open("guid-1", "", false)
            advanceUntilIdle()
            first.onAction(QyProxyEditorAction.Edit(QyProxyField.SN, "edited-device"))
            first.onAction(QyProxyEditorAction.Role("cn2_download"))
            val restored = QyProxyEditorViewModel(handle, { profile() }, { _, _ -> error("Unexpected save") }, { "" }, d)
            restored.open("guid-1", "", false)
            advanceUntilIdle()
            assertEquals("edited-device", restored.uiState.value.fields[QyProxyField.SN])
            assertEquals("cn2_download", restored.uiState.value.role)
            val different = QyProxyEditorViewModel(handle, { profile() }, { _, _ -> error("Unexpected save") }, { "" }, d)
            different.open("guid-2", "", false)
            advanceUntilIdle()
            assertEquals("test-device", different.uiState.value.fields[QyProxyField.SN])
            assertEquals("cn2", different.uiState.value.role)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun newProfileSavesOnceAndIgnoresActionsDuringLoadSaveAndAfterSuccess() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(d)
        var writes = 0
        var stored: ProfileItem? = null
        try {
            val vm = QyProxyEditorViewModel(SavedStateHandle(), { error("Unexpected load") }, { guid, config ->
                assertEquals("", guid)
                writes++
                stored = config
                "guid-new"
            }, { "" }, d)
            vm.open("", "group-new", false)
            vm.open("other-guid", "other-group", true)
            vm.onAction(QyProxyEditorAction.Role("cn2_download"))
            vm.onAction(QyProxyEditorAction.Save)
            assertEquals("cn2", vm.uiState.value.role)
            advanceUntilIdle()
            mapOf(QyProxyField.Remarks to "new CN2", QyProxyField.Address to "198.51.100.7", QyProxyField.Port to "7025",
                QyProxyField.Username to "test-user", QyProxyField.Password to "test-secret", QyProxyField.SN to "new-device",
                QyProxyField.GameId to "456", QyProxyField.ServerId to "123")
                .forEach { (field, value) -> vm.onAction(QyProxyEditorAction.Edit(field, value)) }
            vm.onAction(QyProxyEditorAction.Role("cn2_download"))
            vm.onAction(QyProxyEditorAction.Save)
            vm.onAction(QyProxyEditorAction.Save)
            vm.onAction(QyProxyEditorAction.Edit(QyProxyField.SN, "late-device"))
            assertEquals("new-device", vm.uiState.value.fields[QyProxyField.SN])
            advanceUntilIdle()
            assertEquals("guid-new", vm.uiState.value.savedGuid)
            assertEquals("group-new", stored!!.subscriptionId)
            assertEquals("cn2_download", stored!!.qyProxy!!.role)
            assertFalse(vm.uiState.value.restartService)
            vm.onAction(QyProxyEditorAction.Save)
            vm.onAction(QyProxyEditorAction.Role("cn2"))
            advanceUntilIdle()
            assertEquals(1, writes)
            assertEquals("cn2_download", vm.uiState.value.role)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun missingOrWrongProfileAndEmptySaveIdCannotReportSuccess() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(d)
        val level = LogUtil::class.java.getDeclaredField("cachedMinPriority").apply { isAccessible = true }
        val previous = level.getInt(LogUtil)
        level.setInt(LogUtil, Int.MAX_VALUE)
        try {
            for (loaded in listOf(null, ProfileItem.create(EConfigType.SOCKS))) {
                val vm = QyProxyEditorViewModel(SavedStateHandle(), { loaded }, { _, _ -> error("Unexpected save") }, { "" }, d)
                vm.open("guid-1", "", false)
                advanceUntilIdle()
                assertFalse(vm.uiState.value.ready)
                assertEquals(R.string.toast_failure, vm.uiState.value.error)
                vm.onAction(QyProxyEditorAction.Save)
                assertNull(vm.uiState.value.savedGuid)
            }
            val emptyId = QyProxyEditorViewModel(SavedStateHandle(), { profile() }, { _, _ -> "" }, { "" }, d)
            emptyId.open("guid-1", "", false)
            advanceUntilIdle()
            emptyId.onAction(QyProxyEditorAction.Save)
            advanceUntilIdle()
            assertEquals(R.string.toast_failure, emptyId.uiState.value.error)
            assertFalse(emptyId.uiState.value.saving)
            assertNull(emptyId.uiState.value.savedGuid)
        } finally { level.setInt(LogUtil, previous); Dispatchers.resetMain() }
    }

    @Test fun ownerDestructionCancelsPendingLoad() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(d)
        var called = false
        try {
            val vm = QyProxyEditorViewModel(SavedStateHandle(), { called = true; profile() }, { _, _ -> error("Unexpected save") }, { "" }, d)
            val owner = ViewModelStore()
            owner.put("qyproxy-editor", vm)
            vm.open("guid-1", "", false)
            owner.clear()
            advanceUntilIdle()
            assertFalse(called)
            assertFalse(vm.uiState.value.ready)
            assertNull(vm.uiState.value.savedGuid)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun loadAndSaveFailuresPublishErrorAndAllowSaveRetry() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(d)
        val logLevel = LogUtil::class.java.getDeclaredField("cachedMinPriority").apply { isAccessible = true }
        val oldLevel = logLevel.getInt(LogUtil)
        logLevel.setInt(LogUtil, Int.MAX_VALUE)
        try {
            val failed = QyProxyEditorViewModel(SavedStateHandle(), { error("Injected load failure") }, { _, _ -> error("Unexpected save") }, { "" }, d)
            failed.open("guid-1", "", false)
            advanceUntilIdle()
            assertEquals(R.string.toast_failure, failed.uiState.value.error)
            assertFalse(failed.uiState.value.loading)
            assertFalse(failed.uiState.value.ready)
            var reject = true
            var writes = 0
            val vm = QyProxyEditorViewModel(SavedStateHandle(), { profile() }, { _, _ ->
                writes++; if (reject) error("Injected save failure") else "guid-1"
            }, { "other-guid" }, d)
            vm.open("guid-1", "", true)
            advanceUntilIdle()
            vm.onAction(QyProxyEditorAction.Save)
            advanceUntilIdle()
            assertEquals(R.string.toast_failure, vm.uiState.value.error)
            assertFalse(vm.uiState.value.saving)
            assertNull(vm.uiState.value.savedGuid)
            reject = false
            vm.onAction(QyProxyEditorAction.Save)
            advanceUntilIdle()
            assertEquals("guid-1", vm.uiState.value.savedGuid)
            assertFalse(vm.uiState.value.restartService)
            assertEquals(2, writes)
        } finally { logLevel.setInt(LogUtil, oldLevel); Dispatchers.resetMain() }
    }
}
