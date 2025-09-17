package com.example.jarvis_android

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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

private const val BASE_URL = "http://192.168.100.8:1234/v1/chat/completions"
private const val MODEL_NAME = "openai/gpt-oss-20b"

data class UiMsg(val role: String, val content: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SimpleChat() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleChat() {
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var history by remember {
        mutableStateOf(listOf(UiMsg("system",
            "Ты помощник Jarvis на Android. Отвечай кратко и по делу.")))
    }
    val ui = history.filter { it.role != "system" }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Jarvis — LM Studio") }) },
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
                        }
                    }
                ) { Text(if (sending) "..." else "Отпр.") }
            }
        }
    ) { inner ->
        if (ui.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                Text("Подключено к $BASE_URL\nМодель: $MODEL_NAME", textAlign = TextAlign.Center)
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

/** Постим в LM Studio без внешних библиотек */
private suspend fun callLlm(history: List<UiMsg>): String = withContext(Dispatchers.IO) {
    val url = URL(BASE_URL)
    val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
        // setRequestProperty("Authorization", "Bearer YOUR_KEY") // если бы требовалось
        connectTimeout = 30000
        readTimeout = 120000
    }

    // Сформировать JSON тела
    val msgs = JSONArray().also { arr ->
        history.forEach { msg ->
            val obj = JSONObject().apply {
                put("role", msg.role)
                put("content", msg.content)
            }
            arr.put(obj)
        }
    }
    val body = JSONObject().apply {
        put("model", MODEL_NAME)
        put("messages", msgs)
        put("temperature", 0.3)
        put("max_tokens", 512)
        put("stream", false)
    }.toString()

    try {
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val respText = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { br ->
            buildString { br.forEachLine { append(it) } }
        }
        if (code !in 200..299) return@withContext "HTTP $code: $respText"

        // Разбор ответа OpenAI-совместимого формата
        val root = JSONObject(respText)
        val choices = root.optJSONArray("choices") ?: return@withContext "Пустой ответ"
        val first = choices.optJSONObject(0) ?: return@withContext "Пустой ответ"
        val message = first.optJSONObject("message")
        val content = message?.optString("content")
        if (!content.isNullOrBlank()) content
        else first.optString("text", "Пустой ответ")
    } catch (e: Exception) {
        "Ошибка: ${e.message}"
    } finally {
        conn.disconnect()
    }
}
