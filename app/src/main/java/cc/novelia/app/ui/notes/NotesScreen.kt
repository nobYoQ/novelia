@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package cc.novelia.app.ui.notes

import cc.novelia.app.ui.components.AppSelectionChip
import cc.novelia.app.ui.components.AppChipFlowRow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.Note
import cc.novelia.app.ui.components.AppLazyColumn
import cc.novelia.app.ui.components.AppSheet
import cc.novelia.app.ui.components.EmptyState
import cc.novelia.app.ui.components.MenuRow
import cc.novelia.app.ui.components.Screen
import cc.novelia.app.ui.components.rememberDebouncedQuery
import cc.novelia.app.ui.navigation.AppController

@Composable fun NotesScreen(c: AppController) {
    val state by c.store.state.collectAsStateWithLifecycle(); var editing by remember { mutableStateOf<Note?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var bookKey by rememberSaveable { mutableStateOf<String?>(null) }
    var choosingBook by remember { mutableStateOf(false) }
    val settledQuery = rememberDebouncedQuery(query)
    val allNotes = remember(state.notes, state.books, state.positions) { presentNotes(state) }
    val bookChoices = remember(allNotes) { allNotes.distinctBy { it.note.key }.sortedBy { it.bookTitle } }
    val bookCounts = remember(allNotes) { allNotes.groupingBy { it.note.key }.eachCount() }
    val notes = remember(state.notes, state.books, state.positions, settledQuery, bookKey) { presentNotes(state, settledQuery, bookKey) }
    Screen("书签与笔记", c::back) { padding -> AppLazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "note-search") {
            OutlinedTextField(query, { query = it }, label = { Text("搜索书名、章节、摘录或笔记") }, singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.fillMaxWidth().testTag("notes-search"))
        }
        item(key = "note-filter") {
            AppChipFlowRow() {
                AppSelectionChip(selected = bookKey != null, onClick = { choosingBook = true }, label = { Text(bookChoices.firstOrNull { it.note.key == bookKey }?.bookTitle ?: "全部书籍", maxLines = 1) }, leadingIcon = { Icon(Icons.Outlined.FilterList, null, Modifier.size(18.dp)) })
                if(bookKey != null || query.isNotBlank()) TextButton(onClick = { bookKey = null; query = "" }) { Text("清空筛选") }
                Text("${notes.size} 条", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
        if(state.notes.isEmpty()) item { EmptyState("记下喜欢的句子", "在阅读器长按段落，或点击书签按钮保存。", Icons.Outlined.EditNote) }
        else if(notes.isEmpty()) item { EmptyState("没有匹配的笔记", "调整关键词，或查看全部书籍的笔记。", Icons.Outlined.SearchOff, "清空筛选", { query = ""; bookKey = null }) }
        items(notes, key = { it.note.id }) { item -> val note = item.note; Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(item.bookTitle, style = MaterialTheme.typography.titleSmall)
            Text("${item.chapterTitle} · 第 ${note.paragraph + 1} 段", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(note.quote, maxLines = 5, style = MaterialTheme.typography.bodyMedium); if(note.text.isNotBlank()) Text(note.text, color = MaterialTheme.colorScheme.primary)
            FlowRow {
                TextButton(onClick = {
                    val ref = BookRef.fromKey(note.key)
                    c.read(ref, note.chapterId)
                    c.nav.currentBackStackEntry?.savedStateHandle?.set("readerNoteSourceIndex", note.paragraph)
                }) { Text("回到原文") }
                TextButton(onClick = { editing = note }, modifier = Modifier.heightIn(min = 48.dp)) { Text("编辑笔记") }
                TextButton(onClick = { c.share("${item.bookTitle} · ${item.chapterTitle}\n\n${note.quote}" + if(note.text.isNotBlank()) "\n\n${note.text}" else "") }) { Text("分享") }
                TextButton(onClick = {
                    c.store.update { it.copy(notes = it.notes.filterNot { n -> n.id == note.id }) }
                    c.action {
                        if(c.snackbar.showSnackbar("笔记已删除", actionLabel = "撤销", withDismissAction = true, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                            c.store.update { if(it.notes.any { n -> n.id == note.id }) it else it.copy(notes = it.notes + note) }
                        }
                    }
                }) { Text("删除") }
            }
        } } }
    } }
    if(choosingBook) AppSheet(onDismissRequest = { choosingBook = false }) {
        AppLazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Text("按书籍查看", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge) }
            item { MenuRow("全部书籍", "${allNotes.size} 条笔记", Icons.Outlined.LibraryBooks, { bookKey = null; choosingBook = false }) }
            items(bookChoices, key = { it.note.key }) { item -> MenuRow(item.bookTitle, "${bookCounts[item.note.key] ?: 0} 条笔记", Icons.Outlined.MenuBook, { bookKey = item.note.key; choosingBook = false }) }
        }
    }
    editing?.let { note -> NoteEditorDialog(note, { editing = null }) { value -> c.store.update { it.copy(notes = it.notes.map { n -> if(n.id == note.id) n.copy(text = value) else n }) } } }
}
