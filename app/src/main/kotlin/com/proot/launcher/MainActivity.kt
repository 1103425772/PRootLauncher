package com.proot.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/**
 * v26：MainActivity 从 Miuix（MIUI 风格）迁移到 Material 3，
 * 并新增「外观设置」：模式（跟随系统/浅色/深色）+ 主题色（预设色板 / 自定义 HEX）。
 * 逻辑与 v25 保持一致：
 *  - 下载/导入/解压 rootfs（system tar 优先，TarExtractor 兜底）
 *  - 容器列表（重命名 / X11 / 启动 / 删除）
 *  - 长按容器项 → 高级设置（「运行带有容器检测的二进制」+ 跨架构模拟 QEMU 8 架构）
 *  - 音频诊断（内置 pulseaudio）
 *  - X11：不再内置 termux-x11 APK，未安装时跳浏览器手动下载
 */
class MainActivity : ComponentActivity() {

    lateinit var installer: ProotInstaller

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        installer = ProotInstaller(this)
        File(filesDir, "rootfs").apply { mkdirs() }
        val prefs = getSharedPreferences("proot_launcher", Context.MODE_PRIVATE)
        setContent {
            var themeMode by remember { mutableStateOf(ThemeSettings.mode(prefs)) }
            var seedColor by remember { mutableStateOf(ThemeSettings.seedColor(prefs)) }
            val darkTheme = when (themeMode) {
                ThemeSettings.MODE_LIGHT -> false
                ThemeSettings.MODE_DARK -> true
                else -> isSystemInDarkTheme()
            }
            MaterialTheme(
                colorScheme = ThemeSettings.colorSchemeFromSeed(seedColor, darkTheme),
            ) {
                MainScreen(
                    activity = this@MainActivity,
                    themeMode = themeMode,
                    seedColor = seedColor,
                    onThemeChange = { mode, seed ->
                        themeMode = mode
                        seedColor = seed
                        ThemeSettings.saveMode(prefs, mode)
                        ThemeSettings.saveSeed(prefs, seed)
                    },
                )
            }
        }
    }
}

// ================= 顶层工具函数（与 Java 版一致） =================

private fun rootfsLooksValid(rootfs: File?): Boolean {
    if (rootfs == null || !rootfs.isDirectory) return false
    return File(rootfs, "bin/sh").exists()
        || File(rootfs, "usr/bin/bash").exists()
        || File(rootfs, "etc/os-release").exists()
}

private fun fixExecRecursive(dir: File) {
    dir.listFiles()?.forEach { f ->
        if (f.isDirectory) fixExecRecursive(f)
        else if (f.isFile) {
            f.setExecutable(true, false)
            f.setReadable(true, false)
        }
    }
}

private fun fixExecBits(rootfs: File) {
    for (d in arrayOf("bin", "sbin", "usr/bin", "usr/sbin", "usr/local/bin")) {
        val dir = File(rootfs, d)
        if (dir.isDirectory) fixExecRecursive(dir)
    }
}

/** 系统 toybox tar 优先（符号链接/权限正确），tar.xz 返回 false 走 Java 兜底 */
private fun extractWithSystemTar(archive: File, destDir: File): Boolean {
    val name = archive.name.lowercase()
    var tarBin: String? = null
    for (c in arrayOf("/system/bin/tar", "/system/xbin/tar", "/system/bin/toybox")) {
        if (File(c).exists()) { tarBin = c; break }
    }
    if (tarBin == null) return false
    val toybox = tarBin.endsWith("toybox")
    val cmd = ArrayList<String>()
    if (toybox) { cmd.add(tarBin); cmd.add("tar") } else cmd.add(tarBin)
    when {
        name.endsWith(".tar.gz") || name.endsWith(".tgz") -> cmd.add("xzf")
        name.endsWith(".tar") -> cmd.add("xf")
        name.endsWith(".tar.xz") || name.endsWith(".txz") -> return false
        else -> return false
    }
    cmd.add(archive.absolutePath)
    cmd.add("-C")
    cmd.add(destDir.absolutePath)
    try {
        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(true)
        val p = pb.start()
        val out = StringBuilder()
        p.inputStream.bufferedReader().use { r ->
            var line: String? = r.readLine()
            while (line != null) { out.append(line).append('\n'); line = r.readLine() }
        }
        val code = p.waitFor()
        if (code != 0) {
            // toybox tar 常因 utime/chown 非致命错误返回非 0，rootfs 可用即成功
            if (rootfsLooksValid(destDir)) {
                fixExecBits(destDir)
                return true
            }
            return false
        }
        fixExecBits(destDir)
        return true
    } catch (e: Exception) {
        return false
    }
}

private fun verifyArchive(activity: MainActivity, file: File): String? {
    if (!file.exists()) return activity.getString(R.string.err_file_not_found)
    val size = file.length()
    if (size < Config.MIN_ARCHIVE_SIZE) {
        return activity.getString(R.string.err_file_too_small, size)
    }
    try {
        BufferedInputStream(FileInputStream(file)).use { raw ->
            raw.mark(8)
            val magic = ByteArray(6)
            val n = raw.read(magic)
            raw.reset()
            if (n < 2) return activity.getString(R.string.err_bad_header)
            val isGz = (magic[0].toInt() and 0xFF) == 0x1F && (magic[1].toInt() and 0xFF) == 0x8B
            val isXz = n >= 6 && (magic[0].toInt() and 0xFF) == 0xFD && (magic[1].toInt() and 0xFF) == 0x37
                && (magic[2].toInt() and 0xFF) == 0x7A && (magic[3].toInt() and 0xFF) == 0x58
                && (magic[4].toInt() and 0xFF) == 0x5A && (magic[5].toInt() and 0xFF) == 0x00
            if (!isGz && !isXz) {
                return activity.getString(R.string.err_not_targz,
                    String.format("%02X %02X", magic[0].toInt() and 0xFF, magic[1].toInt() and 0xFF))
            }
            try {
                val dec: InputStream = if (isGz) GZIPInputStream(raw) else XZInputStream(raw)
                dec.use {
                    val tarHeader = ByteArray(512)
                    var tn = 0
                    while (tn < tarHeader.size) {
                        val r = it.read(tarHeader, tn, tarHeader.size - tn)
                        if (r < 0) break
                        tn += r
                    }
                    if (tn < 262) return activity.getString(R.string.err_archive_too_short)
                    if (tarHeader[257] != 'u'.code.toByte() || tarHeader[258] != 's'.code.toByte()
                        || tarHeader[259] != 't'.code.toByte() || tarHeader[260] != 'a'.code.toByte()
                        || tarHeader[261] != 'r'.code.toByte()) {
                        return activity.getString(R.string.err_not_tar)
                    }
                }
            } catch (e: Exception) {
                return activity.getString(R.string.err_verify_decompress, e.message)
            }
        }
    } catch (e: Exception) {
        return activity.getString(R.string.err_read_file, e.message)
    }
    return null
}

private fun calcDirSize(dir: File): Long {
    var size = 0L
    dir.listFiles()?.forEach { f -> size += if (f.isDirectory) calcDirSize(f) else f.length() }
    return size
}

private fun formatSizeLocal(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
}

private fun deleteDir(dir: File) {
    dir.listFiles()?.forEach { f -> if (f.isDirectory) deleteDir(f) else f.delete() }
    dir.delete()
}

private fun renameSimConfigKey(activity: MainActivity, oldName: String, newName: String) {
    try {
        val conf = SimConfig.confFile(activity)
        if (!conf.exists()) return
        val lines = java.nio.file.Files.readAllLines(conf.toPath(), java.nio.charset.StandardCharsets.UTF_8)
        val out = ArrayList<String>()
        for (line in lines) {
            var l = line
            if (l.startsWith(oldName + "=") || l.startsWith(oldName + ".qemu=")) {
                l = l.replace(oldName, newName)
            }
            out.add(l)
        }
        java.nio.file.Files.write(conf.toPath(), out)
    } catch (e: Exception) { /* ignore */ }
}

private fun dirSizeCached(dir: File, cache: ConcurrentHashMap<String, LongArray>): Long {
    val key = dir.absolutePath
    val now = System.currentTimeMillis()
    val cached = cache[key]
    if (cached != null && now - cached[1] < Config.DIR_SIZE_CACHE_TTL_MS) return cached[0]
    return cache.compute(key) { _, old ->
        val t = System.currentTimeMillis()
        if (old != null && t - old[1] < Config.DIR_SIZE_CACHE_TTL_MS) old
        else longArrayOf(calcDirSize(dir), t)
    }!![0]
}

private fun refreshList(rootfsDir: File, cache: ConcurrentHashMap<String, LongArray>,
                        set: (List<File>) -> Unit) {
    val dirs = rootfsDir.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: emptyList()
    // 预热大小缓存（后台线程不用，这里直接算）
    dirs.forEach { dirSizeCached(it, cache) }
    set(dirs)
}

private suspend fun downloadArchive(urlStr: String, cacheDir: File,
                                    onProgress: (Int) -> Unit): File? {
    var conn: HttpURLConnection? = null
    var downloadFile: File? = null
    try {
        val url = URL(urlStr)
        conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 120000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "PRootLauncher/1.0")
        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK) return null
        val contentLength = conn.contentLength
        onProgress(0)
        var fileName = urlStr.substring(urlStr.lastIndexOf('/') + 1)
        if (fileName.isEmpty() || !fileName.contains(".")) fileName = "rootfs.tar.xz"
        fileName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        if (fileName.isEmpty()) fileName = "rootfs.tar.xz"
        downloadFile = File(cacheDir, fileName)
        conn.inputStream.use { inp ->
            BufferedInputStream(inp, 65536).use { bin ->
                FileOutputStream(downloadFile).use { out ->
                    val buf = ByteArray(65536)
                    var total = 0L
                    var len: Int
                    while (bin.read(buf).also { len = it } > 0) {
                        out.write(buf, 0, len)
                        total += len
                        if (contentLength > 0) onProgress((total * 100 / contentLength).toInt())
                    }
                }
            }
        }
        return downloadFile
    } catch (e: Exception) {
        downloadFile?.delete()
        return null
    } finally {
        conn?.disconnect()
    }
}

private suspend fun extractArchive(activity: MainActivity, archive: File,
                                   rootfsDir: File, cache: ConcurrentHashMap<String, LongArray>,
                                   onProgress: (Int) -> Unit): String? {
    var name = archive.name
    if (name.endsWith(".tar.xz")) name = name.dropLast(7)
    else if (name.endsWith(".tar.gz")) name = name.dropLast(7)
    else if (name.endsWith(".tgz")) name = name.dropLast(4)
    else if (name.endsWith(".txz")) name = name.dropLast(4)
    else if (name.endsWith(".tar")) name = name.dropLast(4)
    name = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    if (name.isEmpty()) name = "rootfs"
    var destDir = File(rootfsDir, name)
    if (destDir.exists()) {
        destDir = File(rootfsDir, name + "_" + System.currentTimeMillis())
    }
    destDir.mkdirs()
    try {
        var ok = extractWithSystemTar(archive, destDir)
        if (!ok) {
            try {
                TarExtractor.extract(archive, destDir, object : TarExtractor.ProgressListener {
                    override fun onProgress(extracted: Long, total: Long) {
                        if (total > 0) onProgress((extracted * 100 / total).toInt())
                    }
                    override fun onMessage(msg: String) { /* log */ }
                })
            } catch (te: Exception) { /* 保留部分解压结果 */ }
        }
        if (!rootfsLooksValid(destDir)) {
            throw IllegalStateException(activity.getString(R.string.err_rootfs_incomplete))
        }
        cache[destDir.absolutePath] = longArrayOf(calcDirSize(destDir), System.currentTimeMillis())
        archive.delete()
        return name
    } catch (e: Exception) {
        if (!rootfsLooksValid(destDir)) deleteDir(destDir)
        archive.delete()
        return null
    }
}

private suspend fun importAndExtract(activity: MainActivity, uri: Uri,
                                     rootfsDir: File, cache: ConcurrentHashMap<String, LongArray>,
                                     onProgress: (Int) -> Unit): String? {
    val outFile: File
    try {
        var fname = "imported_rootfs.tar.xz"
        activity.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) fname = c.getString(idx)
            }
        }
        fname = fname.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        if (fname.isEmpty()) fname = "imported_rootfs.tar.xz"
        outFile = File(activity.cacheDir, fname)
        activity.contentResolver.openInputStream(uri)?.use { inp ->
            FileOutputStream(outFile).use { out ->
                val buf = ByteArray(65536)
                var len: Int
                while (inp.read(buf).also { len = it } > 0) out.write(buf, 0, len)
            }
        }
    } catch (e: Exception) {
        return null
    }
    return extractArchive(activity, outFile, rootfsDir, cache, onProgress)
}

// ================= 对话框状态 =================

private sealed class Dlg
private data class RenameDlg(val dir: File) : Dlg()
private data class DeleteDlg(val dir: File) : Dlg()
private data class ChoiceDlg(val dir: File) : Dlg()
private data class X11CmdDlg(val dir: File) : Dlg()
private data class SecretDlg(val dir: File) : Dlg()
private data class AudioDlg(val report: String) : Dlg()

// ================= Compose UI =================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    activity: MainActivity,
    themeMode: String,
    seedColor: Color,
    onThemeChange: (mode: String, seed: Color) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val prefs = remember { activity.getSharedPreferences("proot_launcher", Context.MODE_PRIVATE) }

    // ---- 状态 ----
    val rootfsDir = remember { File(activity.filesDir, "rootfs").apply { mkdirs() } }
    var urlText by remember {
        val abi = Build.SUPPORTED_ABIS?.firstOrNull() ?: ""
        val defaultUrl = if (abi.contains("arm64") || abi.contains("aarch64"))
            "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz"
        else if (abi.contains("arm") || abi.contains("armeabi"))
            "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/armv7/alpine-minirootfs-3.20.3-armv7.tar.gz"
        else
            activity.getString(R.string.default_url)
        mutableStateOf(prefs.getString("last_url", defaultUrl) ?: defaultUrl)
    }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("") }
    var rootfsList by remember { mutableStateOf<List<File>>(emptyList()) }
    val dirSizeCache = remember { ConcurrentHashMap<String, LongArray>() }

    // ---- 对话框状态 ----
    var dialog by remember { mutableStateOf<Dlg?>(null) }
    var showThemeDialog by remember { mutableStateOf(false) }

    // ---- 文件选择 ----
    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            busy = true
            progress = 0
            status = activity.getString(R.string.msg_extracting)
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    importAndExtract(activity, uri, rootfsDir, dirSizeCache) { p ->
                        progress = p
                        status = activity.getString(R.string.msg_extracting) + " $p%"
                    }
                }
                busy = false
                progress = 0
                if (result == null) {
                    status = activity.getString(R.string.msg_import_failed, "uri 读取失败")
                } else {
                    status = activity.getString(R.string.msg_done_name, result)
                    refreshList(rootfsDir, dirSizeCache) { rootfsList = it }
                }
            }
        }
    }

    fun startDownload() {
        val url = urlText.trim()
        if (url.isEmpty()) {
            Toast.makeText(activity, activity.getString(R.string.msg_enter_url), Toast.LENGTH_SHORT).show()
            return
        }
        prefs.edit().putString("last_url", url).apply()
        busy = true
        progress = 0
        status = activity.getString(R.string.msg_downloading)
        scope.launch {
            val archive = withContext(Dispatchers.IO) {
                downloadArchive(url, activity.cacheDir) { p ->
                    progress = p
                    status = activity.getString(R.string.msg_downloading) + " $p%"
                }
            }
            if (archive == null) {
                busy = false; progress = 0
                status = activity.getString(R.string.msg_download_failed, "网络错误")
                return@launch
            }
            val verifyError = withContext(Dispatchers.IO) { verifyArchive(activity, archive) }
            if (verifyError != null) {
                busy = false; progress = 0
                status = activity.getString(R.string.msg_download_invalid, verifyError)
                archive.delete()
                return@launch
            }
            status = activity.getString(R.string.msg_extracting)
            val name = withContext(Dispatchers.IO) {
                extractArchive(activity, archive, rootfsDir, dirSizeCache) { p ->
                    progress = p
                    status = activity.getString(R.string.msg_extracting) + " $p%"
                }
            }
            busy = false; progress = 0
            if (name == null) {
                status = activity.getString(R.string.msg_extract_failed, "解压失败")
            } else {
                status = activity.getString(R.string.msg_done_name, name)
                refreshList(rootfsDir, dirSizeCache) { rootfsList = it }
            }
        }
    }

    // 启动时：pulseaudio 后台自拉起 + proot 运行时准备
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            AudioSupport.ensureStarted(activity)
            if (!activity.installer.isInstalled()) {
                try {
                    activity.installer.prepareRuntime()
                } catch (e: Exception) {
                    status = activity.getString(R.string.msg_proot_init_failed,
                        activity.getString(R.string.msg_check_storage))
                }
            }
        }
        refreshList(rootfsDir, dirSizeCache) { rootfsList = it }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PRoot Launcher · v26") },
                actions = {
                    TextButton(onClick = { showThemeDialog = true }) {
                        Text("外观")
                    }
                },
            )
        }
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------- 下载区 ----------
            item {
                Card(shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = urlText,
                            onValueChange = { urlText = it },
                            label = { Text(activity.getString(R.string.hint_url)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { startDownload() }, modifier = Modifier.weight(1f), enabled = !busy) {
                                Text(activity.getString(R.string.btn_download))
                            }
                            TextButton(
                                onClick = {
                                    pickLauncher.launch(arrayOf(
                                        "application/x-xz", "application/gzip",
                                        "application/x-tar", "application/octet-stream"))
                                },
                                enabled = !busy,
                            ) {
                                Text(activity.getString(R.string.btn_select))
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        if (busy) {
                            if (progress > 0) {
                                LinearProgressIndicator(
                                    progress = { progress / 100f },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                        if (status.isNotEmpty()) {
                            Text(status, maxLines = 6)
                        }
                    }
                }
            }

            // ---------- 音频诊断 ----------
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            Toast.makeText(activity, "正在启动 pulseaudio…", Toast.LENGTH_SHORT).show()
                            scope.launch {
                                val report = withContext(Dispatchers.IO) {
                                    AudioSupport.forceRestartAndDiag(activity)
                                }
                                dialog = AudioDlg(report)
                            }
                        },
                    ) {
                        Text("音频诊断（pulseaudio）")
                    }
                    Spacer(Modifier.weight(1f))
                }
            }

            // ---------- 容器列表 ----------
            item {
                Text(
                    activity.getString(R.string.title_available),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (rootfsList.isEmpty()) {
                item {
                    Card(shape = RoundedCornerShape(20.dp)) {
                        Text(
                            activity.getString(R.string.msg_no_rootfs),
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            } else {
                items(rootfsList, key = { it.absolutePath }) { dir ->
                    val size = dirSizeCached(dir, dirSizeCache)
                    // 长按容器项 → 高级设置（v24 神秘入口）
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { /* 单击无操作，长按进入高级设置 */ },
                                onLongClick = { dialog = SecretDlg(dir) },
                            ),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(dir.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        formatSizeLocal(size),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { dialog = RenameDlg(dir) }) {
                                    Text(activity.getString(R.string.btn_rename))
                                }
                                TextButton(onClick = { startX11(activity, scope, dir, null) { _, _ -> } }) {
                                    Text("X11")
                                }
                                Spacer(Modifier.weight(1f))
                                Button(onClick = { dialog = ChoiceDlg(dir) }) {
                                    Text(activity.getString(R.string.btn_launch))
                                }
                                Spacer(Modifier.width(4.dp))
                                TextButton(onClick = { dialog = DeleteDlg(dir) }) {
                                    Text(activity.getString(R.string.btn_delete))
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "长按此容器进入高级设置",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------- 外观设置 ----------
    if (showThemeDialog) {
        ThemeDialog(
            currentMode = themeMode,
            currentSeed = seedColor,
            onApply = { mode, seed -> onThemeChange(mode, seed) },
            onDismiss = { showThemeDialog = false },
        )
    }

    // ---------- 对话框 ----------
    when (val d = dialog) {
        is RenameDlg -> RenameDialog(activity, d.dir,
            onConfirm = { newName ->
                dialog = null
                scope.launch {
                    busy = true
                    val ok = withContext(Dispatchers.IO) {
                        val old = d.dir.name
                        val ok = d.dir.renameTo(File(rootfsDir, newName))
                        if (ok) {
                            renameSimConfigKey(activity, old, newName)
                            dirSizeCache.remove(d.dir.absolutePath)
                        }
                        ok
                    }
                    busy = false
                    Toast.makeText(activity,
                        if (ok) activity.getString(R.string.msg_renamed, newName)
                        else activity.getString(R.string.msg_rename_failed, "renameTo 返回 false"),
                        if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                    refreshList(rootfsDir, dirSizeCache) { rootfsList = it }
                }
            },
            onDismiss = { dialog = null })
        is DeleteDlg -> DeleteDialog(activity, d.dir,
            onConfirm = {
                dialog = null
                scope.launch {
                    busy = true
                    withContext(Dispatchers.IO) {
                        deleteDir(d.dir)
                        dirSizeCache.remove(d.dir.absolutePath)
                    }
                    busy = false
                    Toast.makeText(activity, activity.getString(R.string.msg_deleted), Toast.LENGTH_SHORT).show()
                    refreshList(rootfsDir, dirSizeCache) { rootfsList = it }
                }
            },
            onDismiss = { dialog = null })
        is ChoiceDlg -> ChoiceDialog(activity, d.dir,
            onWeb = {
                dialog = null
                AudioSupport.ensureStarted(activity)
                startActivity(activity, WebTerminalActivity::class.java, d.dir)
            },
            onShell = {
                dialog = null
                AudioSupport.ensureStarted(activity)
                val intent = Intent(activity, TerminalActivity::class.java).apply {
                    putExtra("rootfs_path", d.dir.absolutePath)
                    putExtra("rootfs_name", d.dir.name)
                    putExtra("real_sim", if (SimConfig.isRealSim(activity, d.dir.name)) "1" else "0")
                }
                activity.startActivity(intent)
            },
            onX11 = { dialog = X11CmdDlg(d.dir) },
            onDismiss = { dialog = null })
        is X11CmdDlg -> X11CommandDialog(activity, d.dir,
            onRun = { cmd ->
                dialog = null
                startX11(activity, scope, d.dir, cmd) { _, _ -> }
            },
            onDismiss = { dialog = null })
        is SecretDlg -> SecretDialog(activity, d.dir,
            onDismiss = { dialog = null })
        is AudioDlg -> AudioDiagDialog(activity, d.report,
            onDismiss = { dialog = null })
        null -> {}
    }
}

private fun startActivity(activity: MainActivity, cls: Class<*>, dir: File) {
    val intent = Intent(activity, cls).apply {
        putExtra("rootfs_path", dir.absolutePath)
        putExtra("rootfs_name", dir.name)
    }
    activity.startActivity(intent)
}

private fun startX11(activity: MainActivity, scope: CoroutineScope,
                     dir: File, command: String?, onFinished: (Boolean, String?) -> Unit) {
    scope.launch {
        withContext(Dispatchers.IO) {
            try {
                if (!X11Support.isTermuxX11Installed(activity)) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, activity.getString(R.string.x11_browser_hint),
                            Toast.LENGTH_LONG).show()
                    }
                    X11Support.installTermuxX11(activity)
                    return@withContext
                }
                X11Support.setX11Enabled(activity, true)
                val xkb = File(dir, "usr/share/X11/xkb")
                val xkbRoot = if (xkb.isDirectory) xkb.absolutePath else null
                if (xkbRoot == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity,
                            "[提示] rootfs 内缺 usr/share/X11/xkb，X server 可能拒绝启动；请在 rootfs 里装 xkb-data（Debian: apt install xkb-data）",
                            Toast.LENGTH_LONG).show()
                    }
                }
                val xUp = X11Support.startXServerViaLoader(activity, xkbRoot)
                if (xUp) X11Support.launchTermuxX11(activity)
                withContext(Dispatchers.Main) {
                    if (!xUp) {
                        val log = X11Support.getLoaderLog().trim()
                        Toast.makeText(activity,
                            "X server 启动失败:\n" + (if (log.isEmpty()) "(无输出)" else log),
                            Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(activity, activity.getString(R.string.x11_started),
                            Toast.LENGTH_SHORT).show()
                        if (command != null && command.isNotBlank()) {
                            val intent = Intent(activity, TerminalActivity::class.java).apply {
                                putExtra("rootfs_path", dir.absolutePath)
                                putExtra("rootfs_name", dir.name)
                                putExtra("x11_command", command.trim())
                            }
                            activity.startActivity(intent)
                        }
                    }
                    onFinished(xUp, command)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity,
                        activity.getString(R.string.x11_install_failed, e.message),
                        Toast.LENGTH_LONG).show()
                    onFinished(false, null)
                }
            }
        }
    }
}

// ---------- 对话框组件 ----------

/** Material 3 风格对话框外壳：Compose Dialog + Card */
@Composable
private fun BaseDialog(title: String?, summary: String? = null,
                       onDismiss: () -> Unit,
                       content: @Composable ColumnScope.() -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                if (title != null) Text(title, style = MaterialTheme.typography.titleLarge)
                if (summary != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(summary, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(12.dp))
                content()
            }
        }
    }
}

@Composable
private fun RenameDialog(activity: MainActivity, dir: File,
                         onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var nameText by remember(dir) { mutableStateOf(dir.name) }
    BaseDialog(
        title = activity.getString(R.string.dialog_rename_title),
        onDismiss = onDismiss,
    ) {
        OutlinedTextField(
            value = nameText,
            onValueChange = { nameText = it },
            label = { Text(activity.getString(R.string.dialog_rename_hint)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onDismiss) { Text(activity.getString(R.string.btn_cancel)) }
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                var newName = nameText.trim()
                if (newName.isEmpty() || newName == dir.name) { onDismiss(); return@Button }
                newName = newName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                if (newName.isEmpty()) { onDismiss(); return@Button }
                onConfirm(newName)
            }) { Text(activity.getString(R.string.btn_rename)) }
        }
    }
}

@Composable
private fun DeleteDialog(activity: MainActivity, dir: File,
                         onConfirm: () -> Unit, onDismiss: () -> Unit) {
    BaseDialog(
        title = activity.getString(R.string.dialog_delete_title),
        summary = activity.getString(R.string.dialog_delete_message, dir.name),
        onDismiss = onDismiss,
    ) {
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onDismiss) { Text(activity.getString(R.string.btn_cancel)) }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onConfirm) { Text(activity.getString(R.string.btn_delete)) }
        }
    }
}

@Composable
private fun ChoiceDialog(activity: MainActivity, dir: File,
                         onWeb: () -> Unit, onShell: () -> Unit,
                         onX11: () -> Unit, onDismiss: () -> Unit) {
    BaseDialog(
        title = dir.name,
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Card(onClick = onWeb, modifier = Modifier.fillMaxWidth()) {
                Text(
                    activity.getString(R.string.choice_web_terminal),
                    modifier = Modifier.padding(16.dp),
                )
            }
            Card(onClick = onShell, modifier = Modifier.fillMaxWidth()) {
                Text(
                    activity.getString(R.string.choice_rootfs_shell),
                    modifier = Modifier.padding(16.dp),
                )
            }
            Card(onClick = onX11, modifier = Modifier.fillMaxWidth()) {
                Text(
                    activity.getString(R.string.choice_x11_run),
                    modifier = Modifier.padding(16.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) { Text(activity.getString(R.string.btn_cancel)) }
            }
        }
    }
}

@Composable
private fun X11CommandDialog(activity: MainActivity, dir: File,
                             onRun: (String) -> Unit, onDismiss: () -> Unit) {
    var cmdText by remember { mutableStateOf("xterm") }
    BaseDialog(
        title = activity.getString(R.string.x11_dialog_title),
        onDismiss = onDismiss,
    ) {
        OutlinedTextField(
            value = cmdText,
            onValueChange = { cmdText = it },
            label = { Text(activity.getString(R.string.x11_dialog_hint)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onDismiss) { Text(activity.getString(R.string.btn_cancel)) }
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                val cmd = cmdText.trim()
                if (cmd.isEmpty()) {
                    Toast.makeText(activity, activity.getString(R.string.x11_dialog_hint), Toast.LENGTH_SHORT).show()
                    return@Button
                }
                onRun(cmd)
            }) { Text(activity.getString(R.string.btn_x11_run)) }
        }
    }
}

@Composable
private fun AudioDiagDialog(activity: MainActivity, report: String, onDismiss: () -> Unit) {
    BaseDialog(
        title = "音频诊断",
        onDismiss = onDismiss,
    ) {
        Text(report)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onDismiss) { Text("关闭") }
        }
    }
}

/** v24 神秘入口（v25：改名「运行带有容器检测的二进制」+ QEMU 8 架构） */
@Composable
private fun SecretDialog(activity: MainActivity, dir: File, onDismiss: () -> Unit) {
    val name = dir.name
    val nativeArch = SimConfig.getNativeArch()
    var realSim by remember { mutableStateOf(SimConfig.isRealSim(activity, name)) }
    var qemuArch by remember { mutableStateOf(SimConfig.getQemuArch(activity, name)) }

    val qemuOptions = remember(nativeArch) {
        listOf(
            SimConfig.QEMU_NONE to "不使用跨架构模拟",
            SimConfig.QEMU_X86_64 to "x86_64 模拟（内置 qemu-user，离线）",
            SimConfig.QEMU_I386 to "i386 模拟（内置 qemu-user，离线）",
            SimConfig.QEMU_ARM to "ARM 32位模拟（内置 qemu-user，离线）",
            SimConfig.QEMU_AARCH64 to "ARM64 模拟（内置 qemu-user，离线）",
            SimConfig.QEMU_M68K to "m68k 模拟（内置 qemu-user，离线）",
            SimConfig.QEMU_PPC to "PPC 32位模拟（内置 qemu-user，离线）",
            SimConfig.QEMU_PPC64 to "PPC64 模拟（内置 qemu-user，离线）",
            SimConfig.QEMU_RISCV32 to "RISC-V 32 模拟（内置 qemu-user，离线）",
            SimConfig.QEMU_RISCV64 to "RISC-V 64 模拟（内置 qemu-user，离线）",
        ).filter { it.first == SimConfig.QEMU_NONE || it.first != nativeArch }
    }

    BaseDialog(
        title = "高级设置 · $name",
        summary = "长按容器项进入",
        onDismiss = onDismiss,
    ) {
        // 运行带有容器检测的二进制（真实模拟 → Tiny Container）
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(activity.getString(R.string.real_sim_label))
                Spacer(Modifier.height(2.dp))
                Text(
                    activity.getString(R.string.real_sim_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = realSim, onCheckedChange = { realSim = it })
        }
        Spacer(Modifier.height(12.dp))

        // 跨架构模拟（QEMU）
        Text(activity.getString(R.string.qemu_title), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(2.dp))
        Text(
            "模拟器必须与 rootfs 架构一致（例如 alpine x86_64 要选 x86_64）。"
                + "启动时会自动检测 rootfs 架构，不一致会自动纠正。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .heightIn(max = 320.dp),
        ) {
            qemuOptions.forEach { (arch, label) ->
                Card(
                    onClick = { qemuArch = arch },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                        Text(label, Modifier.weight(1f))
                        Checkbox(
                            checked = qemuArch == arch,
                            onCheckedChange = { qemuArch = arch },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onDismiss) { Text(activity.getString(R.string.btn_cancel)) }
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                SimConfig.setRealSim(activity, name, realSim)
                SimConfig.setQemuArch(activity, name, qemuArch)
                val msg = (if (realSim) activity.getString(R.string.real_sim_on)
                    else activity.getString(R.string.real_sim_off)) + "\n" +
                    (if (SimConfig.QEMU_NONE == qemuArch) "跨架构模拟：未开启"
                        else "跨架构模拟：$qemuArch（启动时自动引导 qemu 模拟器）")
                Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                onDismiss()
            }) { Text("确定") }
        }
    }
}

// ---------- 外观设置（Material 3 主题） ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeDialog(
    currentMode: String,
    currentSeed: Color,
    onApply: (mode: String, seed: Color) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf(currentMode) }
    var seed by remember { mutableStateOf(currentSeed) }
    var hexText by remember { mutableStateOf(ThemeSettings.toHex(currentSeed)) }
    var hexError by remember { mutableStateOf(false) }

    fun applyHex() {
        val c = ThemeSettings.parseHex(hexText)
        if (c == null) {
            hexError = true
        } else {
            hexError = false
            seed = c
            hexText = ThemeSettings.toHex(c)
            onApply(mode, c)
        }
    }

    BaseDialog(
        title = "外观设置",
        onDismiss = onDismiss,
    ) {
        // ---- 模式 ----
        Text("模式", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == ThemeSettings.MODE_SYSTEM,
                onClick = { mode = ThemeSettings.MODE_SYSTEM },
                label = { Text("跟随系统") },
            )
            FilterChip(
                selected = mode == ThemeSettings.MODE_LIGHT,
                onClick = { mode = ThemeSettings.MODE_LIGHT },
                label = { Text("浅色") },
            )
            FilterChip(
                selected = mode == ThemeSettings.MODE_DARK,
                onClick = { mode = ThemeSettings.MODE_DARK },
                label = { Text("深色") },
            )
        }
        Spacer(Modifier.height(16.dp))

        // ---- 主题色 ----
        Text("主题色", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(6),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 216.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ThemeSettings.PRESETS.size) { i ->
                val (_, c) = ThemeSettings.PRESETS[i]
                ColorSwatch(
                    color = c,
                    selected = ThemeSettings.toHex(seed) == ThemeSettings.toHex(c),
                    onClick = {
                        seed = c
                        hexText = ThemeSettings.toHex(c)
                        hexError = false
                    },
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- 自定义 HEX ----
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = hexText,
                onValueChange = {
                    hexText = it
                    hexError = false
                },
                label = { Text("自定义颜色（HEX）") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                isError = hexError,
                supportingText = if (hexError) {
                    { Text("无效颜色，请使用 #RRGGBB 格式") }
                } else null,
            )
            Button(onClick = { applyHex() }) {
                Text("应用")
            }
        }
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onDismiss) { Text("取消") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                applyHex()
                if (!hexError) onDismiss()
            }) { Text("完成") }
        }
    }
}

@Composable
private fun ColorSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(color)
            .border(2.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            val checkColor = if (color.luminance() > 0.5f) Color(0xFF1A1A1A) else Color.White
            Text("✓", color = checkColor, style = MaterialTheme.typography.titleMedium)
        }
    }
}
