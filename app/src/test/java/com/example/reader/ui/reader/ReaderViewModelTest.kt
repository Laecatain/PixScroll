package com.example.reader.ui.reader

import com.example.reader.data.model.MediaItem
import com.example.reader.data.repository.FakeMediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ReaderViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val testItems = listOf(
        MediaItem(null, "photo1.jpg", "image/jpeg", 1024, 1000, "/DCIM/Camera/photo1.jpg"),
        MediaItem(null, "photo2.jpg", "image/jpeg", 2048, 2000, "/DCIM/Camera/photo2.jpg")
    )

    // ── Basic load tests ──

    @Test
    fun `emits media items on success`() {
        val repo = FakeMediaRepository()
        repo.mediaItems = mapOf(1L to testItems)
        val vm = ReaderViewModel(repo, parentId = 1L)

        assertFalse(vm.state.value.isLoading)
        assertEquals(2, vm.state.value.mediaItems.size)
        assertEquals("Camera", vm.state.value.folderName)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `switches between reader modes`() {
        val repo = FakeMediaRepository()
        repo.mediaItems = mapOf(1L to testItems)
        val vm = ReaderViewModel(repo, parentId = 1L)

        assertEquals(ReaderMode.ContinuousScroll, vm.state.value.currentMode)
        vm.switchMode(ReaderMode.Pager)
        assertEquals(ReaderMode.Pager, vm.state.value.currentMode)
        vm.switchMode(ReaderMode.ContinuousScroll)
        assertEquals(ReaderMode.ContinuousScroll, vm.state.value.currentMode)
    }

    @Test
    fun `empty folder shows empty state`() {
        val repo = FakeMediaRepository()
        repo.mediaItems = mapOf(1L to emptyList())
        val vm = ReaderViewModel(repo, parentId = 1L)

        assertFalse(vm.state.value.isLoading)
        assertTrue(vm.state.value.mediaItems.isEmpty())
        assertNull(vm.state.value.error)
    }

    @Test
    fun `load failure shows error state`() {
        val repo = FakeMediaRepository()
        repo.mediaError = RuntimeException("读取失败")
        val vm = ReaderViewModel(repo, parentId = 1L)

        assertFalse(vm.state.value.isLoading)
        assertEquals("读取失败", vm.state.value.error)
        assertTrue(vm.state.value.mediaItems.isEmpty())
    }

    @Test
    fun `cancellation exception not swallowed by error handler`() {
        val repo = FakeMediaRepository()
        repo.mediaError = kotlinx.coroutines.CancellationException("ViewModel 已清除")
        val vm = ReaderViewModel(repo, parentId = 1L)
        assertTrue(vm.state.value.isLoading)
        assertNull(vm.state.value.error)
    }

    // ── setCurrentIndex tests ──

    @Test
    fun `sets current index`() {
        val repo = FakeMediaRepository()
        repo.mediaItems = mapOf(1L to testItems)
        val vm = ReaderViewModel(repo, parentId = 1L)

        vm.setCurrentIndex(5)
        assertEquals(5, vm.state.value.currentIndex)
    }

    @Test
    fun `setCurrentIndex to zero`() {
        val repo = FakeMediaRepository()
        repo.mediaItems = mapOf(1L to testItems)
        val vm = ReaderViewModel(repo, parentId = 1L)

        vm.setCurrentIndex(5)
        vm.setCurrentIndex(0)
        assertEquals(0, vm.state.value.currentIndex)
    }

    @Test
    fun `setCurrentIndex to very large value`() {
        val repo = FakeMediaRepository()
        repo.mediaItems = mapOf(1L to testItems)
        val vm = ReaderViewModel(repo, parentId = 1L)

        vm.setCurrentIndex(Int.MAX_VALUE)
        assertEquals(Int.MAX_VALUE, vm.state.value.currentIndex)
    }

    @Test
    fun `setCurrentIndex to negative value`() {
        val repo = FakeMediaRepository()
        repo.mediaItems = mapOf(1L to testItems)
        val vm = ReaderViewModel(repo, parentId = 1L)

        vm.setCurrentIndex(-1)
        assertEquals(-1, vm.state.value.currentIndex)
        // ViewModel 层面不负责 clamp，clamp 在 UI 层 (SliderUtils.clampSliderTarget)
    }

    @Test
    fun `setCurrentIndex unchanged on data load`() {
        val repo = FakeMediaRepository()
        repo.mediaItems = mapOf(1L to testItems)
        val vm = ReaderViewModel(repo, parentId = 1L)

        vm.setCurrentIndex(1)
        assertEquals(1, vm.state.value.currentIndex)

        // Reload (triggered by sort change) shouldn't reset currentIndex
        vm.setSortMode(com.example.reader.data.repository.SortMode.NAME)
        assertEquals(1, vm.state.value.currentIndex)
    }

    // ── initialIndex constructor tests ──

    @Test
    fun `initialIndex set via constructor`() {
        val repo = FakeMediaRepository()
        repo.mediaItems = mapOf(1L to testItems)
        val vm = ReaderViewModel(repo, parentId = 1L, initialIndex = 1)

        assertEquals(1, vm.state.value.currentIndex)
    }

    @Test
    fun `initialIndex defaults to zero`() {
        val repo = FakeMediaRepository()
        repo.mediaItems = mapOf(1L to testItems)
        val vm = ReaderViewModel(repo, parentId = 1L)

        assertEquals(0, vm.state.value.currentIndex)
    }

    @Test
    fun `initialIndex large value via constructor`() {
        val repo = FakeMediaRepository()
        repo.mediaItems = mapOf(1L to testItems)
        val vm = ReaderViewModel(repo, parentId = 1L, initialIndex = 42)

        assertEquals(42, vm.state.value.currentIndex)
    }

    // ── currentIndex preserved across mode switch ──

    @Test
    fun `currentIndex preserved when switching to pager`() {
        val repo = FakeMediaRepository()
        repo.mediaItems = mapOf(1L to testItems)
        val vm = ReaderViewModel(repo, parentId = 1L)

        vm.setCurrentIndex(1)
        vm.switchMode(ReaderMode.Pager)
        assertEquals(1, vm.state.value.currentIndex)
        assertEquals(ReaderMode.Pager, vm.state.value.currentMode)
    }

    @Test
    fun `currentIndex preserved when switching back`() {
        val repo = FakeMediaRepository()
        repo.mediaItems = mapOf(1L to testItems)
        val vm = ReaderViewModel(repo, parentId = 1L)

        vm.setCurrentIndex(1)
        vm.switchMode(ReaderMode.Pager)
        vm.switchMode(ReaderMode.ContinuousScroll)
        assertEquals(1, vm.state.value.currentIndex)
    }

    // ── Sort tests ──

    @Test
    fun `sort mode change reloads`() {
        val repo = FakeMediaRepository()
        repo.mediaItems = mapOf(1L to testItems)
        val vm = ReaderViewModel(repo, parentId = 1L)

        assertEquals(com.example.reader.data.repository.SortMode.DATE, vm.state.value.sortMode)
        vm.setSortMode(com.example.reader.data.repository.SortMode.NAME)
        assertEquals(com.example.reader.data.repository.SortMode.NAME, vm.state.value.sortMode)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun `toggle sort order cycles correctly`() {
        val repo = FakeMediaRepository()
        repo.mediaItems = mapOf(1L to testItems)
        val vm = ReaderViewModel(repo, parentId = 1L)

        assertEquals(com.example.reader.data.repository.SortOrder.DESC, vm.state.value.sortOrder)
        vm.toggleSortOrder()
        assertEquals(com.example.reader.data.repository.SortOrder.ASC, vm.state.value.sortOrder)
        vm.toggleSortOrder()
        assertEquals(com.example.reader.data.repository.SortOrder.DESC, vm.state.value.sortOrder)
    }
}
