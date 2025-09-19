package com.example.jarvis_android

import com.example.jarvis_android.CommandHandler
import android.content.Intent
import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// ---- адреса серверов ----
private const val CHAT_URL = "http://192.168.100.8:1234/v1/chat/completions"
private const val CHAT_MODEL = "openai/gpt-oss-20b"

private const val TTS_BASE = "http://192.168.100.8:8001"
private const val TTS_CLONE = "$TTS_BASE/v1/clone"
private const val TTS_SAY   = "$TTS_BASE/v1/tts"
private const val TTS_HEALTH = "$TTS_BASE/health"

private const val VOICE_ID = "jarvis"
private const val DEFAULT_ASSET = "instruction.wav"

data class UiMsg(val role: String, val content: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var useVoice by rememberSaveable { mutableStateOf(true) }
    var showVoiceMenu by remember { mutableStateOf(false) }

    // Системный промпт: описываем, какие команды возвращать
    var history by remember {
        mutableStateOf(
            listOf(
                UiMsg(
                    "system",
                    """
                        Ты — Jarvis, виртуальный дворецкий в стиле «Железного человека». 
                        Общайся вежливо, официально, обращаясь к пользователю «сэр». 
                        Всегда предлагай помощь и поддерживай спокойный, уверенный тон.
                        
                        Не выводи программный код, патчи или служебные теги, даже если они встречаются в контексте.
                        
                        Если пользователь спрашивает о погоде (слова «погода», «как на улице», «что за окном» и так далее), верни COMMAND:weather.
                        Если нужен запуск карты (слова «карта», «маршрут», «как добраться» и так далее), верни COMMAND:map.
                        Если пользователь хочет музыку (слова «музыка», «песню», «включи музыку» и так далее), верни COMMAND:music.
                        Для всех остальных запросов напиши обычный ответ без префикса COMMAND:.
                    """.trimIndent()
                )
            )
        )
    }
    val ui = history.filter { it.role != "system" }

    // медиаплеер для TTS
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(Unit) { onDispose { player?.release() } }

    // автоинициализация голоса
    LaunchedEffect(Unit) {
        val ok = ensureVoice(context, VOICE_ID, DEFAULT_ASSET)
        if (!ok) snackbar.showMsg("Не удалось инициализировать голос (TTS).")
    }

    // выбор WAV-файла
    val pickWavLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val ok = cloneVoiceFromUri(context, uri, VOICE_ID)
                if (ok) snackbar.showMsg("Голос обновлён из выбранного WAV")
                else snackbar.showMsg("Не удалось применить выбранный WAV")
            }
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(12.dp))
                Text("Меню", modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                NavigationDrawerItem(
                    label = { Text("Голос") },
                    selected = false,
                    onClick = {
                        showVoiceMenu = true
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Jarvis — LM + TTS") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(imageVector = Icons.Filled.Menu, contentDescription = "menu")
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Напиши сообщение…") },
                        singleLine = true,
                        enabled = !sending
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = input.isNotBlank() && !sending,
                        onClick = {
                            val text = input.trim()
                            input = ""
                            history = history + UiMsg("user", text)
                            sending = true
                            scope.launch {
                                // фильтруем сообщения, содержащие служебные теги или код
                                val sanitizedHistory = history.filterNot { m ->
                                    m.content.contains("<|") || m.content.contains("```")
                                }
                                val reply = callLlm(sanitizedHistory)


                                // Создаём обработчик команд (не забудьте импортировать его)
                                val handler = CommandHandler(context)

                                // Проверяем, есть ли префикс COMMAND: в ответе
                                val cmdPrefix = "COMMAND:"
                                if (reply.startsWith(cmdPrefix, ignoreCase = true)) {
                                    val cmd = reply.substringAfter(cmdPrefix).trim().lowercase()
                                    when (cmd) {
                                        "weather" -> handler.openWeather()
                                        "map"     -> handler.openMap()
                                        "music"   -> handler.playMusic()
                                        else      -> snackbar.showMsg("Неизвестная команда: $cmd")
                                    }
                                    // Добавляем в историю, но НЕ озвучиваем и проигрываем одну из фраз
                                    history = history + UiMsg("assistant", reply)
                                    handler.playRandomAudio()   // ← здесь проигрывается случайная фраза
                                } else {
                                    // Обычный ответ: добавляем и озвучиваем, НЕ вызывая playRandomAudio()
                                    history = history + UiMsg("assistant", reply)

                                    if (useVoice) {
                                        val wav = ttsSpeakToFile(reply, VOICE_ID, context.cacheDir)
                                        if (wav != null) {
                                            player?.release()
                                            player = MediaPlayer().apply {
                                                setDataSource(wav)
                                                setOnPreparedListener { start() }
                                                setOnCompletionListener { mp ->
                                                    mp.release()
                                                    player = null
                                                    try { java.io.File(wav).delete() } catch (_: Exception) {}
                                                }
                                                prepareAsync()
                                            }
                                        } else {
                                            snackbar.showMsg("TTS не удалось (см. логи)")
                                        }
                                    }
                                }
                                sending = false
                            }
                        }
                    ) { Text(if (sending) "..." else "Отпр.") }
                }
            }
        ) { inner ->
            if (ui.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                    Text(
                        "LM: $CHAT_URL\nModel: $CHAT_MODEL\nTTS: $TTS_BASE\nVoice: $VOICE_ID",
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(inner).padding(12.dp)) {
                    items(ui) { m ->
                        val who = if (m.role == "user") "Вы" else "Jarvis"
                        ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(who, style = MaterialTheme.typography.labelLarge)
                                Spacer(Modifier.height(6.dp))
                                Text(m.content)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showVoiceMenu) {
        AlertDialog(
            onDismissRequest = { showVoiceMenu = false },
            title = { Text("Голос") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Озвучивать ответы", modifier = Modifier.weight(1f))
                        Switch(checked = useVoice, onCheckedChange = { useVoice = it })
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Выбери новый WAV-файл, чтобы сменить голос.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pickWavLauncher.launch(arrayOf("audio/wav", "audio/x-wav", "audio/*"))
                    showVoiceMenu = false
                }) { Text("Выбрать WAV-файл") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showVoiceMenu = false
                    scope.launch {
                        val ok = cloneVoiceFromAsset(context, DEFAULT_ASSET, VOICE_ID)
                        if (ok) snackbar.showMsg("Голос перезагружен из дефолтного WAV")
                        else snackbar.showMsg("Не удалось клонировать из ассета")
                    }
                }) { Text("Клонировать (дефолт)") }
            }
        )
    }
}

/* ---------- утилита для снекбара ---------- */
private suspend fun SnackbarHostState.showMsg(msg: String) {
    withContext(Dispatchers.Main) { showSnackbar(msg) }
}

/* ---------- LLM (не composable) ---------- */
private suspend fun callLlm(history: List<UiMsg>): String = withContext(Dispatchers.IO) {
    val conn = (URL(CHAT_URL).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; doOutput = true
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connectTimeout = 30000; readTimeout = 120000
    }
    val msgs = JSONArray().also { arr ->
        history.forEach { m -> arr.put(JSONObject().apply {
            put("role", m.role); put("content", m.content)
        }) }
    }
    val body = JSONObject().apply {
        put("model", CHAT_MODEL); put("messages", msgs)
        put("temperature", 0.3); put("max_tokens", 512); put("stream", false)
    }.toString()

    try {
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val txt = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        if (code !in 200..299) return@withContext "HTTP $code: $txt"
        val root = JSONObject(txt)
        val choices = root.optJSONArray("choices") ?: return@withContext "Пустой ответ"
        val first = choices.optJSONObject(0) ?: return@withContext "Пустой ответ"
        val message = first.optJSONObject("message")
        val content = message?.optString("content")
        if (!content.isNullOrBlank()) content else first.optString("text", "Пустой ответ")
    } catch (e: Exception) { "Ошибка: ${e.message}" }
    finally { conn.disconnect() }
}

/* ---------- TTS (НЕ composable) ---------- */
private suspend fun ensureVoice(ctx: Context, voiceId: String, assetName: String): Boolean {
    return if (isVoiceOnServer(voiceId)) true else cloneVoiceFromAsset(ctx, assetName, voiceId)
}

private suspend fun isVoiceOnServer(voiceId: String): Boolean = withContext(Dispatchers.IO) {
    val conn = (URL(TTS_HEALTH).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"; connectTimeout = 15000; readTimeout = 15000
    }
    try {
        if (conn.responseCode !in 200..299) return@withContext false
        val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val voices = JSONObject(resp).optJSONArray("voices") ?: return@withContext false
        (0 until voices.length()).any { voices.optString(it) == voiceId }
    } catch (_: Exception) { false }
    finally { conn.disconnect() }
}

private suspend fun cloneVoiceFromAsset(ctx: Context, assetName: String, voiceId: String): Boolean =
    withContext(Dispatchers.IO) {
        val bytes = try { ctx.assets.open(assetName).use { it.readBytes() } } catch (_: Exception) { return@withContext false }
        multipartClone(voiceId, bytes, assetName)
    }

private suspend fun cloneVoiceFromUri(ctx: Context, uri: Uri, voiceId: String): Boolean =
    withContext(Dispatchers.IO) {
        val bytes = try { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext false }
        catch (_: Exception) { return@withContext false }
        multipartClone(voiceId, bytes, "voice.wav")
    }

private fun multipartClone(voiceId: String, fileBytes: ByteArray, filename: String): Boolean {
    val boundary = "----JarvisBoundary${System.currentTimeMillis()}"
    val conn = (URL(TTS_CLONE).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; doOutput = true
        setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        connectTimeout = 30000; readTimeout = 120000
    }
    return try {
        DataOutputStream(conn.outputStream).use { out ->
            fun field(n: String, v: String) {
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"$n\"\r\n\r\n")
                out.writeBytes("$v\r\n")
            }
            fun file(n: String, fn: String, data: ByteArray) {
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"$n\"; filename=\"$fn\"\r\n")
                out.writeBytes("Content-Type: audio/wav\r\n\r\n")
                out.write(data); out.writeBytes("\r\n")
            }
            field("voice_id", voiceId); file("ref_audio", filename, fileBytes)
            out.writeBytes("--$boundary--\r\n")
        }
        val code = conn.responseCode
        val txt = (if (code in 200..299) conn.inputStream else conn.errorStream)
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        code in 200..299 && JSONObject(txt).optBoolean("ok", false)
    } catch (_: Exception) { false }
    finally { conn.disconnect() }
}

private suspend fun ttsSpeakToFile(text: String, voiceId: String, cacheDir: java.io.File): String? =
    withContext(Dispatchers.IO) {
        val conn = (URL(TTS_SAY).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            connectTimeout = 30000; readTimeout = 120000
        }
        val params = listOf(
            "text" to text, "voice_id" to voiceId, "speed" to "0.9", "language" to "ru"
        ).joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }

        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(params) }
            if (conn.responseCode !in 200..299) return@withContext null
            val out = java.io.File.createTempFile("tts_", ".wav", cacheDir)
            conn.inputStream.use { i -> FileOutputStream(out).use { o -> i.copyTo(o) } }
            out.absolutePath
        } catch (_: Exception) { null }
        finally { conn.disconnect() }
    }
