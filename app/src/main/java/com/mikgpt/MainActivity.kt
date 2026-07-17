package com.mikgpt

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

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

    // Bottom sheet details for showing HTML artifacts (like Claude)
    var selectedArtifactHtml by remember { mutableStateOf<String?>(null) }
    var showArtifactSheet by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                modelStatus = "Importing GGUF model to local storage..."
                val name = getFileName(context, uri) ?: "model.gguf"
                modelName = name

                // Copy to internal storage so llama.cpp can load it safely
                val targetFile = File(context.filesDir, name)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }

                modelStatus = "Loading local GGUF engine..."
                val success = LlamaInference.loadModel(targetFile.absolutePath)
                withContext(Dispatchers.Main) {
                    if (success) {
                        modelLoaded = true
                        modelStatus = "Active: $name"
                    } else {
                        modelStatus = "Error: Failed to load model file."
                    }
                }
            }
        }
    }

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
                    IconButton(onClick = { filePicker.launch("*/*") }) {
                        Icon(Icons.Default.CloudUpload, contentDescription = "Import GGUF Model", tint = MaterialTheme.colorScheme.primary)
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

// Utility to retrieve file name from Android URI
fun getFileName(context: android.content.Context, uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        try {
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    result = cursor.getString(index)
                }
            }
        } finally {
            cursor?.close()
        }
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/') ?: -1
        if (cut != -1) {
            result = result?.substring(cut + 1)
        }
    }
    return result
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
        onContainer = Color(0xFFE8EAED),
        background = Color(0xFF202124),
        surface = Color(0xFF202124)
    )

    MaterialTheme(
        colorScheme = darkColorScheme,
        content = content
    )
}
