package com.example.reader.ui.folderlist

import com.example.reader.data.model.MediaFolder
import com.example.reader.data.repository.FakeMediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
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
    fun `emits Success with folders`() {
        val repo = FakeMediaRepository()
        repo.folders = listOf(
            MediaFolder(id = 1, folderName = "Camera", folderPath = "/DCIM/Camera", coverImageUri = null, mediaCount = 5)
        )
        val vm = FolderListViewModel(repo)

        val s = vm.state.value as FolderUiState.Success
        assertEquals(1, s.folders.size)
        assertEquals("Camera", s.folders[0].folderName)
        assertEquals(5, s.folders[0].mediaCount)
    }

    @Test
    fun `emits Error on failure`() {
        val repo = FakeMediaRepository()
        repo.foldersError = RuntimeException("权限被拒绝")
        val vm = FolderListViewModel(repo)

        val s = vm.state.value as FolderUiState.Error
        assertEquals("权限被拒绝", s.message)
    }

    @Test
    fun `retry after error succeeds`() {
        val repo = FakeMediaRepository()
        repo.foldersError = RuntimeException("首次失败")
        val vm = FolderListViewModel(repo)
        assertTrue(vm.state.value is FolderUiState.Error)

        repo.foldersError = null
        repo.folders = listOf(
            MediaFolder(id = 2, folderName = "Screenshots", folderPath = "/Pictures/Screenshots", coverImageUri = null, mediaCount = 3)
        )
        vm.updateSortMode(vm.sortMode) // trigger reload

        val s = vm.state.value as FolderUiState.Success
        assertEquals(1, s.folders.size)
        assertEquals("Screenshots", s.folders[0].folderName)
    }

    @Test
    fun `empty folders emits Success with empty list`() {
        val repo = FakeMediaRepository()
        repo.folders = emptyList()
        val vm = FolderListViewModel(repo)

        val s = vm.state.value as FolderUiState.Success
        assertTrue(s.folders.isEmpty())
    }

    @Test
    fun `CancellationException not swallowed, stays Loading`() {
        val repo = FakeMediaRepository()
        repo.folders = emptyList()
        repo.foldersError = kotlinx.coroutines.CancellationException("ViewModel 已清除")
        val vm = FolderListViewModel(repo)
        // CancellationException 被 rethrow，状态保持 loadFolders 设置的 Loading
        assertTrue(vm.state.value is FolderUiState.Loading)
    }

    @Test
    fun `mediaStoreChanges emits correctly`() = runTest {
        val repo = FakeMediaRepository()
        val emitted = mutableListOf<Unit>()
        val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            repo.mediaStoreChanges.collect { emitted.add(it) }
        }

        repo.emitMediaStoreChange()
        testScheduler.advanceUntilIdle()
        assertEquals(1, emitted.size)

        repo.emitMediaStoreChange()
        testScheduler.advanceUntilIdle()
        assertEquals(2, emitted.size)

        job.cancel()
    }
}
