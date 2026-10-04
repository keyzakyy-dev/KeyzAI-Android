package com.keyzai.app.ui.settings

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keyzai.app.AppContainer
import com.keyzai.app.data.DEFAULT_MODEL
import com.keyzai.app.data.MODELS
import com.keyzai.app.data.UserPreferences
import com.keyzai.app.data.modelById
import com.keyzai.app.ui.home.initialsOf
import com.keyzai.app.ui.rememberVm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val user = container.session.cachedUser

    var isLoading by mutableStateOf(true)
    var isSaving by mutableStateOf(false)
    var defaultModel by mutableStateOf(DEFAULT_MODEL)
    var displayName by mutableStateOf("")
    var notice by mutableStateOf<String?>(null)

    init {
        viewModelScope.launch {
            // Nilai lokal dulu supaya UI langsung terisi
            defaultModel = runCatching { container.session.modelFlow.first() }.getOrDefault(DEFAULT_MODEL)
            try {
                val (prefs, _) = withContext(Dispatchers.IO) { container.api.getPreferences() }
                applyPrefs(prefs)
            } catch (e: Exception) {
                // jaringan gagal — pakai nilai lokal saja
            } finally {
                isLoading = false
            }
        }
    }

    private fun applyPrefs(prefs: UserPreferences) {
        prefs.default_model?.let { defaultModel = it }
        prefs.display_name?.let { displayName = it }
    }

    fun saveModel(id: String) {
        defaultModel = id
        viewModelScope.launch {
            isSaving = true
            try {
                withContext(Dispatchers.IO) {
                    container.api.savePreferences(mapOf("default_model" to id))
                }
                container.session.setModel(id)
                notice = "Model default disimpan"
            } catch (e: Exception) {
                notice = e.message ?: "Gagal menyimpan"
            } finally {
                isSaving = false
            }
        }
    }

    fun saveDisplayName() {
        viewModelScope.launch {
            isSaving = true
            try {
                withContext(Dispatchers.IO) {
                    container.api.savePreferences(mapOf("display_name" to displayName.trim()))
                }
                notice = "Nama tampilan disimpan"
            } catch (e: Exception) {
                notice = e.message ?: "Gagal menyimpan"
            } finally {
                isSaving = false
            }
        }
    }

    fun deleteAll(onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { container.repo.deleteAll() }
                notice = "Semua percakapan dihapus"
            } catch (e: Exception) {
                notice = e.message ?: "Gagal menghapus"
            }
            onDone()
        }
    }

    fun logout() {
        viewModelScope.launch {
            container.session.clear()
            container.repo.clearLocal()
        }
    }

    fun dismissNotice() {
        notice = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onLoggedOut: () -> Unit,
) {
    val vm: SettingsViewModel = rememberVm { SettingsViewModel(container) }
    val snackbar = remember { SnackbarHostState() }
    var showModelMenu by remember { mutableStateOf(false) }
    var showDeleteAll by remember { mutableStateOf(false) }
    var showLogout by remember { mutableStateOf(false) }

    vm.notice?.let { n ->
        LaunchedEffect(n) {
            snackbar.showSnackbar(n)
            vm.dismissNotice()
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
                title = { Text("Pengaturan", fontWeight = FontWeight.Bold) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Profil
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(52.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Text(
                                initialsOf(vm.user?.name ?: "?"),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(vm.user?.name ?: "Pengguna", fontWeight = FontWeight.SemiBold)
                        Text(
                            vm.user?.email ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (vm.isLoading) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                // Model default
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Model default", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Model yang dipakai untuk chat baru.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Box {
                            OutlinedButton(
                                onClick = { showModelMenu = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(modelById(vm.defaultModel).label, modifier = Modifier.weight(1f))
                                Icon(Icons.Default.ExpandMore, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded = showModelMenu,
                                onDismissRequest = { showModelMenu = false },
                            ) {
                                MODELS.forEach { m ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(m.label)
                                                if (m.id == vm.defaultModel) {
                                                    Spacer(Modifier.width(6.dp))
                                                    Icon(
                                                        Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(16.dp),
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            showModelMenu = false
                                            vm.saveModel(m.id)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // Nama tampilan
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Nama tampilan", fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = vm.displayName,
                                onValueChange = { vm.displayName = it },
                                singleLine = true,
                                placeholder = { Text("Nama") },
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { vm.saveDisplayName() }, enabled = !vm.isSaving) {
                                Text("Simpan")
                            }
                        }
                    }
                }

                // Zona berbahaya
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Data", fontWeight = FontWeight.SemiBold)
                        OutlinedButton(
                            onClick = { showDeleteAll = true },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Hapus semua percakapan")
                        }
                        Button(
                            onClick = { showLogout = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Keluar")
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "KeyzAI Android v1.0.0",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }

    if (showDeleteAll) {
        AlertDialog(
            onDismissRequest = { showDeleteAll = false },
            title = { Text("Hapus semua percakapan?") },
            text = { Text("Seluruh riwayat chat di akun ini akan dihapus permanen dan tidak bisa dikembalikan.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteAll = false
                    vm.deleteAll {}
                }) { Text("Hapus semua", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteAll = false }) { Text("Batal") } },
        )
    }

    if (showLogout) {
        AlertDialog(
            onDismissRequest = { showLogout = false },
            title = { Text("Keluar dari KeyzAI?") },
            text = { Text("Kamu harus masuk lagi dengan Google untuk memakai aplikasi.") },
            confirmButton = {
                TextButton(onClick = {
                    showLogout = false
                    vm.logout()
                    onLoggedOut()
                }) { Text("Keluar") }
            },
            dismissButton = { TextButton(onClick = { showLogout = false }) { Text("Batal") } },
        )
    }
}
