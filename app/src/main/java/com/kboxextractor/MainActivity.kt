package com.kboxextractor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Xml
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.selection.SelectionContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

enum class Screen { WELCOME, READY, LOADING, RESULT, ERROR }

private const val MEGATRON_URL =
    "https://github.com/MeowDump/Integrity-Box/releases/download/archive/Megatron"

private class DownloadFailedException : Exception("Download failed — check your connection")
private class ServerErrorException(val code: Int) : Exception("Server error [$code] — try again later")
private class EmptyKeyboxException : Exception("Decoded keybox is empty — the source may have changed")
private class InvalidKeyboxException : Exception("Decoded data is not a valid keybox.xml")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KboxExtractorApp()
        }
    }
}

private val LightColors = lightColorScheme(
    background = Color.White,
    surface = Color.White,
    onBackground = Color.Black,
    onSurface = Color.Black,
    primary = Color.Black,
    onPrimary = Color.White,
    secondary = Color(0xFF444444),
    onSecondary = Color.White,
)

private val AmoledColors = darkColorScheme(
    background = Color.Black,
    surface = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
    primary = Color.White,
    onPrimary = Color.Black,
    secondary = Color(0xFFAAAAAA),
    onSecondary = Color.Black,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KboxExtractorApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var isAmoled by remember { mutableStateOf(false) }
    var screen by remember { mutableStateOf(Screen.WELCOME) }
    var zipName by remember { mutableStateOf("") }
    var xmlContent by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("") }
    var previewExpanded by remember { mutableStateOf(false) }

    fun resetAll() {
        zipName = ""
        xmlContent = ""
        errorMessage = ""
        statusText = ""
        previewExpanded = false
        screen = Screen.WELCOME
    }

    fun startFetch() {
        statusText = ""
        screen = Screen.LOADING
        scope.launch {
            try {
                xmlContent = fetchAndDecode { statusText = it }
                screen = Screen.RESULT
            } catch (e: Exception) {
                errorMessage = when {
                    e is DownloadFailedException -> "Download failed — check your connection"
                    e is ServerErrorException -> "Server error [${e.code}] — try again later"
                    e is IllegalArgumentException && e.message == "invalid hex" -> "Decode error: invalid hex data"
                    e is IllegalArgumentException && e.message?.startsWith("base64 pass ") == true ->
                        "Decode error: ${e.message}"
                    e is EmptyKeyboxException -> "Decoded keybox is empty — the source may have changed"
                    e is InvalidKeyboxException -> e.message ?: "Something went wrong"
                    else -> "Something went wrong: ${e.message}"
                }
                screen = Screen.ERROR
            }
        }
    }

    val zipPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val valid = withContext(Dispatchers.IO) { isValidZip(context, uri) }
            if (valid) {
                zipName = withContext(Dispatchers.IO) { queryDisplayName(context, uri) }
                screen = Screen.READY
            } else {
                snackbarHostState.showSnackbar("Not a valid ZIP file — please select the Integrity-Box module ZIP")
            }
        }
    }

    fun saveToDownloads() {
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, "keybox.xml")
                            put(MediaStore.MediaColumns.MIME_TYPE, "text/xml")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }
                        val uri = context.contentResolver.insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                        ) ?: return@withContext false
                        context.contentResolver.openOutputStream(uri)?.use {
                            it.write(xmlContent.toByteArray(Charsets.UTF_8))
                        } ?: return@withContext false
                        true
                    } else {
                        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        dir.mkdirs()
                        File(dir, "keybox.xml").writeText(xmlContent, Charsets.UTF_8)
                        true
                    }
                } catch (e: Exception) {
                    false
                }
            }
            snackbarHostState.showSnackbar(
                if (saved) "Saved to Downloads/keybox.xml" else "Could not save — try copying instead"
            )
        }
    }

    fun copyToClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("keybox.xml", xmlContent))
        scope.launch { snackbarHostState.showSnackbar("Copied to clipboard") }
    }

    MaterialTheme(colorScheme = if (isAmoled) AmoledColors else LightColors) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("KboxExtractor", fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton(onClick = { isAmoled = !isAmoled }) {
                            Icon(
                                imageVector = if (isAmoled) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                                contentDescription = "Toggle theme"
                            )
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "By Lecrex",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .clickable { openInBrowser(context, "https://github.com/LEECREX") }
                            .padding(horizontal = 12.dp, vertical = 2.dp)
                    )
                    Text(
                        text = "Integrity-Box by MeowDump",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .clickable { openInBrowser(context, "https://github.com/MeowDump/Integrity-Box") }
                            .padding(horizontal = 12.dp, vertical = 2.dp)
                    )
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (screen) {
                    Screen.WELCOME -> WelcomeScreen(
                        onSelectZip = { zipPicker.launch(arrayOf("*/*")) }
                    )
                    Screen.READY -> ReadyScreen(
                        zipName = zipName,
                        onGetKeybox = { startFetch() }
                    )
                    Screen.LOADING -> LoadingScreen(statusText = statusText)
                    Screen.RESULT -> ResultScreen(
                        xml = xmlContent,
                        previewExpanded = previewExpanded,
                        onTogglePreview = { previewExpanded = !previewExpanded },
                        onSave = { saveToDownloads() },
                        onCopy = { copyToClipboard() },
                        onStartOver = { resetAll() }
                    )
                    Screen.ERROR -> ErrorScreen(
                        message = errorMessage,
                        onRetry = { startFetch() },
                        onStartOver = { resetAll() }
                    )
                }
            }
        }
    }
}

@Composable
fun WelcomeScreen(onSelectZip: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("KboxExtractor", fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Provide your Integrity-Box module ZIP to get started",
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Spacer(Modifier.height(36.dp))
            Button(onClick = onSelectZip) { Text("Select ZIP") }
        }
    }
}

@Composable
fun ReadyScreen(zipName: String, onGetKeybox: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                zipName,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
            )
            Spacer(Modifier.height(36.dp))
            Button(onClick = onGetKeybox, modifier = Modifier.fillMaxWidth(0.7f).height(56.dp)) {
                Text("Get Keybox", fontSize = 18.sp)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Will fetch and decode keybox.xml from Integrity-Box servers",
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }
    }
}

@Composable
fun LoadingScreen(statusText: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(20.dp))
            Text(
                statusText,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }
    }
}

@Composable
fun ResultScreen(
    xml: String,
    previewExpanded: Boolean,
    onTogglePreview: () -> Unit,
    onSave: () -> Unit,
    onCopy: () -> Unit,
    onStartOver: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text("keybox.xml ready ✓", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onTogglePreview() }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Preview XML",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (previewExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null
            )
        }
        AnimatedVisibility(visible = previewExpanded) {
            SelectionContainer {
                Text(
                    xml,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                        )
                        .padding(10.dp)
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
            Text("Save to Downloads")
        }
        Spacer(Modifier.height(10.dp))
        Button(onClick = onCopy, modifier = Modifier.fillMaxWidth()) {
            Text("Copy to Clipboard")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onStartOver, modifier = Modifier.fillMaxWidth()) {
            Text("Start Over")
        }
    }
}

@Composable
fun ErrorScreen(message: String, onRetry: () -> Unit, onStartOver: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(16.dp))
            Text(
                message,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
            )
            Spacer(Modifier.height(28.dp))
            Button(onClick = onRetry) { Text("Retry") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onStartOver) { Text("Start Over") }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun WelcomePreview() {
    MaterialTheme(colorScheme = LightColors) {
        WelcomeScreen(onSelectZip = {})
    }
}

// ---------- Helpers ----------

private fun openInBrowser(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        // no browser available — ignore
    }
}

// ---------- Data / decode layer ----------

private suspend fun fetchAndDecode(onStatus: (String) -> Unit): String {
    onStatus("Downloading from GitHub...")
    val raw = withContext(Dispatchers.IO) { downloadMegatron() }
    val xml: String
    if (isHexString(raw)) {
        // Legacy layout: ROT13 -> hex -> base64 x10
        onStatus("Decoding ROT13...")
        val hexed = withContext(Dispatchers.IO) { rot13Decode(raw) }
        onStatus("Decoding hex...")
        var data = withContext(Dispatchers.IO) { hexDecode(hexed) }
        for (pass in 1..10) {
            onStatus("Decoding base64 (pass $pass/10)...")
            data = withContext(Dispatchers.IO) { base64Decode(data, pass) }
        }
        xml = withContext(Dispatchers.IO) { String(data, Charsets.UTF_8) }
    } else {
        // Current layout: base64 x10 -> hex -> ROT13
        var data = withContext(Dispatchers.IO) { raw.toByteArray(Charsets.UTF_8) }
        for (pass in 1..10) {
            onStatus("Decoding base64 (pass $pass/10)...")
            data = withContext(Dispatchers.IO) { base64Decode(data, pass) }
        }
        onStatus("Decoding hex...")
        val hexed = withContext(Dispatchers.IO) { String(data, Charsets.UTF_8) }
        val rotBytes = withContext(Dispatchers.IO) { hexDecode(hexed) }
        onStatus("Decoding ROT13...")
        xml = withContext(Dispatchers.IO) { rot13Decode(String(rotBytes, Charsets.UTF_8)) }
    }
    onStatus("Validating...")
    if (xml.isBlank()) throw EmptyKeyboxException()
    withContext(Dispatchers.IO) { validateKeybox(xml) }
    val fixedXml = xml
        .replace("-----</Certificate>", "-----\n</Certificate>")
        .replace("-----</PrivateKey>", "-----\n</PrivateKey>")
    onStatus("Done.")
    return fixedXml
}

private fun isHexString(s: String): Boolean {
    val cleaned = s.filterNot { it.isWhitespace() }
    if (cleaned.isEmpty() || cleaned.length % 2 != 0) return false
    for (c in cleaned) {
        val ok = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
        if (!ok) return false
    }
    return true
}

private fun downloadMegatron(): String {
    val conn = URL(MEGATRON_URL).openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.instanceFollowRedirects = true
        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK) throw ServerErrorException(code)
        return conn.inputStream.bufferedReader().use { it.readText().trim() }
    } catch (e: ServerErrorException) {
        throw e
    } catch (e: IOException) {
        throw DownloadFailedException()
    } finally {
        conn.disconnect()
    }
}

private fun rot13Decode(input: String): String = buildString(input.length) {
    for (c in input) {
        append(
            when (c) {
                in 'A'..'Z' -> 'A' + ((c - 'A' + 13) % 26)
                in 'a'..'z' -> 'a' + ((c - 'a' + 13) % 26)
                else -> c
            }
        )
    }
}

private fun hexDecode(input: String): ByteArray {
    val cleaned = input.filterNot { it.isWhitespace() }
    if (cleaned.length % 2 != 0) throw IllegalArgumentException("invalid hex")
    for (c in cleaned) {
        val ok = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
        if (!ok) throw IllegalArgumentException("invalid hex")
    }
    val out = ByteArray(cleaned.length / 2)
    for (i in out.indices) {
        val hi = Character.digit(cleaned[i * 2], 16)
        val lo = Character.digit(cleaned[i * 2 + 1], 16)
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

private fun base64Decode(data: ByteArray, pass: Int): ByteArray {
    return try {
        Base64.decode(data, Base64.DEFAULT)
    } catch (e: Exception) {
        throw IllegalArgumentException("base64 pass $pass failed")
    }
}

private fun validateKeybox(xml: String) {
    try {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))
        var rootSeen = false
        var sawKeyboxWithDeviceId = false
        var sawKeyWithAlgo = false
        var sawPrivateKey = false
        var sawCertificate = false
        var insideKey = false
        var insideCertChain = false
        val stack = ArrayDeque<String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "AndroidAttestation" ->
                            if (stack.isEmpty()) rootSeen = true
                        "Keybox" ->
                            if (stack.lastOrNull() == "AndroidAttestation" &&
                                parser.getAttributeValue(null, "DeviceID") != null
                            ) sawKeyboxWithDeviceId = true
                        "Key" -> {
                            if (stack.lastOrNull() == "Keybox") {
                                val algo = parser.getAttributeValue(null, "algorithm")
                                if (algo == "ecdsa" || algo == "rsa") sawKeyWithAlgo = true
                                insideKey = true
                            }
                        }
                        "PrivateKey" -> if (insideKey) sawPrivateKey = true
                        "CertificateChain" -> if (insideKey) insideCertChain = true
                        "Certificate" -> if (insideCertChain) sawCertificate = true
                    }
                    stack.addLast(parser.name)
                }
                XmlPullParser.END_TAG -> {
                    stack.removeLast()
                    when (parser.name) {
                        "Key" -> insideKey = false
                        "CertificateChain" -> insideCertChain = false
                    }
                }
            }
            event = parser.next()
        }
        if (!rootSeen || !sawKeyboxWithDeviceId || !sawKeyWithAlgo ||
            !sawPrivateKey || !sawCertificate
        ) throw InvalidKeyboxException()
    } catch (e: XmlPullParserException) {
        throw InvalidKeyboxException()
    } catch (e: IOException) {
        throw InvalidKeyboxException()
    }
}

private fun isValidZip(context: Context, uri: Uri): Boolean {
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zis ->
                zis.nextEntry != null
            }
        } ?: false
    } catch (e: Exception) {
        false
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String {
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                cursor.getString(idx)?.let { return it }
            }
        }
    } catch (e: Exception) {
        // fall through
    }
    return uri.lastPathSegment ?: "selected.zip"
}
