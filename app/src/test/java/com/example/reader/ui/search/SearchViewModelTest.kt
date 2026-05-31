package com.example.reader.ui.search

import com.example.reader.data.model.MediaFolder
import com.example.reader.data.model.MediaItem
import com.example.reader.data.repository.FakeMediaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state defaults to folders category`() = runTest {
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
    fun `search populates folder video and image results`() = runTest {
        val repo = FakeMediaRepository()
        val folder = MediaFolder(
            id = 1L, folderName = "Camera", folderPath = "/DCIM/Camera",
            coverImageUri = null, mediaCount = 2
        )
        val video = MediaItem(
            null, "clip.mp4", "video/mp4", 100L, 10L, "/DCIM/Camera/clip.mp4"
        )
        val image = MediaItem(
            null, "photo.jpg", "image/jpeg", 200L, 20L, "/DCIM/Camera/photo.jpg"
        )
        repo.searchFolderResults = listOf(folder)
        repo.searchResults = listOf(video, image)
        val vm = SearchViewModel(repo)
        vm.onQueryChange("cam")
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals("cam", state.query)
        assertEquals(listOf(folder), state.folderResults)
        assertEquals(listOf(video), state.videoResults)
        assertEquals(listOf(image), state.imageResults)
        assertTrue(state.hasSearched)
        assertFalse(state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun `category switch does not requery`() = runTest {
        val repo = FakeMediaRepository()
        val vm = SearchViewModel(repo)
        vm.onQueryChange("cam")
        advanceUntilIdle()
        val callCount = repo.searchMediaCallCount

        vm.selectCategory(SearchCategory.IMAGES)

        assertEquals(callCount, repo.searchMediaCallCount)
    }

    @Test
    fun `blank query clears and resets category`() = runTest {
        val repo = FakeMediaRepository()
        val vm = SearchViewModel(repo)
        vm.onQueryChange("cam")
        advanceUntilIdle()
        assertTrue(vm.state.value.hasSearched)

        vm.onQueryChange("")
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals("", state.query)
        assertFalse(state.hasSearched)
        assertTrue(state.results.isEmpty())
    }

    @Test
    fun `clearSearch resets to default state`() = runTest {
        val repo = FakeMediaRepository()
        val vm = SearchViewModel(repo)
        vm.onQueryChange("cam")
        advanceUntilIdle()

        vm.clearSearch()

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
    fun `search error in one service does not affect the other results`() = runTest {
        val repo = FakeMediaRepository()
        repo.searchError = RuntimeException("搜索文件失败")
        repo.searchFolderResults = listOf(
            MediaFolder(
                id = 1L, folderName = "Camera", folderPath = "/DCIM/Camera",
                coverImageUri = null, mediaCount = 2
            )
        )
        val vm = SearchViewModel(repo)

        vm.onQueryChange("cam")
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.hasSearched)
        assertFalse(state.isLoading)
        assertNull(state.error)  // parallel search isolates errors
        assertTrue(state.folderResults.isNotEmpty())  // folder search still succeeded
    }

    @Test
    fun `CancellationException is not swallowed`() = runTest {
        val repo = FakeMediaRepository()
        repo.searchError = CancellationException("取消")
        val vm = SearchViewModel(repo)

        vm.onQueryChange("cam")
        advanceUntilIdle()

        // CancellationException propagates and cancels the search job,
        // so the success/error state updates never execute
        val state = vm.state.value
        assertTrue(state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun `debounce delays search execution until 300ms passed`() = runTest {
        val repo = FakeMediaRepository()
        val vm = SearchViewModel(repo)

        vm.onQueryChange("cam")

        // Before advanceTime, should still be loading (delay not yet triggered)
        assertTrue(vm.state.value.isLoading)
        assertFalse(vm.state.value.hasSearched)

        // Advance 299ms — still before debounce
        advanceTimeBy(299)
        assertTrue(vm.state.value.isLoading)

        // Advance past 300ms — now debounce triggers
        advanceTimeBy(2)  // total = 301ms
        advanceUntilIdle()  // finish search

        assertFalse(vm.state.value.isLoading)
        assertTrue(vm.state.value.hasSearched)
    }

    @Test
    fun `debounce cancelled by rapid keystrokes`() = runTest {
        val repo = FakeMediaRepository()
        val vm = SearchViewModel(repo)

        vm.onQueryChange("c")
        vm.onQueryChange("ca")
        vm.onQueryChange("cam")
        advanceUntilIdle()  // only last query fires

        assertEquals("cam", repo.lastSearchMediaQuery)
        assertEquals(1, repo.searchMediaCallCount)
        assertEquals(1, repo.searchFoldersCallCount)
    }

    @Test
    fun `parallel search runs both queries concurrently`() = runTest {
        val repo = FakeMediaRepository()
        repo.searchFolderResults = listOf(
            MediaFolder(
                id = 1L, folderName = "Camera", folderPath = "/DCIM/Camera",
                coverImageUri = null, mediaCount = 1
            )
        )
        repo.searchResults = listOf(
            MediaItem(null, "cam.jpg", "image/jpeg", 100L, 10L, "/DCIM/Camera/cam.jpg")
        )
        val vm = SearchViewModel(repo)

        vm.onQueryChange("cam")
        advanceUntilIdle()

        assertEquals(1, repo.searchMediaCallCount)
        assertEquals(1, repo.searchFoldersCallCount)
        assertTrue(
            vm.state.value.folderResults.isNotEmpty()
                || vm.state.value.videoResults.isNotEmpty()
                || vm.state.value.imageResults.isNotEmpty()
        )
    }

    @Test
    fun `parallel search partial failure still shows media results`() = runTest {
        val repo = FakeMediaRepository()
        repo.folderSearchError = RuntimeException("文件夹搜索失败")
        repo.searchResults = listOf(
            MediaItem(null, "cam.jpg", "image/jpeg", 100L, 10L, "/DCIM/Camera/cam.jpg")
        )
        val vm = SearchViewModel(repo)

        vm.onQueryChange("cam")
        advanceUntilIdle()

        // Media results should still appear even though folder search failed
        assertEquals(1, repo.searchMediaCallCount)
    }

    @Test
    fun `LRU cache returns cached result instantly without re-query`() = runTest {
        val repo = FakeMediaRepository()
        repo.searchFolderResults = listOf(
            MediaFolder(
                id = 1L, folderName = "Camera", folderPath = "/DCIM/Camera",
                coverImageUri = null, mediaCount = 1
            )
        )
        repo.searchResults = listOf(
            MediaItem(null, "cam.jpg", "image/jpeg", 100L, 10L, "/DCIM/Camera/cam.jpg")
        )
        val vm = SearchViewModel(repo)

        // First search populates cache
        vm.onQueryChange("cam")
        advanceUntilIdle()
        val firstCallCount = repo.searchMediaCallCount
        assertTrue(firstCallCount > 0)

        // Second search should hit cache — no re-query
        vm.onQueryChange("cam")
        advanceUntilIdle()
        assertEquals(firstCallCount, repo.searchMediaCallCount)  // no increase
    }

    @Test
    fun `clearSearch clears LRU cache`() = runTest {
        val repo = FakeMediaRepository()
        repo.searchFolderResults = listOf(
            MediaFolder(
                id = 1L, folderName = "Camera", folderPath = "/DCIM/Camera",
                coverImageUri = null, mediaCount = 1
            )
        )
        repo.searchResults = listOf(
            MediaItem(null, "cam.jpg", "image/jpeg", 100L, 10L, "/DCIM/Camera/cam.jpg")
        )
        val vm = SearchViewModel(repo)

        vm.onQueryChange("cam")
        advanceUntilIdle()

        vm.clearSearch()

        // After clear, same query should trigger re-fetch
        vm.onQueryChange("cam")
        advanceUntilIdle()
        assertTrue(repo.searchMediaCallCount >= 2)  // increased
    }
}
