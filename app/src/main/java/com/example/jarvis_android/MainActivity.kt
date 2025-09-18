package com.example.jarvis_android

import android.content.Context
import android.media.MediaPlayer
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

// ---- Настройки твоих серверов ----
private const val CHAT_URL = "http://192.168.100.8:1234/v1/chat/completions"
private const val CHAT_MODEL = "openai/gpt-oss-20b"

private const val TTS_BASE = "http://192.168.100.8:8001"     // <-- твой FastAPI TTS
private const val TTS_CLONE = "$TTS_BASE/v1/clone"
private const val TTS_SAY   = "$TTS_BASE/v1/tts"
private const val VOICE_ID  = "jarvis"                       // имя спикера на сервере

data class UiMsg(val role: String, val content: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SimpleChatWithTts() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleChatWithTts() {
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var useVoice by remember { mutableStateOf(true) }
    var cloned by remember { mutableStateOf(false) }
    var history by remember {
        mutableStateOf(listOf(UiMsg("system",
            "Ты помощник Jarvis на Android. Отвечай кратко и по делу.")))
    }
    val ui = history.filter { it.role != "system" }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    // MediaPlayer для воспроизведения
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(Unit) { onDispose { player?.release() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Jarvis — LM Studio + TTS") },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Голос")
                        Spacer(Modifier.width(6.dp))
                        Switch(checked = useVoice, onCheckedChange = { useVoice = it })
                        Spacer(Modifier.width(12.dp))
                        Button(
                            enabled = !cloned,
                            onClick = {
                                scope.launch {
                                    val ok = cloneVoiceFromAsset(context, "instruction.wav", VOICE_ID)
                                    cloned = ok
                                    if (!ok) {
                                        // покажем статус в чате
                                        history = history + UiMsg("assistant", "Клонирование не удалось. Проверь TTS сервер и voice_ref.wav.")
                                    } else {
                                        history = history + UiMsg("assistant", "Голос склонирован как \"$VOICE_ID\".")
                                    }
                                }
                            }
                        ) { Text(if (cloned) "Готово" else "Клонировать голос") }
                        Spacer(Modifier.width(8.dp))
                    }
                }
            )
        },
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
                            val reply = callLlm(history)
                            history = history + UiMsg("assistant", reply)
                            sending = false

                            if (useVoice) {
                                // Если голос ещё не клонировали — попробуем разово
                                if (!cloned) {
                                    val ok = cloneVoiceFromAsset(context, "instruction.wav", VOICE_ID)
                                    cloned = ok
                                }
                                // Синтез и проигрывание
                                val wavPath = ttsSpeakToFile(reply, VOICE_ID, context.cacheDir)
                                if (wavPath != null) {
                                    player?.release()
                                    player = MediaPlayer().apply {
                                        setDataSource(wavPath)
                                        setOnPreparedListener { start() }
                                        setOnCompletionListener {
                                            it.release()
                                            player = null
                                            // удалим временный файл
                                            try { File(wavPath).delete() } catch (_: Exception) {}
                                        }
                                        prepareAsync()
                                    }
                                } else {
                                    history = history + UiMsg("assistant", "TTS не удалось (см. логи).")
                                }
                            }
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

/* -------------------- LLM (как раньше) -------------------- */

private suspend fun callLlm(history: List<UiMsg>): String = withContext(Dispatchers.IO) {
    val url = URL(CHAT_URL)
    val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connectTimeout = 30000
        readTimeout = 120000
    }

    val msgs = JSONArray().also { arr ->
        history.forEach { msg ->
            arr.put(JSONObject().apply {
                put("role", msg.role)
                put("content", msg.content)
            })
        }
    }
    val body = JSONObject().apply {
        put("model", CHAT_MODEL)
        put("messages", msgs)
        put("temperature", 0.3)
        put("max_tokens", 512)
        put("stream", false)
    }.toString()

    try {
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val respText = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        if (code !in 200..299) return@withContext "HTTP $code: $respText"

        val root = JSONObject(respText)
        val choices = root.optJSONArray("choices") ?: return@withContext "Пустой ответ"
        val first = choices.optJSONObject(0) ?: return@withContext "Пустой ответ"
        val message = first.optJSONObject("message")
        val content = message?.optString("content")
        if (!content.isNullOrBlank()) content else first.optString("text", "Пустой ответ")
    } catch (e: Exception) {
        "Ошибка: ${e.message}"
    } finally {
        conn.disconnect()
    }
}

/* -------------------- TTS: clone + speak -------------------- */

/** Одноразовое клонирование голоса с отправкой voice_ref.wav из assets */
private suspend fun cloneVoiceFromAsset(ctx: Context, assetName: String, voiceId: String): Boolean =
    withContext(Dispatchers.IO) {
        // читаем ассет в память
        val bytes = try {
            ctx.assets.open(assetName).use { it.readBytes() }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
        // multipart/form-data: fields voice_id + file ref_audio
        val boundary = "----JarvisBoundary${System.currentTimeMillis()}"
        val url = URL(TTS_CLONE)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connectTimeout = 30000
            readTimeout = 120000
        }

        try {
            DataOutputStream(conn.outputStream).use { out ->
                fun writeField(name: String, value: String) {
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    out.writeBytes("$value\r\n")
                }
                fun writeFile(name: String, filename: String, data: ByteArray) {
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"$name\"; filename=\"$filename\"\r\n")
                    out.writeBytes("Content-Type: audio/wav\r\n\r\n")
                    out.write(data)
                    out.writeBytes("\r\n")
                }

                writeField("voice_id", voiceId)
                writeFile("ref_audio", assetName, bytes)

                out.writeBytes("--$boundary--\r\n")
                out.flush()
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (code !in 200..299) {
                println("CLONE ERROR $code: $resp")
                return@withContext false
            }
            // ожидаем {"ok":true, "voice_id": "..."}
            JSONObject(resp).optBoolean("ok", false)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            conn.disconnect()
        }
    }

/** Синтезирует wav-файл в cacheDir и возвращает путь к нему (или null при ошибке) */
private suspend fun ttsSpeakToFile(text: String, voiceId: String, cacheDir: File): String? =
    withContext(Dispatchers.IO) {
        val url = URL(TTS_SAY)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            connectTimeout = 30000
            readTimeout = 120000
        }

        // Формируем body: voice_id, text, speed, language
        val params = listOf(
            "text" to text,
            "voice_id" to voiceId,
            "speed" to "0.9",
            "language" to "ru"
        ).joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }

        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(params) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            if (code !in 200..299) {
                val err = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                println("TTS ERROR $code: $err")
                return@withContext null
            }

            // Сохраняем WAV во временный файл
            val outFile = File.createTempFile("tts_", ".wav", cacheDir)
            BufferedInputStream(stream).use { inp ->
                FileOutputStream(outFile).use { fos ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val r = inp.read(buf)
                        if (r <= 0) break
                        fos.write(buf, 0, r)
                    }
                    fos.flush()
                }
            }
            outFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            conn.disconnect()
        }
    }
