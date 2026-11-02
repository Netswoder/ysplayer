@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.ysplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag

@Composable
internal fun LibraryTopBar(query: String, searchExpanded: Boolean, onQueryChange: (String) -> Unit,
    onSearchExpanded: (Boolean) -> Unit, onFilter: () -> Unit, onRefresh: () -> Unit, onAdd: () -> Unit, refreshEnabled: Boolean) {
    val focus = remember { FocusRequester() }
    val closeSearch = { onQueryChange(""); onSearchExpanded(false) }
    BackHandler(searchExpanded, closeSearch)
    TopAppBar(title = {
        if (searchExpanded) TextField(query, onQueryChange,
            Modifier.fillMaxWidth().focusRequester(focus).testTag("library_search_field"),
            placeholder = { Text("作品、RJ、声优、社团、标签") }, singleLine = true,
            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
        else Text("音声书架")
    }, navigationIcon = {
        IconButton(onClick = { if (searchExpanded) closeSearch() else onSearchExpanded(true) }) {
            Icon(if (searchExpanded) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Search, if (searchExpanded) "收起搜索" else "搜索作品")
        }
    }, actions = {
        IconButton(onClick = onFilter) { Icon(Icons.Default.Tune, "筛选作品") }
        if (!searchExpanded) {
            IconButton(onClick = onRefresh, enabled = refreshEnabled) { Icon(Icons.Default.Refresh, "刷新目录") }
            IconButton(onClick = onAdd) { Icon(Icons.Default.Add, "添加目录") }
        }
    })
    LaunchedEffect(searchExpanded) { if (searchExpanded) focus.requestFocus() }
}

@Composable
internal fun RemoveWorkDialog(title: String, onDismiss: () -> Unit, onRemove: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("从作品库移除？") }, text = {
        Text("移除“$title”及其播放列表条目，源音频、封面和字幕文件保留。文件仍在授权目录内时，重新扫描可再次导入。")
    }, confirmButton = { TextButton(onClick = onRemove) { Text("移除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
