package com.mikgpt

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MikGPTTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppScreen()
                }
            }
        }
    }
}

data class Message(
    val sender: String,
    val text: String,
    val isArtifact: Boolean = false,
    val artifactHtml: String = ""
)

fun checkStoragePermission(context: android.content.Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var modelLoaded by remember { mutableStateOf(false) }
    var modelName by remember { mutableStateOf("No model imported") }
    var modelStatus by remember { mutableStateOf("Please import a .gguf model file to start.") }
    var systemOutputSpeed by remember { mutableStateOf("") }
    var inputText by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf<Message>()) }
    var searchEnabled by remember { mutableStateOf(true) }
    var isGenerating by remember { mutableStateOf(false) }

    // Dialog state variables
    var showModelPicker by remember { mutableStateOf(false) }
    var hasStoragePermission by remember { mutableStateOf(checkStoragePermission(context)) }
    var showLogDialog by remember { mutableStateOf(false) }
    var logContent by remember { mutableStateOf("") }

    // Copy/Download progress state
    var showCopyProgress by remember { mutableStateOf(false) }
    var copyProgressLabel by remember { mutableStateOf("") }
    var copyProgress by remember { mutableStateOf(0f) }

    // Refresh permission when dialog is shown
    LaunchedEffect(showModelPicker) {
        hasStoragePermission = checkStoragePermission(context)
    }

    // Bottom sheet details for showing HTML artifacts (like Claude)
    var selectedArtifactHtml by remember { mutableStateOf<String?>(null) }
    var showArtifactSheet by remember { mutableStateOf(false) }


    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("MikGPT", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
                        Text(
                            text = modelStatus + if (systemOutputSpeed.isNotEmpty()) " | $systemOutputSpeed" else "",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch(Dispatchers.IO) {
                            val logFile = File(context.filesDir, "llama.log")
                            val content = if (logFile.exists()) logFile.readText() else "No engine logs recorded yet."
                            withContext(Dispatchers.Main) {
                                logContent = content
                                showLogDialog = true
                            }
                        }
                    }) {
                        Icon(Icons.Default.BugReport, contentDescription = "View Engine Logs", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { showModelPicker = true }) {
                        Icon(Icons.Default.CloudUpload, contentDescription = "Select GGUF Model", tint = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            ) {
                // Message List
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    reverseLayout = false
                ) {
                    items(messages) { msg ->
                        ChatBubble(msg, onArtifactClick = { html ->
                            selectedArtifactHtml = html
                            showArtifactSheet = true
                        })
                    }
                }

                // Search Toggle & Input Field
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Quick-toggle for Search (Gemini-Inspired UX)
                    Icon(
                        imageVector = if (searchEnabled) Icons.Default.Search else Icons.Default.SearchOff,
                        contentDescription = "Toggle Search",
                        tint = if (searchEnabled) MaterialTheme.colorScheme.primary else Color.Gray,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (searchEnabled) MaterialTheme.colorScheme.primaryContainer.copy(
                                    alpha = 0.5f
                                ) else Color.LightGray.copy(alpha = 0.3f)
                            )
                            .clickable { searchEnabled = !searchEnabled }
                            .padding(8.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Ask MikGPT to write a game, dashboard...") },
                        maxLines = 4,
                        shape = RoundedCornerShape(24.dp),
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    if (inputText.isNotEmpty() && !isGenerating && modelLoaded) {
                                        val prompt = inputText
                                        inputText = ""
                                        isGenerating = true
                                        messages = messages + Message("User", prompt)

                                        scope.launch(Dispatchers.IO) {
                                            var finalPrompt = prompt
                                            if (searchEnabled) {
                                                withContext(Dispatchers.Main) {
                                                    modelStatus = "Searching the web..."
                                                }
                                                val searchResults = SearchTool.performSearch(prompt)
                                                finalPrompt = "Use the following search results to answer the query:\n$searchResults\n\nQuery: $prompt"
                                            }

                                            withContext(Dispatchers.Main) {
                                                modelStatus = "Thinking..."
                                            }

                                            // Setup streaming placeholder message
                                            var currentResponseText = ""
                                            withContext(Dispatchers.Main) {
                                                messages = messages + Message("Assistant", "")
                                            }

                                            val startTime = System.currentTimeMillis()
                                            LlamaInference.generate(finalPrompt, null, object : TokenCallback {
                                                override fun onToken(token: String) {
                                                    currentResponseText += token
                                                    // Update streaming UI
                                                    scope.launch(Dispatchers.Main) {
                                                        messages = messages.dropLast(1) + Message("Assistant", currentResponseText)
                                                    }
                                                }
                                            })

                                            val duration = (System.currentTimeMillis() - startTime) / 1000.0
                                            val wordCount = currentResponseText.split("\\s+".toRegex()).size
                                            val speed = if (duration > 0) String.format("%.1f w/s", wordCount / duration) else ""

                                            withContext(Dispatchers.Main) {
                                                systemOutputSpeed = speed
                                                modelStatus = "Active: $modelName"
                                                isGenerating = false

                                                // Detect HTML code block in response and automatically flag it as an Artifact
                                                val htmlBlockRegex = "```html\\s*([\\s\\S]*?)\\s*```".toRegex()
                                                val match = htmlBlockRegex.find(currentResponseText)
                                                if (match != null) {
                                                    val htmlContent = match.groupValues[1]
                                                    messages = messages.dropLast(1) + Message(
                                                        sender = "Assistant",
                                                        text = currentResponseText,
                                                        isArtifact = true,
                                                        artifactHtml = htmlContent
                                                    )
                                                }
                                            }
                                        }
                                    }
                                },
                                enabled = inputText.isNotEmpty() && !isGenerating && modelLoaded
                            ) {
                                Icon(Icons.Default.Send, contentDescription = "Send")
                            }
                        }
                    )
                }
            }
        }
    }

    // Claude-style sliding Artifact Drawer
    if (showArtifactSheet && selectedArtifactHtml != null) {
        ModalBottomSheet(
            onDismissRequest = { showArtifactSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF121212))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Interactive Sandbox View", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    IconButton(onClick = { showArtifactSheet = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    ArtifactWebView(htmlContent = selectedArtifactHtml!!)
                }
            }
        }
    }

    if (showModelPicker) {
        ModelPickerDialog(
            onDismiss = { showModelPicker = false },
            onFileSelected = { sourceFile ->
                showModelPicker = false
                scope.launch(Dispatchers.IO) {
                    val sandboxDir = context.getExternalFilesDir(null)!!
                    val destFile = File(sandboxDir, sourceFile.name)

                    // Only copy if not already in sandbox
                    if (!destFile.exists() || destFile.length() != sourceFile.length()) {
                        withContext(Dispatchers.Main) {
                            showCopyProgress = true
                            copyProgressLabel = "Copying ${sourceFile.name}..."
                            copyProgress = 0f
                        }
                        val totalBytes = sourceFile.length().toFloat()
                        var copiedBytes = 0L
                        sourceFile.inputStream().use { input ->
                            destFile.outputStream().use { output ->
                                val buf = ByteArray(1024 * 1024) // 1 MB chunks
                                var bytes: Int
                                while (input.read(buf).also { bytes = it } != -1) {
                                    output.write(buf, 0, bytes)
                                    copiedBytes += bytes
                                    val progress = copiedBytes / totalBytes
                                    withContext(Dispatchers.Main) { copyProgress = progress }
                                }
                            }
                        }
                        withContext(Dispatchers.Main) { showCopyProgress = false }
                    }

                    withContext(Dispatchers.Main) {
                        modelStatus = "Loading engine..."
                        modelName = destFile.name
                    }
                    val success = LlamaInference.loadModel(destFile.absolutePath)
                    withContext(Dispatchers.Main) {
                        if (success) {
                            modelLoaded = true
                            modelStatus = "Active: ${destFile.name}"
                        } else {
                            modelStatus = "Error: Failed to load model file."
                        }
                    }
                }
            },
            onDirectDownload = { url, fileName ->
                showModelPicker = false
                scope.launch(Dispatchers.IO) {
                    val sandboxDir = context.getExternalFilesDir(null)!!
                    val destFile = File(sandboxDir, fileName)
                    withContext(Dispatchers.Main) {
                        showCopyProgress = true
                        copyProgressLabel = "Downloading $fileName..."
                        copyProgress = 0f
                    }
                    try {
                        val connection = URL(url).openConnection() as HttpURLConnection
                        connection.connect()
                        val totalBytes = connection.contentLength.toFloat()
                        var downloadedBytes = 0L
                        connection.inputStream.use { input ->
                            destFile.outputStream().use { output ->
                                val buf = ByteArray(1024 * 1024)
                                var bytes: Int
                                while (input.read(buf).also { bytes = it } != -1) {
                                    output.write(buf, 0, bytes)
                                    downloadedBytes += bytes
                                    if (totalBytes > 0) {
                                        val progress = downloadedBytes / totalBytes
                                        withContext(Dispatchers.Main) { copyProgress = progress }
                                    }
                                }
                            }
                        }
                        withContext(Dispatchers.Main) {
                            showCopyProgress = false
                            modelStatus = "Loading engine..."
                            modelName = destFile.name
                        }
                        val success = LlamaInference.loadModel(destFile.absolutePath)
                        withContext(Dispatchers.Main) {
                            if (success) {
                                modelLoaded = true
                                modelStatus = "Active: ${destFile.name}"
                            } else {
                                modelStatus = "Error: Failed to load downloaded model."
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            showCopyProgress = false
                            modelStatus = "Error: Download failed — ${e.message}"
                        }
                    }
                }
            },
            onRequestPermission = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + context.packageName)
                    )
                    context.startActivity(intent)
                }
            },
            hasPermission = hasStoragePermission
        )
    }

    // Copy / Download progress overlay dialog
    if (showCopyProgress) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(copyProgressLabel, fontSize = 14.sp) },
            text = {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = copyProgress,
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("${(copyProgress * 100).toInt()}%", fontSize = 12.sp, color = Color.LightGray)
                }
            },
            confirmButton = {}
        )
    }

    if (showLogDialog) {
        AlertDialog(
            onDismissRequest = { showLogDialog = false },
            title = { Text("Engine Logs Diagnostics") },
            text = {
                Box(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    LazyColumn {
                        item {
                            Text(logContent, fontSize = 11.sp, color = Color.White, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLogDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
fun ChatBubble(msg: Message, onArtifactClick: (String) -> Unit) {
    val isUser = msg.sender == "User"
    val background = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val alignment = if (isUser) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 0.dp,
                        bottomEnd = if (isUser) 0.dp else 16.dp
                    )
                )
                .background(background)
                .padding(12.dp)
        ) {
            Column {
                Text(msg.text, color = MaterialTheme.colorScheme.onPrimaryContainer)

                // Render Claude-like Interactive Artifact Launch Card
                if (msg.isArtifact) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black.copy(alpha = 0.2f))
                            .clickable { onArtifactClick(msg.artifactHtml) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play Icon", tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Launch Application Artifact", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("Tap to run interactive game or preview custom dashboard", fontSize = 11.sp, color = Color.LightGray)
                        }
                    }
                }
            }
        }
    }
}

// Custom Material 3 Premium Theme
@Composable
fun MikGPTTheme(content: @Composable () -> Unit) {
    val darkColorScheme = darkColorScheme(
        primary = Color(0xFF8AB4F8),
        onPrimary = Color(0xFF202124),
        primaryContainer = Color(0xFF3C4043),
        onPrimaryContainer = Color(0xFFE8EAED),
        secondary = Color(0xFFF28B82),
        secondaryContainer = Color(0xFF2D2E30),
        onSecondaryContainer = Color(0xFFE8EAED),
        background = Color(0xFF202124),
        surface = Color(0xFF202124)
    )

    MaterialTheme(
        colorScheme = darkColorScheme,
        content = content
    )
}

// Known models available on HuggingFace
data class HFModel(
    val name: String,
    val description: String,
    val sizeLabel: String,
    val downloadUrl: String,
    val fileName: String
)

val HUGGINGFACE_MODELS = listOf(
    HFModel(
        name = "Ternary-Bonsai-8B Q2_0",
        description = "Ternary-quantized, optimized for mobile CPU inference",
        sizeLabel = "~2.8 GB",
        downloadUrl = "https://huggingface.co/PrismML-Eng/Ternary-Bonsai-8B-GGUF/resolve/main/Ternary-Bonsai-8B-Q2_0_g64.gguf",
        fileName = "Ternary-Bonsai-8B-Q2_0_g64.gguf"
    )
)

@Composable
fun ModelPickerDialog(
    onDismiss: () -> Unit,
    onFileSelected: (File) -> Unit,
    onDirectDownload: (url: String, fileName: String) -> Unit,
    onRequestPermission: () -> Unit,
    hasPermission: Boolean
) {
    val context = LocalContext.current
    var downloadFiles by remember { mutableStateOf(listOf<File>()) }
    var refreshTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(hasPermission, refreshTrigger) {
        if (hasPermission) {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloadFiles = downloadsDir.listFiles { _, name ->
                name.endsWith(".gguf", ignoreCase = true)
            }?.toList() ?: emptyList()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Load Model")
                IconButton(onClick = { refreshTrigger++ }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = MaterialTheme.colorScheme.primary)
                }
            }
        },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                // ── Section 1: Local Downloads folder ──────────────────────
                item {
                    Text(
                        "📂  My Downloads",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                if (!hasPermission) {
                    item {
                        Button(
                            onClick = onRequestPermission,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Text("Grant Storage Access", fontSize = 12.sp)
                        }
                    }
                } else if (downloadFiles.isEmpty()) {
                    item {
                        Text(
                            "No .gguf files found in Downloads.",
                            fontSize = 12.sp,
                            color = Color.LightGray,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                } else {
                    items(downloadFiles) { file ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onFileSelected(file) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(file.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color.White)
                                Text(
                                    String.format("%.2f GB  •  Tap to load", file.length() / (1024.0 * 1024.0 * 1024.0)),
                                    fontSize = 10.sp,
                                    color = Color.LightGray
                                )
                            }
                        }
                        Divider(color = Color.White.copy(alpha = 0.08f))
                    }
                }

                // ── Section 2: HuggingFace direct download catalog ──────────
                item {
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        "🤗  Download from HuggingFace",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Models download directly to the app. No files to move.",
                        fontSize = 11.sp,
                        color = Color.LightGray
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                items(HUGGINGFACE_MODELS) { model ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onDirectDownload(model.downloadUrl, model.fileName) }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(model.name, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                                Text(model.description, fontSize = 10.sp, color = Color.LightGray)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                Text(model.sizeLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                                Spacer(modifier = Modifier.height(4.dp))
                                Icon(Icons.Default.Download, contentDescription = "Download", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
