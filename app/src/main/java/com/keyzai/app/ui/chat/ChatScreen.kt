package com.keyzai.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keyzai.app.AppContainer
import com.keyzai.app.data.AuthException
import com.keyzai.app.data.ChatMessage
import com.keyzai.app.data.ChatRepository
import com.keyzai.app.data.Conversation
import com.keyzai.app.data.DEFAULT_MODEL
import com.keyzai.app.data.MODELS
import com.keyzai.app.data.MsgState
import com.keyzai.app.data.SendMode
import com.keyzai.app.data.SendResult
import com.keyzai.app.data.SessionStore
import com.keyzai.app.data.getActivePath
import com.keyzai.app.data.modelById
import com.keyzai.app.data.siblingPosition
import com.keyzai.app.ui.markdown.MarkdownText
import com.keyzai.app.ui.rememberVm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(
    private val repo: ChatRepository,
    private val session: SessionStore,
    private val convId: String,
) : ViewModel() {

    val conv: StateFlow<Conversation?> =
        repo.conversations.map { list -> list.find { it.id == convId } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val messages: StateFlow<List<ChatMessage>> =
        conv.map { getActivePath(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val streamingMsgId: StateFlow<String?> = repo.streamingMsgId

    var input by mutableStateOf("")
    var model by mutableStateOf(DEFAULT_MODEL)
    var isSending by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var editingTarget by mutableStateOf<ChatMessage?>(null)

    init {
        viewModelScope.launch {
            session.modelFlow.collect { model = it }
        }
        viewModelScope.launch {
            runCatching { repo.ensureFull(convId) }
        }
    }

    fun selectModel(id: String) {
        model = id
        viewModelScope.launch { session.setModel(id) }
    }

    fun send(text: String = input) {
        val t = text.trim()
        if (t.isEmpty() || isSending) return
        if (editingTarget != null) {
            confirmEdit(t)
            return
        }
        input = ""
        doSend(t, SendMode.New)
    }

    private fun doSend(t: String, mode: SendMode) {
        isSending = true
        error = null
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    repo.sendMessage(convId = convId, content = t, model = model, mode = mode)
                }
                if (result == SendResult.Empty) {
                    error = "Model tidak memberi respons. Coba kirim ulang."
                }
            } catch (e: AuthException) {
                error = e.message // sesi juga dibersihkan global -> kembali ke login
            } catch (e: Exception) {
                error = e.message ?: "Gagal mengirim pesan"
            } finally {
                isSending = false
            }
        }
    }

    fun stop() = repo.stopGeneration()

    fun regenerate(assistantMsgId: String) {
        if (isSending) return
        doSend("", SendMode.Regenerate(assistantMsgId))
    }

    fun startEdit(msg: ChatMessage) {
        editingTarget = msg
        input = msg.content
    }

    fun cancelEdit() {
        editingTarget = null
        input = ""
    }

    private fun confirmEdit(t: String) {
        val target = editingTarget ?: return
        editingTarget = null
        input = ""
        doSend(t, SendMode.Edit(target.id))
    }

    fun navigateBranch(msgId: String, dir: Int) {
        if (isSending) return
        repo.navigateBranch(convId, msgId, dir)
    }

    fun deleteConversation(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { repo.delete(convId) }
            onDone()
        }
    }

    fun sendOption(label: String) = send(label)

    fun dismissError() {
        error = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    container: AppContainer,
    convId: String,
    onBack: () -> Unit,
    onNewChat: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm: ChatViewModel = rememberVm { ChatViewModel(container.repo, container.session, convId) }
    val conv by vm.conv.collectAsState()
    val messages by vm.messages.collectAsState()
    val streamingId by vm.streamingMsgId.collectAsState()
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }

    var showModelMenu by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    vm.error?.let { err ->
        LaunchedEffect(err) {
            snackbar.showSnackbar(err)
            vm.dismissError()
        }
    }

    // Auto-scroll mengikuti pesan baru / streaming, hanya bila user sudah di bawah
    val lastContentLen = messages.lastOrNull()?.content?.length ?: 0
    LaunchedEffect(messages.size, lastContentLen) {
        if (messages.isEmpty()) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (lastVisible >= messages.size - 2) {
            listState.scrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Kembali")
                    }
                },
                title = {
                    Text(
                        conv?.title?.ifEmpty { "Chat baru" } ?: "Memuat…",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    // Pemilih model
                    Box {
                        TextButton(onClick = { showModelMenu = true }) {
                            Text(
                                modelById(vm.model).label,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Icon(Icons.Default.ExpandMore, contentDescription = "Pilih model")
                        }
                        DropdownMenu(expanded = showModelMenu, onDismissRequest = { showModelMenu = false }) {
                            MODELS.forEach { m ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(m.label, fontWeight = FontWeight.Medium)
                                                if (m.id == vm.model) {
                                                    Spacer(Modifier.width(6.dp))
                                                    Icon(
                                                        Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(16.dp),
                                                    )
                                                }
                                            }
                                            Text(
                                                "${m.provider} · ${m.tagline}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    onClick = {
                                        vm.selectModel(m.id)
                                        showModelMenu = false
                                    },
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { showOverflow = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                            DropdownMenuItem(
                                text = { Text("Chat baru") },
                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                                onClick = {
                                    showOverflow = false
                                    onNewChat(container.repo.createLocal().id)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Hapus percakapan", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    showOverflow = false
                                    showDeleteConfirm = true
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            ChatInputBar(
                value = vm.input,
                onValueChange = { vm.input = it },
                isSending = vm.isSending,
                isEditing = vm.editingTarget != null,
                onSend = { vm.send() },
                onStop = { vm.stop() },
                onCancelEdit = { vm.cancelEdit() },
            )
        },
    ) { padding ->
        if (conv == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (messages.isEmpty() && !vm.isSending) {
            EmptyChatHint(Modifier.fillMaxSize().padding(padding))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                items(messages, key = { it.id }) { msg ->
                    val c = conv ?: return@items
                    val isStreamingThis = msg.id == streamingId
                    if (msg.role == "user") {
                        UserBubble(msg, onEdit = { vm.startEdit(msg) })
                    } else {
                        AssistantMessage(
                            msg = msg,
                            conv = c,
                            displayText = msg.content,
                            isStreaming = isStreamingThis,
                            onRegenerate = { vm.regenerate(msg.id) },
                            onNavigate = { dir -> vm.navigateBranch(msg.id, dir) },
                            onOptionSelected = { vm.sendOption(it) },
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Hapus percakapan?") },
            text = { Text("Percakapan ini akan dihapus permanen dari semua perangkat.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    vm.deleteConversation(onBack)
                }) { Text("Hapus", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Batal") } },
        )
    }
}

@Composable
private fun EmptyChatHint(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("✦", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(
                "Mau nulis, debug, atau belajar apa hari ini?",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UserBubble(msg: ChatMessage, onEdit: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(0.85f),
        ) {
            SelectionContainer {
                Text(
                    msg.content,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Default.Edit,
                contentDescription = "Edit pesan",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun AssistantMessage(
    msg: ChatMessage,
    conv: Conversation,
    displayText: String,
    isStreaming: Boolean,
    onRegenerate: () -> Unit,
    onNavigate: (Int) -> Unit,
    onOptionSelected: (String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Column(modifier = Modifier.fillMaxWidth()) {
        if (isStreaming && displayText.isEmpty()) {
            ThinkingIndicator()
        } else {
            Row {
                MarkdownText(
                    text = displayText,
                    modifier = Modifier.weight(1f, fill = false),
                    onOptionSelected = onOptionSelected,
                )
                if (isStreaming) {
                    Text(
                        "▍",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        if (!isStreaming) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(msg.content)) },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Salin",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(onClick = onRegenerate, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Buat ulang",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
                // Navigasi cabang: ‹ 1 / 2 ›
                siblingPosition(conv, msg.id)?.let { (idx, total) ->
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = { onNavigate(-1) }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.ChevronLeft,
                            contentDescription = "Sebelumnya",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        "${idx + 1} / $total",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(onClick = { onNavigate(1) }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = "Berikutnya",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                if (msg.state == MsgState.ABORTED) {
                    Text(
                        "dihentikan",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThinkingIndicator() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            "Berpikir…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    isSending: Boolean,
    isEditing: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onCancelEdit: () -> Unit,
) {
    Surface(shadowElevation = 8.dp) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (isEditing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 4.dp),
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Edit pesan",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onCancelEdit, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Batal edit")
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = { Text(if (isEditing) "Ubah pesan…" else "Tanya lanjutan…") },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 5,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                if (isSending) {
                    FilledIconButton(onClick = onStop, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Stop, contentDescription = "Hentikan")
                    }
                } else {
                    FilledIconButton(
                        onClick = onSend,
                        enabled = value.isNotBlank(),
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Kirim")
                    }
                }
            }
        }
    }
}
