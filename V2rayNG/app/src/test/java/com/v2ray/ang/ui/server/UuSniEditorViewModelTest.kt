package com.v2ray.ang.ui.server

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.UuSniOptions
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

// ExperimentalCoroutinesApi is limited to deterministic dispatcher tests.
// Remove when setMain/resetMain and advanceUntilIdle are stable in the pinned dependency.
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UuSniEditorViewModelTest {
    private fun profile() = ProfileItem.create(EConfigType.UUSNI).apply {
        remarks = "SNI fixture"; server = "203.0.113.10"; serverPort = "5556"
        subscriptionId = "stable-group"; uuSni = UuSniOptions(77, true)
    }

    @Test fun loadEditSaveReopenAndRestoreUseStableProfileAndGroupIds() = runTest {
        val d = StandardTestDispatcher(testScheduler); Dispatchers.setMain(d)
        var stored = profile()
        val handle = SavedStateHandle()
        try {
            val vm = UuSniEditorViewModel(handle, { assertEquals("guid-1", it); stored }, { guid, p -> stored = p; guid }, { "guid-1" }, d)
            assertTrue(vm.uiState.value.loading)
            vm.open("guid-1", "different-group", true); advanceUntilIdle()
            assertTrue(vm.uiState.value.ready)
            assertEquals("77", vm.uiState.value.fields[UuSniField.ObfsKey])
            vm.onAction(UuSniEditorAction.Edit(UuSniField.ObfsKey, "0"))
            vm.onAction(UuSniEditorAction.Crypt(false))
            val restored = UuSniEditorViewModel(handle, { stored }, { guid, p -> stored = p; guid }, { "guid-1" }, d)
            restored.open("guid-1", "", true); advanceUntilIdle()
            assertFalse(restored.uiState.value.sniCrypt)
            assertEquals("0", restored.uiState.value.fields[UuSniField.ObfsKey])
            restored.onAction(UuSniEditorAction.Save)
            assertTrue(restored.uiState.value.saving); advanceUntilIdle()
            assertEquals("guid-1", restored.uiState.value.savedGuid)
            assertTrue(restored.uiState.value.restartService)
            assertEquals("stable-group", stored.subscriptionId)
            assertEquals(UuSniOptions(0, false), stored.uuSni)
            val reopen = UuSniEditorViewModel(SavedStateHandle(), { stored }, { _, _ -> error("unexpected write") }, { "" }, d)
            reopen.open("guid-1", "", false); advanceUntilIdle()
            assertEquals("0", reopen.uiState.value.fields[UuSniField.ObfsKey])
            assertFalse(reopen.uiState.value.sniCrypt)
            val another = UuSniEditorViewModel(handle, { profile() }, { _, _ -> error("unexpected write") }, { "" }, d)
            another.open("different-guid", "", false); advanceUntilIdle()
            assertEquals("77", another.uiState.value.fields[UuSniField.ObfsKey])
        } finally { Dispatchers.resetMain() }
    }

    @Test fun newEmptyInvalidAndMissingKeyFormsNeverWrite() = runTest {
        val d = StandardTestDispatcher(testScheduler); Dispatchers.setMain(d)
        try {
            val vm = UuSniEditorViewModel(SavedStateHandle(), { error("unexpected load") }, { _, _ -> error("unexpected write") }, { "" }, d)
            vm.open("", "group-new", false); advanceUntilIdle()
            assertEquals("", vm.uiState.value.fields[UuSniField.ObfsKey])
            vm.onAction(UuSniEditorAction.Save)
            assertEquals(R.string.uusni_invalid_config, vm.uiState.value.error)
            val invalid = UuSniEditorViewModel(SavedStateHandle(), { profile() }, { _, _ -> error("unexpected write") }, { "" }, d)
            invalid.open("guid", "", false); advanceUntilIdle()
            for (key in listOf("", "bad", "-1", "256")) {
                invalid.onAction(UuSniEditorAction.Edit(UuSniField.ObfsKey, key))
                assertNull(invalid.uiState.value.error)
                invalid.onAction(UuSniEditorAction.Save)
                assertEquals(R.string.uusni_invalid_config, invalid.uiState.value.error)
                assertFalse(invalid.uiState.value.saving); assertNull(invalid.uiState.value.savedGuid)
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun newProfileSavesOnceAndIgnoresActionsWhileBusyAndAfterSave() = runTest {
        val d = StandardTestDispatcher(testScheduler); Dispatchers.setMain(d)
        var writes = 0; var stored: ProfileItem? = null
        try {
            val vm = UuSniEditorViewModel(SavedStateHandle(), { error("unexpected load") }, { guid, p ->
                assertEquals("", guid); writes++; stored = p; "guid-new"
            }, { "other" }, d)
            vm.open("", "group-new", true); vm.open("other", "wrong-group", true)
            vm.onAction(UuSniEditorAction.Crypt(false)); vm.onAction(UuSniEditorAction.Save)
            assertTrue(vm.uiState.value.sniCrypt); advanceUntilIdle()
            mapOf(UuSniField.Remarks to "new SNI", UuSniField.Address to "203.0.113.10", UuSniField.Port to "5556", UuSniField.ObfsKey to "77")
                .forEach { (f, v) -> vm.onAction(UuSniEditorAction.Edit(f, v)) }
            vm.onAction(UuSniEditorAction.Save); vm.onAction(UuSniEditorAction.Save)
            vm.onAction(UuSniEditorAction.Edit(UuSniField.ObfsKey, "255")); advanceUntilIdle()
            assertEquals("guid-new", vm.uiState.value.savedGuid); assertEquals("group-new", stored!!.subscriptionId)
            assertFalse(vm.uiState.value.restartService); assertEquals(77, stored!!.uuSni!!.obfsKey)
            vm.onAction(UuSniEditorAction.Save); advanceUntilIdle(); assertEquals(1, writes)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun failureStatesDoNotClaimSuccessAndSavingCanBeRetried() = runTest {
        val d = StandardTestDispatcher(testScheduler); Dispatchers.setMain(d)
        val level = LogUtil::class.java.getDeclaredField("cachedMinPriority").apply { isAccessible = true }
        val previous = level.getInt(LogUtil); level.setInt(LogUtil, Int.MAX_VALUE)
        try {
            for (loader in listOf<(String) -> ProfileItem?>( { null }, { ProfileItem.create(EConfigType.SOCKS) }, { error("load failure") })) {
                val vm = UuSniEditorViewModel(SavedStateHandle(), loader, { _, _ -> error("unexpected write") }, { "" }, d)
                vm.open("guid", "", false); advanceUntilIdle()
                assertFalse(vm.uiState.value.ready); assertFalse(vm.uiState.value.loading)
                assertEquals(R.string.toast_failure, vm.uiState.value.error)
                vm.onAction(UuSniEditorAction.Save); assertNull(vm.uiState.value.savedGuid)
            }
            var attempts = 0
            val vm = UuSniEditorViewModel(SavedStateHandle(), { profile() }, { _, _ ->
                attempts++; when (attempts) { 1 -> error("save failure"); 2 -> ""; else -> "guid" }
            }, { "other-guid" }, d)
            vm.open("guid", "", true); advanceUntilIdle()
            repeat(2) {
                vm.onAction(UuSniEditorAction.Save); advanceUntilIdle()
                assertEquals(R.string.toast_failure, vm.uiState.value.error)
                assertFalse(vm.uiState.value.saving); assertNull(vm.uiState.value.savedGuid)
            }
            vm.onAction(UuSniEditorAction.Save); advanceUntilIdle()
            assertEquals("guid", vm.uiState.value.savedGuid); assertFalse(vm.uiState.value.restartService)
        } finally { level.setInt(LogUtil, previous); Dispatchers.resetMain() }
    }

    @Test fun ownerDestructionCancelsPendingLoad() = runTest {
        val d = StandardTestDispatcher(testScheduler); Dispatchers.setMain(d)
        var loaded = false
        try {
            val vm = UuSniEditorViewModel(SavedStateHandle(), { loaded = true; profile() }, { _, _ -> error("unexpected write") }, { "" }, d)
            val owner = ViewModelStore(); owner.put("uusni", vm)
            vm.open("guid", "", false); owner.clear(); advanceUntilIdle()
            assertFalse(loaded); assertFalse(vm.uiState.value.ready)
        } finally { Dispatchers.resetMain() }
    }
}
