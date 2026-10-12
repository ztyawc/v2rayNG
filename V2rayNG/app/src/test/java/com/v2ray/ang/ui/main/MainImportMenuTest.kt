package com.v2ray.ang.ui.main

import com.v2ray.ang.R
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Test

class MainImportMenuTest {

    @Test
    fun importMenuIncludesPrivateSocksAfterRegularSocks() {
        assertEquals(ImportMenuAction.Socks.ordinal + 1, ImportMenuAction.PrivateSocks.ordinal)
        assertEquals(
            R.string.menu_item_import_config_manually_private_socks,
            ImportMenuAction.PrivateSocks.labelRes,
        )
        assertEquals(
            MainAction.ImportManually(EConfigType.PRIVATE_SOCKS.value),
            ImportMenuAction.PrivateSocks.action,
        )
    }

    @Test
    fun regularShareMenuContainsOnlyShareActions() {
        val expected = listOf(
            ServerMenuAction.ShareQRCode,
            ServerMenuAction.ShareClipboard,
            ServerMenuAction.ShareFullContent,
        )
        assertEquals(expected, serverMenuActions(isComplexProfile = false, includeManagementActions = false))
    }

    @Test
    fun regularMoreMenuContainsEveryActionInDisplayOrder() {
        assertEquals(
            ServerMenuAction.entries,
            serverMenuActions(isComplexProfile = false, includeManagementActions = true),
        )
    }

    @Test
    fun complexShareMenuContainsOnlyFullContent() {
        assertEquals(
            listOf(ServerMenuAction.ShareFullContent),
            serverMenuActions(isComplexProfile = true, includeManagementActions = false),
        )
    }

    @Test
    fun complexMoreMenuRetainsManagementActions() {
        val expected = listOf(
            ServerMenuAction.ShareFullContent,
            ServerMenuAction.Edit,
            ServerMenuAction.Delete,
        )
        assertEquals(expected, serverMenuActions(isComplexProfile = true, includeManagementActions = true))
    }
}
