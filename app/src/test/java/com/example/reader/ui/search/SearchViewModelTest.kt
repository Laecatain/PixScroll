package com.example.reader.ui.search

import com.example.reader.data.model.MediaFolder
import com.example.reader.data.model.MediaItem
import com.example.reader.data.repository.FakeMediaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state defaults to folders category`() {
        val vm = SearchViewModel(FakeMediaRepository())

        val state = vm.state.value
        assertEquals("", state.query)
        assertEquals(SearchCategory.FOLDERS, state.selectedCategory)
        assertTrue(state.folderResults.isEmpty())
        assertTrue(state.videoResults.isEmpty())
        assertTrue(state.imageResults.isEmpty())
        assertFalse(state.isLoading)
        assertFalse(state.hasSearched)
        assertNull(state.error)
    }

    @Test
    fun `search populates folder video and image results`() {
        val repo = FakeMediaRepository()
        val folder = MediaFolder(
            id = 1L,
            folderName = "Camera",
            folderPath = "/DCIM/Camera",
            coverImageUri = null,
            mediaCount = 2
        )
        val video = MediaItem(null, "clip.mp4", "video/mp4", 100L, 10L, "/DCIM/Camera/clip.mp4")
        val image = MediaItem(null, "photo.jpg", "image/jpeg", 200L, 20L, "/DCIM/Camera/photo.jpg")
        repo.searchFolderResults = listOf(folder)
        repo.searchResults = listOf(video, image)
        val vm = SearchViewModel(repo)

        vm.onQueryChange("cam")

        val state = vm.state.value
        assertEquals("cam", state.query)
        assertEquals(SearchCategory.FOLDERS, state.selectedCategory)
        assertEquals(listOf(folder), state.folderResults)
        assertEquals(listOf(video), state.videoResults)
        assertEquals(listOf(image), state.imageResults)
        assertTrue(state.hasSearched)
        assertFalse(state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun `category switch does not requery`() {
        val repo = FakeMediaRepository()
        repo.searchResults = listOf(
            MediaItem(null, "clip.mp4", "video/mp4", 100L, 10L, "/DCIM/Camera/clip.mp4")
        )
        val vm = SearchViewModel(repo)
        vm.onQueryChange("clip")
        val mediaCalls = repo.searchMediaCallCount
        val folderCalls = repo.searchFoldersCallCount

        vm.selectCategory(SearchCategory.VIDEOS)

        val state = vm.state.value
        assertEquals(SearchCategory.VIDEOS, state.selectedCategory)
        assertEquals(mediaCalls, repo.searchMediaCallCount)
        assertEquals(folderCalls, repo.searchFoldersCallCount)
    }

    @Test
    fun `blank query clears and resets category`() {
        val repo = FakeMediaRepository()
        repo.searchResults = listOf(
            MediaItem(null, "clip.mp4", "video/mp4", 100L, 10L, "/DCIM/Camera/clip.mp4")
        )
        val vm = SearchViewModel(repo)
        vm.onQueryChange("clip")
        vm.selectCategory(SearchCategory.VIDEOS)

        vm.onQueryChange(" ")

        val state = vm.state.value
        assertEquals(SearchCategory.FOLDERS, state.selectedCategory)
        assertTrue(state.folderResults.isEmpty())
        assertTrue(state.videoResults.isEmpty())
        assertTrue(state.imageResults.isEmpty())
        assertFalse(state.hasSearched)
        assertFalse(state.isLoading)
    }

    @Test
    fun `clearSearch resets to default state`() {
        val repo = FakeMediaRepository()
        repo.searchResults = listOf(
            MediaItem(null, "clip.mp4", "video/mp4", 100L, 10L, "/DCIM/Camera/clip.mp4")
        )
        val vm = SearchViewModel(repo)
        vm.onQueryChange("clip")
        vm.selectCategory(SearchCategory.IMAGES)

        vm.clearSearch()

        assertEquals(SearchState(), vm.state.value)
    }

    @Test
    fun `search error updates error state`() {
        val repo = FakeMediaRepository()
        repo.folderSearchError = RuntimeException("搜索文件夹失败")
        val vm = SearchViewModel(repo)

        vm.onQueryChange("camera")

        val state = vm.state.value
        assertFalse(state.isLoading)
        assertTrue(state.hasSearched)
        assertEquals("搜索文件夹失败", state.error)
    }

    @Test
    fun `CancellationException is not swallowed`() {
        val repo = FakeMediaRepository()
        repo.folderSearchError = CancellationException("ViewModel 已清除")
        val vm = SearchViewModel(repo)

        vm.onQueryChange("camera")

        val state = vm.state.value
        assertTrue(state.isLoading)
        assertNull(state.error)
    }
}
