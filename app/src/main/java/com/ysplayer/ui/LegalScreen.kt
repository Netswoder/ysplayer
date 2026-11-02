@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.ysplayer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun LegalScreen(notices: String, privacy: String, onBack: () -> Unit, onExport: () -> Unit) {
    var privacySelected by rememberSaveable { mutableStateOf(false) }
    val text = if (privacySelected) privacy else notices
    val chunks = remember(text) { text.chunked(6000) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("开源许可与隐私说明") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!privacySelected, { privacySelected = false }, label = { Text("开源许可") })
                FilterChip(privacySelected, { privacySelected = true }, label = { Text("隐私说明") })
            }
            if (!privacySelected) {
                Text("应用代码采用 Apache-2.0。字幕编码检测库采用 MPL 1.1，原版源码与构建说明随应用提供，可离线导出。其他组件保留各自许可。",
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onExport, modifier = Modifier.padding(horizontal = 8.dp)) { Text("导出 MPL 库源码包") }
            }
            key(privacySelected) {
                LazyColumn(Modifier.fillMaxSize().testTag("legal_document"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(chunks) { _, chunk -> SelectionContainer { Text(chunk, style = MaterialTheme.typography.bodySmall) } }
                }
            }
        }
    }
}
