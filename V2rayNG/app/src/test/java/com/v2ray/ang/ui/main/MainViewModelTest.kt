package com.v2ray.ang.ui.main

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AngApplication
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServerAffiliationInfo
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.ProfileOrder
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*
import com.v2ray.ang.handler.ProfileStorageException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    @Test fun qrSuccessEmptyFailureAndCancellationPublishOnlyOwnedResults() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val source = mock<MainDataSource>()
        whenever(source.mainServiceEvent).thenReturn(MutableSharedFlow())
        whenever(source.getSelectedSubscriptionId()).thenReturn("")
        whenever(source.getSubscriptions()).thenReturn(emptyList())
        val model = spy(MainViewModel(mock<AngApplication>(), source, SavedStateHandle(), dispatcher, dispatcher))
        doNothing().whenever(model).toastError(any<Int>())
        val bitmap = mock<android.graphics.Bitmap>()
        val level = LogUtil::class.java.getDeclaredField("cachedMinPriority").apply { isAccessible = true }
        val previous = level.getInt(LogUtil)
        level.setInt(LogUtil, Int.MAX_VALUE)
        try {
            advanceUntilIdle()
            assertNull(model.uiState.value.shareQRCodeBitmap)
            whenever(source.share2QRCode("valid")).thenReturn(bitmap)
            model.onAction(MainAction.ShareQRCode("valid"))
            advanceUntilIdle()
            assertSame(bitmap, model.uiState.value.shareQRCodeBitmap)
            model.onAction(MainAction.DismissQRCodeDialog)
            assertNull(model.uiState.value.shareQRCodeBitmap)
            model.onAction(MainAction.ShareQRCode(""))
            advanceUntilIdle()
            assertNull(model.uiState.value.shareQRCodeBitmap)
            verify(model).toastError(any<Int>())
            whenever(source.share2QRCode("failed")).thenThrow(ProfileStorageException("Injected failure"))
            model.onAction(MainAction.ShareQRCode("failed"))
            advanceUntilIdle()
            assertNull(model.uiState.value.shareQRCodeBitmap)
            verify(model, times(2)).toastError(any<Int>())
            whenever(source.share2QRCode("cancelled")).thenAnswer {
                model.onAction(MainAction.DismissQRCodeDialog)
                bitmap
            }
            model.onAction(MainAction.ShareQRCode("cancelled"))
            advanceUntilIdle()
            assertNull(model.uiState.value.shareQRCodeBitmap)
            verify(model, times(2)).toastError(any<Int>())
        } finally { level.setInt(LogUtil, previous); model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun searchRestoresByDomainIdAndRejectsReorderDuringFiltering() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val source = mock<MainDataSource>()
        val events = MutableSharedFlow<MainServiceEvent>(extraBufferCapacity = 8)
        var order = listOf("keep-a", "hidden", "keep-b")
        val profiles = order.associateWith { guid -> ProfileItem.create(EConfigType.SOCKS).apply {
            subscriptionId = "group"; remarks = guid; server = "127.0.0.1"; serverPort = "1080"; description = guid
        } }
        whenever(source.mainServiceEvent).thenReturn(events)
        whenever(source.getSelectedSubscriptionId()).thenReturn("group")
        whenever(source.getSubscriptions()).thenReturn(listOf(SubscriptionCache("group", SubscriptionItem(remarks = "Group"))))
        whenever(source.getServerGuidList("group")).thenAnswer { order }
        profiles.forEach { (id, profile) -> whenever(source.decodeServerConfig(id)).thenReturn(profile) }
        whenever(source.moveServer(eq("group"), any(), any())).thenAnswer {
            order = ProfileOrder.move(order, it.getArgument(1), it.getArgument(2)); order
        }
        val state = SavedStateHandle(mapOf("searchQuery" to "keep", "searchVisible" to true))
        val model = MainViewModel(mock<AngApplication>(), source, state, dispatcher, dispatcher)
        try {
            advanceUntilIdle()
            assertEquals("keep", model.uiState.value.searchQuery)
            assertTrue(model.uiState.value.searchVisible)
            assertEquals(listOf("keep-a", "keep-b"), model.serverGroupState("group").value.servers.map { it.guid })
            model.onAction(MainAction.MoveServer("group", "keep-b", "keep-a"))
            advanceUntilIdle()
            verify(source, never()).moveServer(any(), any(), any())
            model.onAction(MainAction.ShowSearch(false))
            assertTrue(model.uiState.value.isFiltering)
            model.onAction(MainAction.MoveServer("group", "keep-b", "keep-a"))
            advanceUntilIdle()
            assertFalse(model.uiState.value.isFiltering)
            assertEquals(order, model.serverGroupState("group").value.servers.map { it.guid })
            model.onAction(MainAction.MoveServer("group", "keep-b", "keep-a"))
            advanceUntilIdle()
            assertEquals(listOf("keep-b", "keep-a", "hidden"), order)
            model.onAction(MainAction.Search("["))
            advanceUntilIdle()
            assertEquals(order, model.serverGroupState("group").value.servers.map { it.guid })
            assertEquals("[", state.get<String>("searchQuery"))
            model.onAction(MainAction.Search("no-match"))
            advanceUntilIdle()
            assertTrue(model.serverGroupState("group").value.servers.isEmpty())
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun delayResultReadsOnlyTheChangedAffiliation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val source = mock<MainDataSource>()
        val events = MutableSharedFlow<MainServiceEvent>(extraBufferCapacity = 8)
        whenever(source.mainServiceEvent).thenReturn(events)
        whenever(source.getSelectedSubscriptionId()).thenReturn("group")
        whenever(source.getSubscriptions()).thenReturn(listOf(SubscriptionCache("group", SubscriptionItem(remarks = "Group"))))
        whenever(source.getServerGuidList("group")).thenReturn(listOf("a", "b"))
        listOf("a", "b").forEach { guid -> whenever(source.decodeServerConfig(guid)).thenReturn(
            ProfileItem.create(EConfigType.SOCKS).apply { remarks = guid; description = guid; subscriptionId = "group" }) }
        val model = MainViewModel(mock<AngApplication>(), source, SavedStateHandle(), dispatcher, dispatcher)
        try {
            advanceUntilIdle()
            clearInvocations(source)
            whenever(source.decodeAffiliationInfo("b")).thenReturn(ServerAffiliationInfo(testDelayMillis = 42))
            events.emit(MainServiceEvent.MeasureConfigSuccess("b"))
            advanceUntilIdle()
            assertEquals(42L, model.serverGroupState("group").value.servers.single { it.guid == "b" }.testDelayMillis)
            verify(source, never()).getServerGuidList(any())
            verify(source, never()).decodeServerConfig(any())
            events.emit(MainServiceEvent.MeasureConfigSuccess(""))
            advanceUntilIdle()
            verify(source, times(1)).decodeAffiliationInfo(any())
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun emptyGroupAndFailedReorderPreservePublishedState() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val source = mock<MainDataSource>()
        whenever(source.mainServiceEvent).thenReturn(MutableSharedFlow())
        whenever(source.getSelectedSubscriptionId()).thenReturn("group")
        whenever(source.getSubscriptions()).thenReturn(listOf(SubscriptionCache("group", SubscriptionItem(remarks = "Group"))))
        whenever(source.getServerGuidList("group")).thenReturn(emptyList())
        val model = spy(MainViewModel(mock<AngApplication>(), source, SavedStateHandle(), dispatcher, dispatcher))
        doNothing().whenever(model).toastError(any<Int>())
        val level = LogUtil::class.java.getDeclaredField("cachedMinPriority").apply { isAccessible = true }
        val previous = level.getInt(LogUtil)
        level.setInt(LogUtil, Int.MAX_VALUE)
        try {
            advanceUntilIdle()
            assertTrue(model.serverGroupState("group").value.servers.isEmpty())
            whenever(source.moveServer("group", "a", "b")).thenThrow(ProfileStorageException("Injected write failure"))
            model.onAction(MainAction.MoveServer("group", "a", "b"))
            advanceUntilIdle()
            assertTrue(model.serverGroupState("group").value.servers.isEmpty())
            verify(model).toastError(any<Int>())
        } finally { level.setInt(LogUtil, previous); model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
}
