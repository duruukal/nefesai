package tr.gov.ibb.nefesai

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.Charset

data class RagItem(val id: Int, val topic: String, val title: String, val content: String)

object RAGManager {
    private const val TAG = "RAGManager"
    private val guideItems = mutableListOf<RagItem>()
    private val keywordMapping = mutableMapOf<String, MutableList<Int>>() // Kelime -> İlgili ID'ler

    fun initialize(context: Context) {
        if (guideItems.isNotEmpty()) return
        try {
            // 1. Ana Rehber Dosyasını Oku
            val inputStream = context.assets.open("afad_deprem_rehberi.json")
            val size = inputStream.available()
            val buffer = ByteArray(size)
            inputStream.read(buffer)
            inputStream.close()
            val jsonString = String(buffer, Charset.forName("UTF-8"))

            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                guideItems.add(
                    RagItem(
                        id = obj.getInt("id"),
                        topic = obj.optString("topic", "Genel"),
                        title = obj.optString("title", "Acil Durum"),
                        content = obj.getString("content")
                    )
                )
            }
            Log.i(TAG, "✅ AFAD Rehberi Yüklendi: ${guideItems.size} kayıt.")

            // 2. Ekstra Anahtar Kelimeler Dosyasını Oku (Eğer varsa)
            try {
                val kwInputStream = context.assets.open("anahtar_kelimeler.json")
                val kwSize = kwInputStream.available()
                val kwBuffer = ByteArray(kwSize)
                kwInputStream.read(kwBuffer)
                kwInputStream.close()
                val kwString = String(kwBuffer, Charset.forName("UTF-8"))

                // Yapısına göre JSONArray veya JSONObject olabilir, genel işleyiş:
                if (kwString.trim().startsWith("[")) {
                    val kwArray = JSONArray(kwString)
                    // Gerekirse anahtar kelime eşlemeleri burada işlenebilir
                }
                Log.i(TAG, "✅ Anahtar kelimeler dosyası okundu.")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ anahtar_kelimeler.json okunamadı veya gerek duyulmadı: ${e.message}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ RAG Yüklenirken Hata", e)
        }
    }

    fun searchContext(query: String): String {
        if (guideItems.isEmpty()) {
            return "RESMİ AFAD PROSEDÜRü: Sakin olun, paniğe kapılmayın ve güvenliğinizi sağlayın. Acil durumlarda 112'yi arayın."
        }

        val lowerQuery = query.lowercase()
        // Kullanıcının yazdığı sorguyu kelimelere ayır (2 harften uzun olanlar)
        val keywords = lowerQuery.split(" ", ",", ".", "?", "!", "-", "'").filter { it.length > 1 }

        // Her öğeyi kullanıcının sorgusuna göre puanla
        val scoredItems = guideItems.map { item ->
            var score = 0
            val searchableText = "${item.topic} ${item.title} ${item.content}".lowercase()

            for (kw in keywords) {
                if (searchableText.contains(kw)) {
                    score += 2
                }
            }

            // Kritik kelimeler için ekstra akıllı puanlama (Nokta atışı eşleşme)
            if (lowerQuery.contains("yarala") && searchableText.contains("yara")) score += 6
            if (lowerQuery.contains("kan") && searchableText.contains("kanama")) score += 6
            if (lowerQuery.contains("enkaz") && searchableText.contains("enkaz")) score += 6
            if (lowerQuery.contains("deprem") && searchableText.contains("deprem")) score += 4

            item to score
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(2) // En iyi 2 eşleşmeyi al

        return if (scoredItems.isNotEmpty()) {
            val contextBuilder = StringBuilder("RESMİ AFAD/AKUT REHBER BAĞLAMI:\n")
            for ((item, _) in scoredItems) {
                contextBuilder.append("- Konu: ${item.topic} | Başlık: ${item.title}\n  İçerik: ${item.content}\n\n")
            }
            contextBuilder.toString()
        } else {
            "RESMİ AFAD PROSEDÜRÜ: Sakin olun, paniğe kapılmayın ve güvenliğinizi sağlayın. Acil durumlarda 112'yi arayın."
        }
    }
}