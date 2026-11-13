package com.v2ray.ang.ui.server

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.VkS6Options
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

// Deterministic dispatcher tests require ExperimentalCoroutinesApi; remove the
// opt-in when setMain/resetMain and scheduler controls become stable.
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VkS6EditorViewModelTest {
    private fun profile() = ProfileItem.create(EConfigType.VKS6).apply {
        remarks = "S6 fixture"; server = "203.0.113.10"; serverPort = "8050"
        username = "vk-test"; password = "synthetic-secret"
        vkS6 = VkS6Options(8150); subscriptionId = "stable-group"
    }

    @Test fun loadEditRestoreSaveAndReopenPreserveStableIdsAndCredentialBytes() = runTest {
        val d = StandardTestDispatcher(testScheduler); Dispatchers.setMain(d)
        var stored = profile()
        val handle = SavedStateHandle()
        try {
            val vm = VkS6EditorViewModel(handle, { assertEquals("guid-1", it); stored }, { guid, p -> stored = p; guid }, { "guid-1" }, d)
            assertTrue(vm.uiState.value.loading)
            vm.open("guid-1", "wrong-group", true); advanceUntilIdle()
            assertTrue(vm.uiState.value.ready)
            assertEquals("8150", vm.uiState.value.fields[VkS6Field.PortEnd])
            vm.onAction(VkS6EditorAction.Edit(VkS6Field.PortEnd, ""))
            vm.onAction(VkS6EditorAction.Edit(VkS6Field.Password, " 密码 +% "))
            val restored = VkS6EditorViewModel(handle, { stored }, { guid, p -> stored = p; guid }, { "guid-1" }, d)
            restored.open("guid-1", "", true); advanceUntilIdle()
            assertEquals(" 密码 +% ", restored.uiState.value.fields[VkS6Field.Password])
            restored.onAction(VkS6EditorAction.Save)
            assertTrue(restored.uiState.value.saving); advanceUntilIdle()
            assertEquals("guid-1", restored.uiState.value.savedGuid)
            assertTrue(restored.uiState.value.restartService)
            assertEquals("stable-group", stored.subscriptionId)
            assertEquals(" 密码 +% ", stored.password)
            assertNull(stored.vkS6!!.portEnd)
            val reopen = VkS6EditorViewModel(SavedStateHandle(), { stored }, { _, _ -> error("unexpected write") }, { "" }, d)
            reopen.open("guid-1", "", false); advanceUntilIdle()
            assertEquals(stored.password, reopen.uiState.value.fields[VkS6Field.Password])
            val other = VkS6EditorViewModel(handle, { profile() }, { _, _ -> error("unexpected write") }, { "" }, d)
            other.open("guid-other", "", false); advanceUntilIdle()
            assertEquals("synthetic-secret", other.uiState.value.fields[VkS6Field.Password])
        } finally { Dispatchers.resetMain() }
    }

    @Test fun emptyAndInvalidFormsNeverWriteAndEditsClearErrors() = runTest {
        val d = StandardTestDispatcher(testScheduler); Dispatchers.setMain(d)
        try {
            val empty = VkS6EditorViewModel(SavedStateHandle(), { error("unexpected load") }, { _, _ -> error("unexpected write") }, { "" }, d)
            empty.open("", "new-group", false); advanceUntilIdle()
            empty.onAction(VkS6EditorAction.Save)
            assertEquals(R.string.vks6_invalid_config, empty.uiState.value.error)
            for ((field, value) in listOf(VkS6Field.Remarks to "", VkS6Field.Address to "node.test",
                VkS6Field.Port to "0", VkS6Field.PortEnd to "bad", VkS6Field.PortEnd to "8049",
                VkS6Field.PortEnd to "65536", VkS6Field.Username to "", VkS6Field.Password to "",
                VkS6Field.Password to "中".repeat(86))) {
                val vm = VkS6EditorViewModel(SavedStateHandle(), { profile() }, { _, _ -> error("unexpected write") }, { "" }, d)
                vm.open("guid", "", false); advanceUntilIdle()
                vm.onAction(VkS6EditorAction.Edit(field, value))
                assertNull(vm.uiState.value.error)
                vm.onAction(VkS6EditorAction.Save)
                assertEquals(R.string.vks6_invalid_config, vm.uiState.value.error)
                assertFalse(vm.uiState.value.saving); assertNull(vm.uiState.value.savedGuid)
                vm.onAction(VkS6EditorAction.Edit(field, value))
                assertNull(vm.uiState.value.error)
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun newProfileSavesOnceAndIgnoresActionsDuringLoadingSavingAndAfterSuccess() = runTest {
        val d = StandardTestDispatcher(testScheduler); Dispatchers.setMain(d)
        var writes = 0; var stored: ProfileItem? = null
        try {
            val vm = VkS6EditorViewModel(SavedStateHandle(), { error("unexpected load") }, { guid, p ->
                assertEquals("", guid); writes++; stored = p; "guid-new"
            }, { "other" }, d)
            vm.open("", "new-group", true); vm.open("wrong-guid", "wrong-group", true)
            vm.onAction(VkS6EditorAction.Edit(VkS6Field.Username, "ignored")); vm.onAction(VkS6EditorAction.Save)
            assertEquals("", vm.uiState.value.fields[VkS6Field.Username]); advanceUntilIdle()
            mapOf(VkS6Field.Remarks to "new S6", VkS6Field.Address to "203.0.113.10", VkS6Field.Port to "8050",
                VkS6Field.PortEnd to "8150", VkS6Field.Username to "vk-test", VkS6Field.Password to "synthetic-secret")
                .forEach { (field, value) -> vm.onAction(VkS6EditorAction.Edit(field, value)) }
            vm.onAction(VkS6EditorAction.Save); vm.onAction(VkS6EditorAction.Save)
            vm.onAction(VkS6EditorAction.Edit(VkS6Field.PortEnd, "9000")); advanceUntilIdle()
            assertEquals("guid-new", vm.uiState.value.savedGuid)
            assertEquals("new-group", stored!!.subscriptionId)
            assertEquals(VkS6Options(8150), stored.vkS6)
            assertFalse(vm.uiState.value.restartService)
            vm.onAction(VkS6EditorAction.Save); advanceUntilIdle(); assertEquals(1, writes)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun loadAndSaveFailuresNeverReportSuccessAndSaveCanBeRetried() = runTest {
        val d = StandardTestDispatcher(testScheduler); Dispatchers.setMain(d)
        val level = LogUtil::class.java.getDeclaredField("cachedMinPriority").apply { isAccessible = true }
        val previous = level.getInt(LogUtil); level.setInt(LogUtil, Int.MAX_VALUE)
        try {
            for (loader in listOf<(String) -> ProfileItem?>({ null }, { ProfileItem.create(EConfigType.SOCKS) }, { error("load failure") })) {
                val vm = VkS6EditorViewModel(SavedStateHandle(), loader, { _, _ -> error("unexpected write") }, { "" }, d)
                vm.open("guid", "", false); advanceUntilIdle()
                assertFalse(vm.uiState.value.ready); assertFalse(vm.uiState.value.loading)
                assertEquals(R.string.toast_failure, vm.uiState.value.error)
                vm.onAction(VkS6EditorAction.Save); assertNull(vm.uiState.value.savedGuid)
            }
            var attempts = 0
            val vm = VkS6EditorViewModel(SavedStateHandle(), { profile() }, { _, _ ->
                attempts++; when (attempts) { 1 -> error("save failure"); 2 -> ""; else -> "guid" }
            }, { "other-guid" }, d)
            vm.open("guid", "", true); advanceUntilIdle()
            repeat(2) {
                vm.onAction(VkS6EditorAction.Save); advanceUntilIdle()
                assertEquals(R.string.toast_failure, vm.uiState.value.error)
                assertFalse(vm.uiState.value.saving); assertNull(vm.uiState.value.savedGuid)
            }
            vm.onAction(VkS6EditorAction.Save); advanceUntilIdle()
            assertEquals("guid", vm.uiState.value.savedGuid); assertFalse(vm.uiState.value.restartService)
        } finally { level.setInt(LogUtil, previous); Dispatchers.resetMain() }
    }

    @Test fun ownerDestructionCancelsPendingLoadAndSave() = runTest {
        val d = StandardTestDispatcher(testScheduler); Dispatchers.setMain(d)
        var loaded = false; var saved = false
        try {
            val vm = VkS6EditorViewModel(SavedStateHandle(), { loaded = true; profile() }, { _, _ -> error("unexpected write") }, { "" }, d)
            val owner = ViewModelStore(); owner.put("vks6-guid", vm)
            vm.open("guid", "", false); owner.clear(); advanceUntilIdle()
            assertFalse(loaded); assertFalse(vm.uiState.value.ready)
            val saving = VkS6EditorViewModel(SavedStateHandle(), { profile() }, { _, _ -> saved = true; "guid" }, { "" }, d)
            val savingOwner = ViewModelStore(); savingOwner.put("vks6-guid", saving)
            saving.open("guid", "", false); advanceUntilIdle()
            saving.onAction(VkS6EditorAction.Save); savingOwner.clear(); advanceUntilIdle()
            assertFalse(saved); assertNull(saving.uiState.value.savedGuid)
        } finally { Dispatchers.resetMain() }
    }
}
