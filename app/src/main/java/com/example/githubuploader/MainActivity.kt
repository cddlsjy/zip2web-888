package com.example.githubuploader

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

// ==================== GitHub API 接口定义 ====================
interface GithubApi {
    @GET("repos/{owner}/{repo}/contents/{path}")
    suspend fun getFile(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path") path: String,
        @Query("ref") branch: String,
        @Header("Authorization") token: String
    ): Response<GithubContentResponse>

    @PUT("repos/{owner}/{repo}/contents/{path}")
    suspend fun createOrUpdateFile(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path") path: String,
        @Header("Authorization") token: String,
        @Body body: CreateFileRequest
    ): Response<GithubFileResponse>

    data class CreateFileRequest(
        val message: String,
        val content: String,
        val sha: String? = null,
        val branch: String
    )

    data class GithubContentResponse(
        val sha: String,
        val content: String? = null
    )

    data class GithubFileResponse(
        val content: ContentInfo,
        val commit: CommitInfo? = null
    )

    data class ContentInfo(val sha: String)
    data class CommitInfo(val sha: String, val message: String)
}

// ==================== 内置 YML 文件内容 ====================
// 暂时注释掉这些字符串以避免编译错误
// 实际使用时可以取消注释
val DEFAULT_UNPACK_YML = ""
val DEFAULT_BUILD_YML = ""

// ==================== 主题颜色 ====================
private val Purple80 = Color(0xFFD0BCFF)
private val PurpleGrey80 = Color(0xFFCCC2DC)
private val Pink80 = Color(0xFFEFB8C8)
private val Purple40 = Color(0xFF6650a4)
private val PurpleGrey40 = Color(0xFF625b71)
private val Pink40 = Color(0xFF7D5260)
private val SuccessGreen = Color(0xFF4CAF50)
private val ErrorRed = Color(0xFFE53935)
private val WarningOrange = Color(0xFFFF9800)
private val InfoBlue = Color(0xFF2196F3)

// ==================== 主活动 ====================
class MainActivity : ComponentActivity() {
    private lateinit var api: GithubApi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化 Retrofit
        val client = OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
            .build()

        val gson = GsonBuilder().create()
        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.github.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

        api = retrofit.create(GithubApi::class.java)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Purple80,
                    secondary = PurpleGrey80,
                    tertiary = Pink80
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    UploadScreen(api = api, context = this)
                }
            }
        }
    }
}

// ==================== 主界面 Composable ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadScreen(api: GithubApi, context: MainActivity) {
    var repoUrl by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("main") }
    var uploadDefaultUnpack by remember { mutableStateOf(true) }
    var uploadDefaultBuild by remember { mutableStateOf(true) }
    val customYmlFiles = remember { mutableStateListOf<Pair<Uri, String>>() }
    var zipFileUri by remember { mutableStateOf<Uri?>(null) }
    var zipFileName by remember { mutableStateOf("") }
    var logs by remember { mutableStateOf(listOf<String>()) }
    var isUploading by remember { mutableStateOf(false) }
    var tokenVisible by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    // 保存/加载 token 和 repo 的 SharedPreferences
    val prefs = context.getSharedPreferences("github_uploader", Context.MODE_PRIVATE)
    LaunchedEffect(Unit) {
        token = prefs.getString("token", "") ?: ""
        repoUrl = prefs.getString("repo_url", "") ?: ""
        branch = prefs.getString("branch", "main") ?: "main"
        uploadDefaultUnpack = prefs.getBoolean("upload_default_unpack", true)
        uploadDefaultBuild = prefs.getBoolean("upload_default_build", true)
    }

    fun savePrefs() {
        prefs.edit()
            .putString("token", token)
            .putString("repo_url", repoUrl)
            .putString("branch", branch)
            .putBoolean("upload_default_unpack", uploadDefaultUnpack)
            .putBoolean("upload_default_build", uploadDefaultBuild)
            .apply()
    }

    // 添加带时间戳的日志
    fun addLog(msg: String) {
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val timestamp = timeFormat.format(Date())
        logs = logs + "[$timestamp] $msg"
    }

    // 从 Uri 获取文件名
    fun getFileNameFromUri(uri: Uri): String {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                cursor.getString(nameIndex)
            } else {
                uri.lastPathSegment ?: "unknown"
            }
        } ?: uri.lastPathSegment ?: "unknown"
    }

    // 读取文件内容为 Base64
    suspend fun readFileAsBase64(uri: Uri): String = withContext(Dispatchers.IO) {
        val inputStream: InputStream = context.contentResolver.openInputStream(uri)
            ?: throw Exception("无法打开文件")
        val bytes = inputStream.use { it.readBytes() }
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    // 从仓库地址解析 owner/repo
    fun parseRepo(url: String): Pair<String, String>? {
        val patterns = listOf(
            Regex("github\\.com[:/]([^/]+)/([^/.]+)"),
            Regex("https://github\\.com/([^/]+)/([^/.]+)")
        )
        for (pattern in patterns) {
            val matcher = pattern.find(url)
            if (matcher != null) {
                return matcher.groupValues[1] to matcher.groupValues[2]
            }
        }
        return null
    }

    // 获取文件 sha（用于更新），不存在返回 null
    suspend fun getFileSha(
        api: GithubApi,
        owner: String,
        repo: String,
        path: String,
        token: String,
        branch: String
    ): String? {
        return try {
            val response = api.getFile(owner, repo, path, branch, "token $token")
            if (response.isSuccessful) response.body()?.sha else null
        } catch (e: Exception) {
            null
        }
    }

    // 通用文件上传
    suspend fun uploadFile(
        api: GithubApi,
        owner: String,
        repo: String,
        remotePath: String,
        contentBase64: String,
        token: String,
        branch: String,
        addLog: (String) -> Unit
    ) {
        val sha = getFileSha(api, owner, repo, remotePath, token, branch)
        val body = GithubApi.CreateFileRequest(
            message = "Upload $remotePath",
            content = contentBase64,
            sha = sha,
            branch = branch
        )
        val response = api.createOrUpdateFile(owner, repo, remotePath, "token $token", body)
        if (response.isSuccessful) {
            addLog("✓ $remotePath 上传成功")
        } else {
            val errorBody = response.errorBody()?.string() ?: "Unknown error"
            addLog("✗ $remotePath 失败: ${response.code()} - $errorBody")
        }
    }

    // 上传 YML 内容字符串
    suspend fun uploadYmlContent(
        owner: String,
        repo: String,
        filename: String,
        content: String,
        api: GithubApi,
        token: String,
        branch: String,
        addLog: (String) -> Unit
    ) {
        val contentBase64 = Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP)
        uploadFile(api, owner, repo, ".github/workflows/$filename", contentBase64, token, branch, addLog)
    }

    // 上传逻辑
    fun startUpload() {
        if (isUploading) return
        if (repoUrl.isBlank()) {
            addLog("⚠ 请输入仓库地址")
            return
        }
        if (token.isBlank()) {
            addLog("⚠ 请输入 GitHub Token")
            return
        }

        isUploading = true
        addLog("🚀 开始上传...")

        context.lifecycleScope.launch {
            try {
                val (owner, repo) = parseRepo(repoUrl)
                    ?: throw Exception("仓库地址格式错误，请检查格式")

                addLog("📦 仓库: $owner/$repo")
                addLog("🌿 分支: $branch")

                // 1. 上传默认的 workflow（如果勾选）
                if (uploadDefaultUnpack) {
                    addLog("📤 上传 zip-moveroot.yml ...")
                    uploadYmlContent(owner, repo, "zip-moveroot.yml", DEFAULT_UNPACK_YML, api, token, branch) { addLog(it) }
                } else {
                    addLog("⏭ 跳过 zip-moveroot.yml")
                }

                if (uploadDefaultBuild) {
                    addLog("📤 上传 build.yml ...")
                    uploadYmlContent(owner, repo, "build.yml", DEFAULT_BUILD_YML, api, token, branch) { addLog(it) }
                } else {
                    addLog("⏭ 跳过 build.yml")
                }

                // 2. 上传自定义的 YML 文件
                if (customYmlFiles.isEmpty()) {
                    addLog("📂 无自定义 YML 文件")
                } else {
                    for ((uri, filename) in customYmlFiles) {
                        addLog("📤 上传自定义 YML: $filename ...")
                        try {
                            val contentBase64 = readFileAsBase64(uri)
                            val remotePath = ".github/workflows/$filename"
                            uploadFile(api, owner, repo, remotePath, contentBase64, token, branch) { addLog(it) }
                        } catch (e: Exception) {
                            addLog("✗ $filename 读取失败: ${e.message}")
                        }
                    }
                }

                // 3. 上传 ZIP 文件
                zipFileUri?.let { uri ->
                    addLog("📤 上传 ZIP: $zipFileName ...")
                    try {
                        val contentBase64 = readFileAsBase64(uri)
                        uploadFile(api, owner, repo, zipFileName, contentBase64, token, branch) { addLog(it) }
                    } catch (e: Exception) {
                        addLog("✗ ZIP 读取失败: ${e.message}")
                    }
                } ?: addLog("📂 无 ZIP 文件")

                addLog("✅ 所有操作完成！")

            } catch (e: Exception) {
                addLog("❌ 错误: ${e.message}")
            } finally {
                isUploading = false
            }
        }
    }

    // 文件选择器
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val name = getFileNameFromUri(it)
            when {
                name.endsWith(".yml") || name.endsWith(".yaml") -> {
                    customYmlFiles.add(Pair(uri, name))
                    addLog("➕ 添加自定义 YML: $name")
                }
                name.endsWith(".zip") -> {
                    zipFileUri = uri
                    zipFileName = name
                    addLog("📁 选择 ZIP: $name")
                }
                else -> {
                    addLog("⚠ 不支持的文件类型: $name")
                }
            }
        }
    }

    // 自动滚动到最新日志
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    var showSettings by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("GitHub Workflow 上传工具", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "设置"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) {
        if (showSettings) {
            AlertDialog(
                onDismissRequest = { showSettings = false },
                title = { Text("设置") },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Token 输入
                        OutlinedTextField(
                            value = token,
                            onValueChange = {
                                token = it
                                savePrefs()
                            },
                            label = { Text("GitHub Token") },
                            placeholder = { Text("ghp_xxxxxxxxxxxx") },
                            leadingIcon = {
                                Icon(Icons.Default.Delete, contentDescription = null)
                            },
                            trailingIcon = {
                                IconButton(onClick = { tokenVisible = !tokenVisible }) {
                                    Text(if (tokenVisible) "隐藏" else "显示")
                                }
                            },
                            visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        // 分支输入
                        OutlinedTextField(
                            value = branch,
                            onValueChange = {
                                branch = it
                                savePrefs()
                            },
                            label = { Text("分支名") },
                            placeholder = { Text("默认 main") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSettings = false }) {
                        Text("保存")
                    }
                }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(it)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 仓库地址输入
            OutlinedTextField(
                value = repoUrl,
                onValueChange = {
                    repoUrl = it
                    savePrefs()
                },
                label = { Text("仓库地址 (GitHub URL)") },
                placeholder = { Text("例如: https://github.com/user/repo") },
                leadingIcon = {
                    Icon(Icons.Default.Delete, contentDescription = null)
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // 分隔线
            Divider(modifier = Modifier.padding(vertical = 4.dp))

            // 默认文件选择
            Text(
                "默认 Workflow 文件",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = uploadDefaultUnpack,
                                onCheckedChange = {
                                    uploadDefaultUnpack = it
                                    savePrefs()
                                }
                            )
                            Text("zip-moveroot.yml", modifier = Modifier.padding(start = 4.dp))
                        }
                        Text(
                            "自动解压 ZIP",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = uploadDefaultBuild,
                                onCheckedChange = {
                                    uploadDefaultBuild = it
                                    savePrefs()
                                }
                            )
                            Text("build.yml", modifier = Modifier.padding(start = 4.dp))
                        }
                        Text(
                            "构建 Android APK",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 自定义文件选择按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { filePickerLauncher.launch("*/*") },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("选择文件")
                }
                Text(
                    "支持 YML / ZIP",
                    modifier = Modifier.align(Alignment.CenterVertically),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 自定义 YML 文件列表
            if (customYmlFiles.isNotEmpty()) {
                Text(
                    "自定义 YML 文件 (${customYmlFiles.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 100.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    LazyColumn(
                        modifier = Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(customYmlFiles.size) { index ->
                            val (_, name) = customYmlFiles[index]
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        name,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        customYmlFiles.removeAt(index)
                                        addLog("🗑 移除: $name")
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "删除",
                                        modifier = Modifier.size(16.dp),
                                        tint = ErrorRed
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ZIP 文件显示
            if (zipFileName.isNotBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    "ZIP 文件",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    zipFileName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                addLog("🗑 移除 ZIP: $zipFileName")
                                zipFileUri = null
                                zipFileName = ""
                            }
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除",
                                tint = ErrorRed
                            )
                        }
                    }
                }
            }

            // 上传按钮
            Button(
                onClick = { startUpload() },
                enabled = !isUploading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                if (isUploading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("上传中...", style = MaterialTheme.typography.titleMedium)
                } else {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("开始上传", style = MaterialTheme.typography.titleMedium)
                }
            }

            // 日志区域
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "操作日志",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = { logs = emptyList() }) {
                    Text("清除日志")
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                if (logs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "日志将在此处显示...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(logs.size) { index ->
                            val log = logs[index]
                            val color = when {
                                log.contains("✓") -> SuccessGreen
                                log.contains("✗") || log.contains("❌") -> ErrorRed
                                log.contains("⚠") -> WarningOrange
                                log.contains("📤") -> InfoBlue
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Text(
                                text = log,
                                style = MaterialTheme.typography.bodySmall,
                                color = color,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}
