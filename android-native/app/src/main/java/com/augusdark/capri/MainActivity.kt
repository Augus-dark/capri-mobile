package com.augusdark.capri

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64

private val CapriBg = Color(0xFF122B2A)
private val CapriPanel = Color(0xFF1B3C3A)
private val CapriAccent = Color(0xFF8ED5C4)
private val CapriText = Color(0xFFEAF4F1)
private val CapriMuted = Color(0xFFAAC0BA)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CapriApp() }
    }
}

data class ChatMessage(val role: String, val content: String)

class CapriViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("capri_native", 0)
    private val http = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    var messages by mutableStateOf(loadMessages())
        private set
    var baseUrl by mutableStateOf(prefs.getString("base_url", "https://api.groq.com/openai/v1") ?: "")
    var model by mutableStateOf(prefs.getString("model", "openai/gpt-oss-20b") ?: "")
    var apiKey by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    init { apiKey = runCatching { SecretBox.decrypt(prefs.getString("key", null)) }.getOrDefault("") }

    fun updateBaseUrl(value: String) { baseUrl = value }
    fun updateModel(value: String) { model = value }
    fun updateApiKey(value: String) { apiKey = value }

    fun saveSettings() {
        prefs.edit()
            .putString("base_url", baseUrl.trim().trimEnd('/'))
            .putString("model", model.trim())
            .putString("key", SecretBox.encrypt(apiKey.trim()))
            .apply()
        error = "Configurações salvas neste dispositivo."
    }

    fun newChat() {
        messages = emptyList()
        persist()
        error = null
    }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || busy) return
        if (apiKey.isBlank()) {
            error = "Adicione sua chave de API em Configurações antes de conversar."
            return
        }
        val updated = messages + ChatMessage("user", prompt)
        messages = updated
        persist()
        busy = true
        error = null
        viewModelScope.launch {
            try {
                val answer = withContext(Dispatchers.IO) { requestAnswer(updated) }
                messages = messages + ChatMessage("assistant", answer)
                persist()
            } catch (t: Throwable) {
                error = t.message ?: "Não foi possível conectar ao serviço de IA."
            } finally {
                busy = false
            }
        }
    }

    private fun requestAnswer(history: List<ChatMessage>): String {
        val endpoint = baseUrl.trim().trimEnd('/') + "/chat/completions"
        val arr = JSONArray()
        val memories = runCatching { JSONArray(prefs.getString("memories", "[]")) }.getOrDefault(JSONArray())
        val memoryText = (0 until memories.length()).joinToString("\\n") { "- " + memories.optString(it) }
        val systemPrompt = buildString {
            append("Você é a Capri, uma assistente virtual simpática no celular do usuário. ")
            append("Responda em português do Brasil, em 1 a 3 frases curtas, sem markdown. ")
            append("Use as ferramentas quando o usuário pedir algo. Nunca peça nem guarde senhas ou dados sensíveis. ")
            append("Nunca invente que fez algo. Você foi criada por Augusto; se perguntarem quem te criou, diga isso. ")
            append("Agora é ").append(java.time.ZonedDateTime.now().toString()).append(".")
            if (memoryText.isNotBlank()) append("\\n\\nO que você lembra do usuário:\\n").append(memoryText)
        }
        arr.put(JSONObject().put("role", "system").put("content", systemPrompt))
        history.takeLast(12).forEach { msg ->
            arr.put(JSONObject().put("role", msg.role).put("content", msg.content))
        }
        val body = JSONObject()
            .put("model", model.trim())
            .put("messages", arr)
            .put("stream", false)
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .header("Content-Type", "application/json")
            .post(body)
            .build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull()
                throw IllegalStateException(detail?.takeIf { !it.isNullOrBlank() } ?: "Erro HTTP ${response.code}. Confira endpoint, modelo e chave.")
            }
            val root = JSONObject(raw)
            return root.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content")
                .ifBlank { "A API retornou uma resposta vazia." }
        }
    }

    private fun loadMessages(): List<ChatMessage> = runCatching {
        val a = JSONArray(prefs.getString("history", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            ChatMessage(o.getString("role"), o.getString("content"))
        }
    }.getOrDefault(emptyList())

    private fun persist() {
        val a = JSONArray()
        messages.takeLast(100).forEach { a.put(JSONObject().put("role", it.role).put("content", it.content)) }
        prefs.edit().putString("history", a.toString()).apply()
    }
}

private object SecretBox {
    private const val ALIAS = "capri_api_key_v1"
    private fun key(): SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
    fun encrypt(value: String): String {
        if (value.isBlank()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }
    fun decrypt(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, 12)
        val payload = bytes.copyOfRange(12, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(payload), Charsets.UTF_8)
    }
}

@Composable
fun CapriApp(vm: CapriViewModel = viewModel()) {
    var settings by remember { mutableStateOf(false) }
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = CapriAccent,
            background = CapriBg,
            surface = CapriPanel,
            onPrimary = CapriBg,
            onBackground = CapriText,
            onSurface = CapriText
        )
    ) {
        Surface(Modifier.fillMaxSize(), color = CapriBg) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().background(CapriPanel).padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.SmartToy, null, tint = CapriAccent, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Capri", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(if (settings) "Configurações" else "Assistente virtual", color = CapriMuted, style = MaterialTheme.typography.labelSmall)
                    }
                    if (!settings) {
                        IconButton(onClick = { vm.newChat() }) { Icon(Icons.Default.Add, "Nova conversa") }
                    }
                    IconButton(onClick = { settings = !settings }) {
                        Icon(Icons.Default.Settings, "Configurações", tint = if (settings) CapriAccent else CapriText)
                    }
                }
                if (settings) SettingsScreen(vm, Modifier.weight(1f))
                else ChatScreen(vm, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ChatScreen(vm: CapriViewModel, modifier: Modifier = Modifier) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(vm.messages.size, vm.busy) {
        val count = vm.messages.size + if (vm.busy) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }
    Column(modifier.fillMaxSize()) {
        if (vm.messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.SmartToy, null, tint = CapriAccent, modifier = Modifier.size(52.dp))
                    Spacer(Modifier.height(14.dp))
                    Text("Olá! Eu sou a Capri.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("Como posso ajudar hoje?", color = CapriMuted)
                    Spacer(Modifier.height(20.dp))
                    Text("Configure seu provedor de IA para começar.", color = CapriMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(vm.messages) { message -> MessageBubble(message) }
                if (vm.busy) item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = CapriAccent, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Capri está pensando…", color = CapriMuted)
                    }
                }
            }
        }
        vm.error?.let { msg ->
            Text(msg, color = if (msg.startsWith("Configurações")) CapriAccent else Color(0xFFFFB4AB),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall)
        }
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Pergunte à Capri…") },
                shape = RoundedCornerShape(26.dp),
                maxLines = 5,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CapriAccent,
                    unfocusedBorderColor = CapriMuted.copy(alpha = .45f),
                    focusedTextColor = CapriText,
                    unfocusedTextColor = CapriText
                )
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = { vm.send(draft); draft = "" },
                enabled = draft.isNotBlank() && !vm.busy,
                modifier = Modifier.size(52.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = CapriAccent, contentColor = CapriBg)
            ) { Icon(Icons.Default.ArrowUpward, "Enviar") }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val user = message.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (user) Color(0xFF28534D) else CapriPanel,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.widthIn(max = 330.dp)
        ) {
            Text(
                message.content,
                modifier = Modifier.padding(horizontal = 15.dp, vertical = 12.dp),
                color = CapriText,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun SettingsScreen(vm: CapriViewModel, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Conexão com a IA", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Use o endpoint compatível com a API OpenAI do seu provedor (por exemplo, Groq ou OpenRouter).", color = CapriMuted)
        OutlinedTextField(
            value = vm.baseUrl, onValueChange = vm::updateBaseUrl,
            modifier = Modifier.fillMaxWidth(), label = { Text("URL base da API") }, singleLine = true
        )
        OutlinedTextField(
            value = vm.model, onValueChange = vm::updateModel,
            modifier = Modifier.fillMaxWidth(), label = { Text("ID do modelo") }, singleLine = true
        )
        OutlinedTextField(
            value = vm.apiKey, onValueChange = vm::updateApiKey,
            modifier = Modifier.fillMaxWidth(), label = { Text("Chave da API") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        Button(onClick = vm::saveSettings, modifier = Modifier.fillMaxWidth()) { Text("Salvar configurações") }
        Text("A chave é criptografada com uma chave do Android Keystore e armazenada neste aparelho. O histórico fica salvo localmente.", color = CapriMuted, style = MaterialTheme.typography.bodySmall)
        Text("Observação: este primeiro núcleo já conversa com APIs compatíveis com OpenAI. Recursos do PWA como voz, câmera, lembretes e ferramentas ainda precisam ser migrados para APIs Android nativas.", color = CapriMuted, style = MaterialTheme.typography.bodySmall)
    }
}
