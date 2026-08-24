package com.clipsync.android.ui.home

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `without a history source the state is honestly unavailable`() {
        val state = HomeViewModel(historySource = null).state.value
        assertFalse(state.historyAvailable)
        assertTrue(state.entries.isEmpty())
    }

    @Test
    fun `with a source the state is available even while empty`() {
        val flow = MutableStateFlow(emptyList<HistoryEntry>())
        val state = HomeViewModel(historySource = { flow }).state.value
        assertTrue(state.historyAvailable)
        assertTrue(state.entries.isEmpty())
    }

    @Test
    fun `history emissions stream into the state`() {
        val flow = MutableStateFlow(emptyList<HistoryEntry>())
        val model = HomeViewModel(historySource = { flow })

        val first = HistoryEntry(
            id = "evt-1",
            preview = "hello from windows",
            fromThisDevice = false,
            capturedAtMs = 1_755_000_000_000,
        )
        flow.value = listOf(first)
        assertEquals(listOf(first), model.state.value.entries)

        val second = first.copy(id = "evt-2", preview = "reply from phone", fromThisDevice = true)
        flow.value = listOf(second, first)
        assertEquals(listOf(second, first), model.state.value.entries)
        assertTrue(model.state.value.historyAvailable)
    }
}
