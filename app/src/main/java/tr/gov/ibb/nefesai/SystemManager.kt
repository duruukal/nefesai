package tr.gov.ibb.nefesai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tr.gov.ibb.nefesai.internal.LlamaInferenceEngine
import java.util.Locale

data class RAGDocument(
    val id: String,
    val content: String,
    val keywords: List<String>,
    var embedding: FloatArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RAGDocument
        if (id != other.id) return false
        if (content != other.content) return false
        if (keywords != other.keywords) return false
        if (embedding != null) {
            if (other.embedding == null) return false
            if (!embedding.contentEquals(other.embedding)) return false
        } else if (other.embedding != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + keywords.hashCode()
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        return result
    }
}

class SystemManager private constructor() {

    companion object {
        private const val TAG = "NefesAiSystemManager"
        private const val CACHE_FILE = "embeddings_cache.json"

        @JvmStatic
        val shared: SystemManager by lazy { SystemManager() }
    }

    private var baseSystemPrompt: String = ""
    private var emergencyDocuments: MutableList<RAGDocument> = mutableListOf()
    private var isFirstChatMessage: Boolean = true

    fun initialize(context: Context) {
        loadSystemPrompt()
        Thread {
            try {
                val loaded = loadEmergencyDocumentsFromCache(context)
                Log.i(TAG, if (loaded) "♻️ Dökümanlar yüklendi: ${emergencyDocuments.size}" else "⚠️ Dökümanlar yüklenemedi")
            } catch (e: Exception) {
                Log.e(TAG, "Döküman yükleme hatası!", e)
            }
        }.start()
    }

    fun resetChatSession() {
        isFirstChatMessage = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                LlamaInferenceEngine.getInstance().resetConversation()
                Log.i(TAG, "🧹 Chat oturumu ve model context'i sıfırlandı.")
            } catch (e: Exception) {
                Log.e(TAG, "Context sıfırlanırken hata oluştu", e)
            }
        }
    }

    private fun loadSystemPrompt() {
        baseSystemPrompt = """
            Sen Nefes AI'sın. İnternete bağlı olmayan bir afet ve acil durum destek asistanısın. 
            Yalnızca uygulama içindeki doğrulanmış afet güvenliği içeriklerini kullan. 
            Kesin teşhis koyma, bina hasarının güvenli olduğunu söyleme, riskli müdahale talimatı verme. 
            Acil ve hayati risk, yaralanma, yangın, gaz kaçağı veya mahsur kalma durumunda kullanıcıyı 112'ye yönlendir. 
            Bilgi yoksa tahmin yürütme; güvenli ve kısa bir uyarı ver.
        """.trimIndent()
    }

    private fun loadEmergencyDocumentsFromCache(context: Context): Boolean {
        val json = try {
            context.assets.open(CACHE_FILE).bufferedReader().use { it.readText() }
        } catch (ignored: Exception) {
            Log.w(TAG, "📂 $CACHE_FILE assets içinde bulunamadı.")
            return false
        }

        val root = JSONObject(json)
        val loaded = mutableListOf<RAGDocument>()

        for (id in root.keys()) {
            val entry = root.getJSONObject(id)
            val content = entry.optString("content", "")
            if (content.isEmpty()) continue

            val embArr = entry.optJSONArray("embedding") ?: continue
            val embedding = FloatArray(embArr.length()) { i -> embArr.getDouble(i).toFloat() }

            val keywordsArr = entry.optJSONArray("keywords")
            val keywords = if (keywordsArr != null)
                List(keywordsArr.length()) { i -> keywordsArr.getString(i) }
            else emptyList()

            loaded.add(RAGDocument(id, content, keywords, embedding))
        }

        emergencyDocuments = loaded
        return loaded.isNotEmpty()
    }

    suspend fun retrieveRAGContext(query: String): String? = withContext(Dispatchers.Default) {
        val trLocale = Locale("tr", "TR")
        val lowercaseQuery = query.lowercase(trLocale)
        val queryWords = lowercaseQuery
            .split(Regex("[^a-zçğıöşü0-9]+"))
            .filter { it.length > 2 }
            .toSet()

        val keywordMatches = emergencyDocuments.filter { doc ->
            doc.keywords.any { kw -> queryWords.contains(kw.lowercase(trLocale)) }
        }

        if (keywordMatches.isNotEmpty()) {
            Log.i(TAG, "🎯 [KEYWORD RAG] ${keywordMatches.size} döküman eşleşti: ${keywordMatches.map { it.id }}")
            return@withContext keywordMatches.joinToString("\n\n---\n\n") { it.content.trim() }
        }

        Log.w(TAG, "⚠️ [RAG BAŞARISIZ] Eşik altında kaldı.")
        null
    }

    fun buildNextPrompt(userText: String, ragContext: String?): String {
        val tpl = NefesConstants.currentTemplate

        val emergencyDataSection = buildString {
            append("\n\n<afet_rehberi_verisi>\n")
            if (ragContext != null) {
                append("DURUM: Soruyla ilgili resmi afet ve acil durum rehberi verisi aşağıda sağlanmıştır:\n")
                append(ragContext.trim())
            } else {
                append("REHBER BİLGİSİ BULUNAMADI")
            }
            append("\n</afet_rehberi_verisi>\n\n")
            append("Kullanıcı Sorusu: ").append(userText.trim())
        }

        return buildString {
            append(tpl.bos)
            if (tpl.supportsSystemRole) {
                append(tpl.systemPrefix).append(baseSystemPrompt.trim()).append(tpl.systemSuffix)
                append(tpl.userPrefix).append(emergencyDataSection.trim()).append(tpl.userSuffix)
            } else {
                append(tpl.userPrefix).append(baseSystemPrompt.trim()).append(emergencyDataSection).append(tpl.userSuffix)
            }
            append(tpl.assistantPrefix)
            isFirstChatMessage = false
        }
    }

    private fun checkEmergencyPriority(userQuery: String): Boolean {
        val lowerQuery = userQuery.lowercase(Locale("tr", "TR"))
        val criticalKeywords = listOf("yangın", "gaz", "enkaz", "yaralı", "kan", "nefes alamıyorum", "mahsur", "deprem oluyor")

        for (keyword in criticalKeywords) {
            if (lowerQuery.contains(keyword)) {
                Log.w(TAG, "⚠️ Kritik acil durum kelimesi algılandı: $keyword")
                return true
            }
        }
        return false
    }

    suspend fun prepareResponse(userText: String): ChatDecision = withContext(Dispatchers.Default) {
        if (checkEmergencyPriority(userText)) {
            return@withContext ChatDecision.DirectResponse(
                "⚠️ ACİL DURUM TESPİT EDİLDİ: Lütfen sakin olun, güvenli bir alana geçin ve hemen 112 Acil Yardım hattını arayın!"
            )
        }

        val ragContext = retrieveRAGContext(query = userText)
        ChatDecision.Generate(prompt = buildNextPrompt(userText, ragContext))
    }
}