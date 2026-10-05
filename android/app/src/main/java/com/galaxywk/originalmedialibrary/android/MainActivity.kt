package com.galaxywk.originalmedialibrary.android

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MediaDownloaderApp(application as MediaDownloaderApplication) }
    }
}
class MainViewModel(val app: MediaDownloaderApplication) : ViewModel() {
    val library = app.library.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val jobs = app.downloads.jobs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val exportTree = app.settings.exportTree.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    var message by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    var sources by mutableStateOf<List<ParsedSource>>(emptyList()); private set
    var chosen by mutableStateOf<Set<String>>(emptySet())
    var selected by mutableStateOf<Set<String>>(emptySet())
    var viewerId by mutableStateOf<String?>(null)
    fun run(action: suspend () -> String?) {
        viewModelScope.launch {
            try { action()?.let { message = it } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = error.message ?: "操作失败" }
        }
    }
    fun parse(text: String) {
        run {
            busy = true
            try {
                sources = app.downloads.parse(text)
                chosen = sources.filter { !it.duplicate }.map { it.url }.toSet()
                "已识别 ${sources.size} 个作品"
            } finally { busy = false }
        }
    }
    fun queue(force: Boolean) = run { "已创建 ${app.downloads.queue(sources.filter { it.url in chosen }, force)} 个任务" }
    fun toggleSelected(id: String) { selected = if(id in selected) selected-id else selected+id }
    fun scan() = run { "已扫描，新增 ${app.library.scan()} 个文件" }
    fun import(uris: List<Uri>) = run { "已导入 ${app.library.importUris(uris)} 个文件" }
    fun export(id: String) = run {
        require(exportTree.value != null) { "请在设置中选择导出目录" }
        app.library.export(id); "已导出"
    }
    fun trash(ids: List<String>) = run { app.library.trash(ids); selected=emptySet(); viewerId=null; "已移入回收站（导出副本保留）" }
    fun restore(id: String) = run { app.library.restore(id); viewerId=null; "已恢复" }
    fun rename(id: String, title: String) = run { require(title.isNotBlank()) { "名称不能为空" }; app.library.rename(id,title); "已重命名" }
    fun audio(id: String, assetId: String) = run { app.audio.queue(id,assetId); "提取任务已排队" }
    fun cancel(job: JobEntity) = run { app.downloads.cancel(job); null }
    fun retry(job: JobEntity) = run { app.downloads.retry(job); "已排队重试" }
    fun open(asset: AssetEntity) = run { app.storage.open(asset); null }
    fun locate(asset: AssetEntity) = run { app.storage.locate(asset); null }
    fun chooseTree(uri: Uri?) = run { if(uri != null) app.settings.select(uri); "导出目录已设置" }
    fun dismiss() { message = null }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaDownloaderApp(app: MediaDownloaderApplication) {
    val vm: MainViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory=object: ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T: ViewModel> create(modelClass: Class<T>): T = MainViewModel(app) as T
    })
    var page by rememberSaveable { mutableStateOf("下载") }
    var login by rememberSaveable { mutableStateOf(false) }
    var sessionRevision by remember { mutableIntStateOf(0) }
    val notification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { if(Build.VERSION.SDK_INT>=33) notification.launch(Manifest.permission.POST_NOTIFICATIONS) }
    val items by vm.library.collectAsStateWithLifecycle()
    MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFFC93636))) {
        Scaffold(topBar={TopAppBar(title={Text(page)})},bottomBar={
            NavigationBar { listOf("下载","媒体库","任务","设置").forEach { label ->
                NavigationBarItem(selected=page==label,onClick={page=label},
                    icon={Icon(if(label=="下载") Icons.Default.Download else if(label=="媒体库") Icons.Default.Collections else Icons.Default.Settings, null)},
                    label={Text(label)})
            } }
        }) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when(page) {
                    "下载" -> DownloadScreen(vm)
                    "媒体库" -> LibraryScreen(vm, items)
                    "任务" -> TasksScreen(vm)
                    else -> SettingsScreen(vm, sessionRevision) { app.session.openLogin(); login=true }
                }
            }
        }
        vm.message?.let { AlertDialog(onDismissRequest=vm::dismiss,title={Text("提示")},text={Text(it)},
            confirmButton={TextButton(onClick=vm::dismiss){Text("确定")}}) }
        if(login) LoginDialog(app.session) { app.session.flush(); login=false; sessionRevision++ }
        vm.viewerId?.let { id -> items.find { it.collection.id==id }?.let { Viewer(vm,it) } }
    }
}
@Composable
private fun DownloadScreen(vm: MainViewModel) {
    var text by rememberSaveable { mutableStateOf("") }
    var force by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item { Text("粘贴分享内容",style=MaterialTheme.typography.titleLarge) }
        item { OutlinedTextField(text,{text=it},Modifier.fillMaxWidth().heightIn(min=140.dp),placeholder={Text("抖音分享文案、短链接或作品链接")}) }
        item { Button(onClick={vm.parse(text)},enabled=text.isNotBlank()&&!vm.busy,modifier=Modifier.fillMaxWidth()) { Text(if(vm.busy) "解析中…" else "解析链接") } }
        items(vm.sources,key={it.url}) { source ->
            Row(verticalAlignment=Alignment.CenterVertically) {
                Checkbox(source.url in vm.chosen,{checked -> vm.chosen=if(checked) vm.chosen+source.url else vm.chosen-source.url})
                Column(Modifier.weight(1f)) { Text(source.url); if(source.duplicate) Text("已在媒体库中",color=MaterialTheme.colorScheme.primary) }
            }
        }
        if(vm.sources.isNotEmpty()) {
            item { Row(verticalAlignment=Alignment.CenterVertically) { Switch(force,{force=it}); Text("强制重新下载") } }
            item { Button(onClick={vm.queue(force)},enabled=vm.chosen.isNotEmpty(),modifier=Modifier.fillMaxWidth()) { Text("下载选中作品（${vm.chosen.size}）") } }
        }
    }
}
@Composable
private fun LibraryScreen(vm: MainViewModel, all: List<CollectionWithAssets>) {
    var search by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("all") }
    var sort by rememberSaveable { mutableStateOf("newest") }
    var trash by rememberSaveable { mutableStateOf(false) }
    var grid by rememberSaveable { mutableStateOf(true) }
    val filtered=LibraryFilter.apply(all,LibraryQuery(search,kind,sort,trash))
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.import(it) }
    Column(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            OutlinedTextField(search,{search=it},Modifier.weight(1f),singleLine=true,label={Text("搜索媒体")})
            IconButton(onClick=vm::scan){Icon(Icons.Default.Refresh,"重新扫描")}
            IconButton(onClick={importer.launch(arrayOf("image/*","video/*","audio/*"))}){Icon(Icons.Default.Add,"导入媒体")}
        }
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            listOf("all" to "全部","image" to "图片","video" to "视频","audio" to "音频").forEach { (value,label) ->
                FilterChip(kind==value,{kind=value},label={Text(label)})
            }
            FilterChip(trash,{trash=!trash; vm.selected=emptySet()},label={Text("回收站")})
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf("newest" to "最新","oldest" to "最早","name" to "名称","size" to "文件数量").forEach { (value,label) -> TextButton(onClick={sort=value}) {Text(if(sort==value) "✓ $label" else label)} }
            TextButton(onClick={grid=!grid}) { Text(if(grid) "列表" else "网格") }
        }
        Text("${filtered.size} 个作品")
        if(vm.selected.isNotEmpty()&&!trash) {
            Row { TextButton(onClick={vm.trash(vm.selected.toList())}){Text("移入回收站（${vm.selected.size}）")}; TextButton(onClick={vm.selected=emptySet()}){Text("取消选择")} }
        }
        if(filtered.isEmpty()) { Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {Text("没有匹配的媒体")} }
        else if(grid) {
            LazyVerticalGrid(GridCells.Adaptive(150.dp),Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                items(filtered,key={it.collection.id}) { item -> MediaCard(vm,item,trash) }
            }
        } else {
            LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {items(filtered,key={it.collection.id}){item -> MediaCard(vm,item,trash)}}
        }
    }
}
@Composable
private fun MediaCard(vm: MainViewModel,item: CollectionWithAssets,trash: Boolean) {
    Card(Modifier.fillMaxWidth().clickable{vm.viewerId=item.collection.id}) {
        Column(Modifier.padding(10.dp)) {
            if(!trash) {
                val cover=item.assets.sortedBy {it.sequence}.firstOrNull {it.kind!="audio"}
                AsyncImage(cover?.thumbnail ?: cover?.path?.let(::File),null,Modifier.fillMaxWidth().height(110.dp),contentScale=ContentScale.Crop)
            }
            Text(item.collection.title,maxLines=2,style=MaterialTheme.typography.titleMedium)
            Text("${item.assets.size} 个文件 · ${item.collection.author ?: "本地文件"}",style=MaterialTheme.typography.bodySmall)
            if(trash) TextButton(onClick={vm.restore(item.collection.id)}){Text("恢复")}
            else Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(item.collection.id in vm.selected,{vm.toggleSelected(item.collection.id)}); Text("选择") }
        }
    }
}
@Composable
private fun Viewer(vm: MainViewModel,item: CollectionWithAssets) {
    var index by remember(item.collection.id) {mutableIntStateOf(0)}
    var rename by remember {mutableStateOf(false)}
    var title by remember {mutableStateOf(item.collection.title)}
    val assets=item.assets.sortedBy {it.sequence}
    val asset=assets.getOrNull(index.coerceAtMost((assets.size-1).coerceAtLeast(0)))
    Dialog(onDismissRequest={vm.viewerId=null},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically){Text(item.collection.title,Modifier.weight(1f),maxLines=2);TextButton(onClick={vm.viewerId=null}){Text("关闭")}}
                if(item.collection.trashedAt!=null) {
                    Text("作品在回收站内，恢复后可预览。导出副本不受影响。")
                    Button(onClick={vm.restore(item.collection.id)}){Text("恢复")}
                } else if(asset!=null) {
                    Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF151515)),contentAlignment=Alignment.Center) {
                        if(asset.kind=="image") AsyncImage(File(asset.path),asset.filename,Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
                        else Player(asset)
                    }
                    if(assets.size>1) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                        TextButton(onClick={index=(index-1+assets.size)%assets.size}){Text("上一项")}
                        Text("${index+1} / ${assets.size}")
                        TextButton(onClick={index=(index+1)%assets.size}){Text("下一项")}
                    }
                    Text(asset.filename,style=MaterialTheme.typography.bodySmall)
                    Text("${asset.mimeType} · ${asset.size/1024} KB · ${asset.width ?: "?"} × ${asset.height ?: "?"}",style=MaterialTheme.typography.bodySmall)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(onClick={vm.open(asset)}){Text("打开")}
                        TextButton(onClick={vm.locate(asset)}){Text("定位")}
                        TextButton(onClick={vm.export(item.collection.id)}){Text("导出")}
                        if(asset.kind=="video") TextButton(onClick={vm.audio(item.collection.id,asset.id)}){Text("提取音频")}
                    }
                    Row { TextButton(onClick={rename=true}){Text("重命名")}; TextButton(onClick={vm.trash(listOf(item.collection.id))}){Text("移入回收站")} }
                }
            }
        }
    }
    if(rename) AlertDialog(onDismissRequest={rename=false},title={Text("重命名作品")},text={OutlinedTextField(title,{title=it},label={Text("名称")})},
        confirmButton={TextButton(onClick={vm.rename(item.collection.id,title);rename=false}){Text("保存")}},dismissButton={TextButton(onClick={rename=false}){Text("取消")}})
}
@Composable
private fun Player(asset: AssetEntity) {
    val context=LocalContext.current
    val player=remember(asset.id) { ExoPlayer.Builder(context).build().apply {setMediaItem(MediaItem.fromUri(Uri.fromFile(File(asset.path))));prepare()} }
    DisposableEffect(player) {onDispose {player.release()}}
    AndroidView(factory={PlayerView(it).apply { this.player=player;useController=true }},Modifier.fillMaxSize())
}
@Composable
private fun TasksScreen(vm: MainViewModel) {
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        if(jobs.isEmpty()) item {Text("暂无任务")}
        items(jobs,key={it.id}){job ->
            Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){
                Text(job.title);Text("${job.status} · ${job.progress}%")
                LinearProgressIndicator(progress={job.progress/100f},modifier=Modifier.fillMaxWidth())
                Text("${job.downloadedBytes/1024} KB / ${if(job.totalBytes>0) (job.totalBytes/1024).toString() else "未知"} KB",style=MaterialTheme.typography.bodySmall)
                job.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                if(job.status in listOf("queued","resolving","downloading","extracting")) TextButton(onClick={vm.cancel(job)}){Text("取消任务")}
                if(job.status in listOf("failed","cancelled","interrupted")) TextButton(onClick={vm.retry(job)}){Text("重试任务")}
            }}
        }
    }
}
@Composable
private fun SettingsScreen(vm: MainViewModel,revision: Int,onLogin:()->Unit) {
    val export by vm.exportTree.collectAsStateWithLifecycle()
    val chooser=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()){vm.chooseTree(it)}
    val loggedIn=remember(revision){vm.app.session.loggedIn()}
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        ListItem(headlineContent={Text("抖音登录")},supportingContent={Text(if(loggedIn) "已登录" else "待登录")},
            trailingContent={Button(onClick=onLogin){Text(if(loggedIn) "重新登录" else "配置登录")}})
        Text("工作目录：${vm.app.storage.root}")
        Text("导出目录：${export ?: "未选择"}")
        Button(onClick={chooser.launch(export?.let(Uri::parse))}){Text("选择导出目录")}
        Text("原文件保存在应用目录，导出后可通过系统文件管理器访问。卸载会删除内部数据，导出副本保留。")
        Button(onClick=vm::scan){Text("重新扫描媒体库")}
        Text(AndroidAudioEngine.version()?.let{"FFmpeg $it · MP3 192 kbps"} ?: "音频引擎不可用")
        Text("Android ${BuildConfig.VERSION_NAME}")
    }
}
@Composable
private fun LoginDialog(session: DouyinSession,onClose:()->Unit) {
    val view=session.loginView
    BackHandler {if(view.canGoBack())view.goBack() else onClose()}
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxSize()) { Column {
            Row{TextButton(onClick={if(view.canGoBack())view.goBack() else onClose()}){Text("返回")}; TextButton(onClick=onClose){Text("完成登录")}}
            AndroidView(factory={(view.parent as? ViewGroup)?.removeView(view);view},Modifier.weight(1f).fillMaxWidth())
        } }
    }
}