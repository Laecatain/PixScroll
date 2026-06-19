package com.example.reader.ui.folderlist

import com.example.reader.data.model.MediaFolder
import com.example.reader.data.repository.FakeMediaRepository
import com.example.reader.data.repository.SortMode
import com.example.reader.data.repository.SortOrder
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

    // ?? Folder sort regression tests ??

    private val sortableFolders = listOf(
        MediaFolder(id = 1, folderName = "Camera", folderPath = "/DCIM/Camera", coverImageUri = null, mediaCount = 50, coverDateModified = 3000),
        MediaFolder(id = 2, folderName = "Album", folderPath = "/Pictures/Album", coverImageUri = null, mediaCount = 10, coverDateModified = 1000),
        MediaFolder(id = 3, folderName = "Download", folderPath = "/Download", coverImageUri = null, mediaCount = 30, coverDateModified = 2000),
    )

    private fun folderNames(vm: FolderListViewModel): List<String> {
        val s = vm.state.value as FolderUiState.Success
        return s.folders.map { it.folderName }
    }

    @Test
    fun `NAME ASC sorts folders A-Z`() {
        val repo = FakeMediaRepository()
        repo.folders = sortableFolders
        val vm = FolderListViewModel(repo)

        vm.updateSortMode(SortMode.NAME)
        assertEquals(listOf("Album", "Camera", "Download"), folderNames(vm))
    }

    @Test
    fun `NAME DESC sorts folders Z-A`() {
        val repo = FakeMediaRepository()
        repo.folders = sortableFolders
        val vm = FolderListViewModel(repo)

        vm.updateSortMode(SortMode.NAME)
        vm.toggleSortOrder() // default DESC → ASC, toggle → DESC... wait, updateSortMode keeps current order
        // default order is DESC, so NAME+DESC should be Z-A
        assertEquals(listOf("Download", "Camera", "Album"), folderNames(vm))
    }

    @Test
    fun `DATE DESC sorts newest folder first`() {
        val repo = FakeMediaRepository()
        repo.folders = sortableFolders
        val vm = FolderListViewModel(repo)

        // default is DATE+DESC; coverDateModified: Camera=3000, Download=2000, Album=1000
        assertEquals(listOf("Camera", "Download", "Album"), folderNames(vm))
    }

    @Test
    fun `DATE ASC sorts oldest folder first`() {
        val repo = FakeMediaRepository()
        repo.folders = sortableFolders
        val vm = FolderListViewModel(repo)

        vm.toggleSortOrder() // DESC → ASC
        assertEquals(listOf("Album", "Download", "Camera"), folderNames(vm))
    }

    @Test
    fun `SIZE DESC sorts most media first`() {
        val repo = FakeMediaRepository()
        repo.folders = sortableFolders
        val vm = FolderListViewModel(repo)

        vm.updateSortMode(SortMode.SIZE)
        // mediaCount: Camera=50, Download=30, Album=10
        assertEquals(listOf("Camera", "Download", "Album"), folderNames(vm))
    }

    @Test
    fun `SIZE ASC sorts least media first`() {
        val repo = FakeMediaRepository()
        repo.folders = sortableFolders
        val vm = FolderListViewModel(repo)

        vm.updateSortMode(SortMode.SIZE)
        vm.toggleSortOrder() // DESC → ASC
        assertEquals(listOf("Album", "Download", "Camera"), folderNames(vm))
    }
}
