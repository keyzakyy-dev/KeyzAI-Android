package com.keyzai.app.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keyzai.app.AppContainer
import com.keyzai.app.data.ChatRepository
import com.keyzai.app.data.Conversation
import com.keyzai.app.data.SessionStore
import com.keyzai.app.ui.rememberVm
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class HomeViewModel(
    private val repo: ChatRepository,
    private val session: SessionStore,
) : ViewModel() {
    val conversations = repo.conversations
    val user = session.cachedUser

    var isLoading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    fun load() {
        if (isLoading) return
        viewModelScope.launch {
            isLoading = true
            error = null
            try {
                repo.loadConversations()
            } catch (e: Exception) {
                error = e.message ?: "Gagal memuat riwayat"
            } finally {
                isLoading = false
            }
        }
    }

    fun newChat(): String = repo.createLocal().id

    fun rename(id: String, title: String) = viewModelScope.launch {
        runCatching { repo.rename(id, title) }
    }

    fun togglePin(id: String) = viewModelScope.launch {
        runCatching { repo.togglePin(id) }
    }

    fun delete(id: String) = viewModelScope.launch {
        runCatching { repo.delete(id) }
    }
}

/** "Hari ini", "Kemarin", "3 Jun", "3 Jun 2025". */
fun relativeDay(epochMs: Long): String {
    if (epochMs <= 0) return ""
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when {
        date == today -> "Hari ini"
        date == today.minusDays(1) -> "Kemarin"
        date.year == today.year -> date.format(DateTimeFormatter.ofPattern("d MMM", Locale("id")))
        else -> date.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale("id")))
    }
}

fun initialsOf(name: String): String {
    val parts = name.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
    if (parts.isEmpty()) return "?"
    return (parts.first().first().toString() + (parts.getOrNull(1)?.first()?.toString() ?: "")).uppercase()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenChat: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm: HomeViewModel = rememberVm { HomeViewModel(container.repo, container.session) }
    val conversations by vm.conversations.collectAsState()
    var query by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<Conversation?>(null) }
    var renameFor by remember { mutableStateOf<Conversation?>(null) }
    var deleteFor by remember { mutableStateOf<Conversation?>(null) }
    var renameText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.load() }

    val filtered = remember(conversations, query) {
        if (query.isBlank()) conversations
        else conversations.filter { it.title.contains(query, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("KeyzAI", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { vm.load() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Muat ulang")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(34.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Text(
                                    initialsOf(vm.user?.name ?: "?"),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onOpenChat(vm.newChat()) }) {
                Icon(Icons.Default.Add, contentDescription = "Chat baru")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Cari percakapan") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            when {
                vm.isLoading && conversations.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                filtered.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("💬", style = MaterialTheme.typography.headlineLarge)
                            Spacer(Modifier.height(8.dp))
                            val msg = when {
                                vm.error != null -> "Gagal memuat: ${vm.error}"
                                query.isNotBlank() -> "Tidak ada hasil untuk \"$query\""
                                else -> "Belum ada percakapan.\nKetuk + untuk mulai chat baru."
                            }
                            Text(
                                msg,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (vm.error != null) {
                                Spacer(Modifier.height(12.dp))
                                TextButton(onClick = { vm.load() }) { Text("Coba lagi") }
                            }
                        }
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(filtered, key = { it.id }) { conv ->
                            ConversationRow(
                                conv = conv,
                                onClick = { onOpenChat(conv.id) },
                                onLongClick = { menuFor = conv },
                            )
                        }
                    }
                }
            }
        }
    }

    // Menu aksi (tahan lama)
    menuFor?.let { conv ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(conv.title.ifEmpty { "Chat baru" }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = { menuFor = null; vm.togglePin(conv.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (conv.pinned) "Lepas pin" else "Pin ke atas", modifier = Modifier.fillMaxWidth()) }
                    TextButton(
                        onClick = { renameText = conv.title; renameFor = conv; menuFor = null },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Ubah nama", modifier = Modifier.fillMaxWidth()) }
                    TextButton(
                        onClick = { deleteFor = conv; menuFor = null },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "Hapus",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { menuFor = null }) { Text("Tutup") } },
        )
    }

    renameFor?.let { conv ->
        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text("Ubah nama") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) vm.rename(conv.id, renameText.trim())
                    renameFor = null
                }) { Text("Simpan") }
            },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text("Batal") } },
        )
    }

    deleteFor?.let { conv ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Hapus percakapan?") },
            text = { Text("\"${conv.title.ifEmpty { "Chat baru" }}\" akan dihapus permanen.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(conv.id)
                    deleteFor = null
                }) { Text("Hapus", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Batal") } },
        )
    }

}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conv: Conversation,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (conv.pinned) {
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = "Di-pin",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(14.dp)
                            .padding(end = 2.dp),
                    )
                }
                Text(
                    conv.title.ifEmpty { "Chat baru" },
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                relativeDay(conv.updatedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
