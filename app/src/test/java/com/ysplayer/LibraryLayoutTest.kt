package com.ysplayer

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.ysplayer.data.Progress
import com.ysplayer.data.Work
import com.ysplayer.ui.LibraryPage
import com.ysplayer.ui.LibraryTopBar
import com.ysplayer.ui.RemoveWorkDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], application = Application::class)
class LibraryLayoutTest {
    @get:Rule val compose = createComposeRule()
    @Composable private fun Library(works: List<Work>, progress: List<Progress> = emptyList(), open: (String) -> Unit = {}) {
        var grid by remember { mutableStateOf(false) }
        var query by remember { mutableStateOf("") }
        var expanded by remember { mutableStateOf(false) }
        var filters by remember { mutableStateOf(false) }
        MaterialTheme { Column(Modifier.fillMaxSize()) {
            LibraryTopBar(query, expanded, { query = it }, { expanded = it }, { filters = true }, {}, {}, true)
            LibraryPage(works, emptyList(), progress, open, {}, grid, { grid = it }, query, filters, { filters = false })
        } }
    }
    @Test fun collapsedSearchExpandsInToolbarAndLayoutSwitchPreservesQuery() {
        var opened = ""
        compose.setContent { Library(listOf(Work("one", "Alpha", "RJ123456", voices = "Alice、Beth", circles = "Circle", tags = "低语\nASMR", tagsKnown = true),
            Work("two", "Bravo", "RJ123457")), open = { opened = it }) }
        compose.onNodeWithTag("library_list").assertExists()
        compose.onNodeWithTag("library_search_field").assertDoesNotExist()
        listOf("新建作品", "最近收听", "收藏", "待整理").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        compose.onNodeWithContentDescription("搜索作品").performClick()
        compose.onNodeWithTag("library_search_field").performTextInput("Alpha")
        compose.onNodeWithText("音声书架").assertDoesNotExist()
        compose.onNodeWithContentDescription("双列封面视图").performClick()
        compose.onNodeWithTag("library_grid").assertExists()
        compose.onNodeWithText("Bravo").assertDoesNotExist()
        compose.onNodeWithText("Alice", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Beth", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("低语", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("grid_work_one").performClick()
        assertEquals("one", opened)
        compose.onNodeWithContentDescription("列表视图").performClick()
        compose.onNodeWithTag("library_list").assertExists()
        compose.onNodeWithText("Bravo").assertDoesNotExist()
        compose.onNodeWithContentDescription("收起搜索").performClick()
        compose.onNodeWithTag("library_search_field").assertDoesNotExist()
        compose.onNodeWithText("Bravo").assertExists()
    }
    @Test fun searchesAndFiltersByChineseTags() {
        compose.setContent { Library(listOf(Work("one", "Alpha", "RJ123456", tags = "低语\nASMR", tagsKnown = true),
            Work("two", "Bravo", "RJ123457", tags = "环境音", tagsKnown = true))) }
        compose.onNodeWithContentDescription("搜索作品").performClick()
        compose.onNodeWithTag("library_search_field").performTextInput("低语")
        compose.onNodeWithContentDescription("双列封面视图").performClick()
        compose.onNodeWithTag("grid_work_one").assertExists()
        compose.onNodeWithTag("grid_work_two").assertDoesNotExist()
        compose.onNodeWithTag("library_search_field").performTextClearance()
        compose.onNodeWithContentDescription("筛选作品").performClick()
        compose.onNodeWithTag("tag_filter_环境音").performScrollTo().performClick()
        compose.onNodeWithText("应用").performClick()
        compose.onNodeWithTag("grid_work_one").assertDoesNotExist()
        compose.onNodeWithTag("grid_work_two").assertExists()
    }
    @Test fun favoritesAndReviewFiltersLiveInDialogAndCanBeCleared() {
        compose.setContent { Library(listOf(Work("one", "Both", favorite = true, review = "待确认"),
            Work("two", "Favorite only", favorite = true), Work("three", "Review only", review = "待确认"))) }
        compose.onNodeWithContentDescription("筛选作品").performClick()
        compose.onNodeWithText("最近收听").assertExists()
        compose.onNodeWithTag("library_filter_favorite").performClick()
        compose.onNodeWithTag("library_filter_review").performClick()
        compose.onNodeWithText("应用").performClick()
        compose.onNodeWithText("Both").assertExists()
        compose.onNodeWithText("Favorite only").assertDoesNotExist()
        compose.onNodeWithText("Review only").assertDoesNotExist()
        compose.onNodeWithContentDescription("筛选作品").performClick()
        compose.onNodeWithText("清除筛选").performClick()
        compose.onNodeWithText("Favorite only").assertExists()
        compose.onNodeWithText("Review only").assertExists()
    }
    @Test fun recentListeningSortCanBeEnabledInFilterDialog() {
        compose.setContent { Library(listOf(Work("one", "Earlier"), Work("two", "Latest")),
            listOf(Progress("one", "track-one", 1, 100), Progress("two", "track-two", 1, 200))) }
        compose.onNodeWithContentDescription("筛选作品").performClick()
        compose.onNodeWithTag("library_filter_recent").performClick()
        compose.onNodeWithText("应用").performClick()
        assertTrue(compose.onNodeWithText("Latest").fetchSemanticsNode().boundsInRoot.top <
            compose.onNodeWithText("Earlier").fetchSemanticsNode().boundsInRoot.top)
    }
    @Test fun removeDialogRequiresAnExplicitTapAndExplainsSourceFilesAreKept() {
        var removed = false
        var shown by mutableStateOf(true)
        compose.setContent { MaterialTheme {
            if (shown) RemoveWorkDialog("Example", { shown = false }) { removed = true; shown = false }
        } }
        compose.onNodeWithText("源音频、封面和字幕文件保留", substring = true).assertExists()
        compose.onNodeWithText("取消").performClick()
        assertFalse(removed)
        compose.runOnIdle { shown = true }
        compose.onNodeWithText("移除").performClick()
        assertTrue(removed)
    }
}
