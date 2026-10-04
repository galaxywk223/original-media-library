package com.galaxywk.originalmedialibrary.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as MediaDownloaderApplication
        setContent { MediaDownloaderApp(app) }
    }
}

class MainViewModel(private val repository: MediaRepository) : ViewModel() {
    val collections = repository.collections().stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyList())
    var jobs by mutableStateOf<List<JobEntity>>(emptyList())
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set

    init { refreshJobs() }
    fun refreshJobs() { viewModelScope.launch { jobs = repository.jobs() } }
    fun resolveAndQueue(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            loading = true
            message = runCatching { "已创建 ${repository.resolveAndQueue(text)} 个下载任务" }.getOrElse { it.message ?: "解析失败" }
            loading = false
            refreshJobs()
        }
    }
    fun extractAudio(collection: CollectionEntity, asset: AssetEntity) {
        viewModelScope.launch { message = repository.extractAudio(collection, asset).fold({ "音频提取完成" }, { it.message ?: "音频提取失败" }) }
    }
    fun extractFirstVideoAudio(collection: CollectionEntity) {
        viewModelScope.launch { message = repository.extractFirstVideoAudio(collection).fold({ "音频提取完成" }, { it.message ?: "音频提取失败" }) }
    }
    fun clearMessage() { message = null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaDownloaderApp(app: MediaDownloaderApplication) {
    val viewModel: MainViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(app.repository) as T
    })
    var page by remember { mutableStateOf("download") }
    var loginOpen by remember { mutableStateOf(false) }
    MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFFC93636))) {
        Scaffold(
            topBar = { TopAppBar(title = { Text(if (page == "download") "下载" else if (page == "library") "媒体库" else if (page == "tasks") "任务" else "设置") }) },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(page == "download", { page = "download" }, icon = { Icon(Icons.Default.Download, null) }, label = { Text("下载") })
                    NavigationBarItem(page == "library", { page = "library" }, icon = { Icon(Icons.Default.Collections, null) }, label = { Text("媒体库") })
                    NavigationBarItem(page == "tasks", { page = "tasks" }, icon = { Icon(Icons.Default.List, null) }, label = { Text("任务") })
                    NavigationBarItem(page == "settings", { page = "settings" }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("设置") })
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (page) {
                    "download" -> DownloadScreen(viewModel)
                    "library" -> LibraryScreen(viewModel)
                    "tasks" -> TasksScreen(viewModel)
                    else -> SettingsScreen(app, onLogin = { loginOpen = true })
                }
            }
        }
        viewModel.message?.let { text ->
            LaunchedEffect(text) { kotlinx.coroutines.delay(3_000); viewModel.clearMessage() }
            SnackbarHost(hostState = remember { SnackbarHostState() }, modifier = Modifier.padding(16.dp))
            AlertDialog(onDismissRequest = viewModel::clearMessage, confirmButton = { TextButton(onClick = viewModel::clearMessage) { Text("确定") } }, title = { Text("提示") }, text = { Text(text) })
        }
        if (loginOpen) LoginDialog(app.session, onClose = { loginOpen = false })
    }
}

@Composable
private fun DownloadScreen(viewModel: MainViewModel) {
    var text by remember { mutableStateOf(TextFieldValue()) }
    Column(Modifier.padding(20.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("粘贴抖音分享内容", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 150.dp), placeholder = { Text("粘贴分享文案、短链接或作品链接") })
        Button(onClick = { viewModel.resolveAndQueue(text.text) }, enabled = text.text.isNotBlank() && !viewModel.loading, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Link, null); Spacer(Modifier.width(8.dp)); Text(if (viewModel.loading) "解析中…" else "解析并下载")
        }
        Text("登录后可下载需要权限访问的作品。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LibraryScreen(viewModel: MainViewModel) {
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    if (collections.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("媒体库为空") }
    else LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(collections) { collection ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(collection.title, style = MaterialTheme.typography.titleMedium)
                    Text("${collection.mediaType} · ${collection.author ?: "本地文件"}", style = MaterialTheme.typography.bodySmall)
                    Text("打开媒体库查看 ${collection.title}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    if (collection.mediaType == "video" || collection.mediaType == "mixed") {
                        TextButton(onClick = { viewModel.extractFirstVideoAudio(collection) }) { Text("提取音频") }
                    }
                }
            }
        }
    }
}

@Composable
private fun TasksScreen(viewModel: MainViewModel) {
    LaunchedEffect(Unit) { viewModel.refreshJobs() }
    if (viewModel.jobs.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("暂无任务") }
    else LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(viewModel.jobs) { job ->
            ListItem(headlineContent = { Text(job.title) }, supportingContent = { Text("${job.status} · ${job.progress}%${job.error?.let { " · $it" } ?: ""}") })
            HorizontalDivider()
        }
    }
}

@Composable
private fun SettingsScreen(app: MediaDownloaderApplication, onLogin: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("设置", style = MaterialTheme.typography.titleLarge)
        ListItem(headlineContent = { Text("抖音登录") }, supportingContent = { Text(if (app.session.loggedIn()) "已配置登录 Cookie" else "未配置登录") }, trailingContent = { Button(onClick = onLogin) { Text(if (app.session.loggedIn()) "重新登录" else "配置登录") } })
        ListItem(headlineContent = { Text("下载目录") }, supportingContent = { Text(context.getExternalFilesDir("media")?.absolutePath ?: "不可用") })
        ListItem(headlineContent = { Text("版本") }, supportingContent = { Text("Android 1.0.0") })
    }
}

@Composable
private fun LoginDialog(session: DouyinSession, onClose: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, confirmButton = { TextButton(onClick = onClose) { Text("完成") } }, title = { Text("配置抖音登录") }, text = {
        AndroidView(factory = { session.webView }, modifier = Modifier.fillMaxWidth().height(480.dp))
    })
}
