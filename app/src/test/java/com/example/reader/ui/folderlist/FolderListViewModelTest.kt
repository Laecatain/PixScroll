package com.example.reader.ui.folderlist

import com.example.reader.data.model.MediaFolder
import com.example.reader.data.repository.FakeMediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FolderListViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `emits loading then folders on success`() {
        val repo = FakeMediaRepository()
        repo.folders = listOf(
            MediaFolder(id = 1, folderName = "Camera", folderPath = "/DCIM/Camera", coverImageUri = null, mediaCount = 5)
        )
        val vm = FolderListViewModel(repo)

        assertFalse(vm.state.value.isLoading)
        assertEquals(1, vm.state.value.folders.size)
        assertEquals("Camera", vm.state.value.folders[0].folderName)
        assertEquals(5, vm.state.value.folders[0].mediaCount)
    }

    @Test
    fun `emits error on failure`() {
        val repo = FakeMediaRepository()
        repo.foldersError = RuntimeException("权限被拒绝")
        val vm = FolderListViewModel(repo)

        assertFalse(vm.state.value.isLoading)
        assertEquals("权限被拒绝", vm.state.value.error)
        assertTrue(vm.state.value.folders.isEmpty())
    }

    @Test
    fun `retry after error succeeds`() {
        val repo = FakeMediaRepository()
        repo.foldersError = RuntimeException("首次失败")
        val vm = FolderListViewModel(repo)
        assertEquals("首次失败", vm.state.value.error)

        repo.foldersError = null
        repo.folders = listOf(
            MediaFolder(id = 2, folderName = "Screenshots", folderPath = "/Pictures/Screenshots", coverImageUri = null, mediaCount = 3)
        )
        vm.loadFolders()

        assertFalse(vm.state.value.isLoading)
        assertEquals(1, vm.state.value.folders.size)
        assertEquals("Screenshots", vm.state.value.folders[0].folderName)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `empty folders when no media on device`() {
        val repo = FakeMediaRepository()
        repo.folders = emptyList()
        val vm = FolderListViewModel(repo)

        assertFalse(vm.state.value.isLoading)
        assertTrue(vm.state.value.folders.isEmpty())
        assertNull(vm.state.value.error)
    }

    @Test
    fun `cancellation exception not swallowed by error handler`() {
        val repo = FakeMediaRepository()
        repo.folders = emptyList()
        repo.foldersError = kotlinx.coroutines.CancellationException("ViewModel 已清除")
        val vm = FolderListViewModel(repo)
        // CancellationException 被 rethrow，不应被 generic catch 吃到
        // 状态保持初始值（Loading=true, error=null）
        assertTrue(vm.state.value.isLoading)
        assertNull(vm.state.value.error)
    }
}
