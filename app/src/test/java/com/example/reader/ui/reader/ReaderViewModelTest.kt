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

    @Test
    fun `sets current index`() {
        val repo = FakeMediaRepository()
        repo.mediaItems = mapOf(1L to testItems)
        val vm = ReaderViewModel(repo, parentId = 1L)

        vm.setCurrentIndex(5)
        assertEquals(5, vm.state.value.currentIndex)
    }
}
