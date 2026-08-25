package tr.gov.ibb.nefesai

enum class ChatTemplateType(val id: String, val displayName: String) {
    CHAT_ML("chatML", "ChatML (Qwen, Yi, OpenHermes...)"),
    MISTRAL_INSTRUCT("mistralInstruct", "Mistral / Ministral Instruct"),
    LLAMA3("llama3", "Llama 3.x Instruct"),
    GEMMA("gemma", "Gemma 2 / 3"),
    PHI3("phi3", "Phi-3 / Phi-3.5");

    companion object {
        // ID'ye göre şablon tipi bulmak gerekirse (Örn: Cache/SharedPreferences için)
        fun fromId(id: String): ChatTemplateType? {
            return entries.find { it.id == id }
        }
    }
}

data class ChatTemplate(
    val bos: String,
    val systemPrefix: String,
    val systemSuffix: String,
    val userPrefix: String,
    val userSuffix: String,
    val assistantPrefix: String,
    val assistantSuffix: String,
    val stopStrings: List<String>,      // Çıkışta yakalanıp üretimi durduracak etiketler
    val supportsSystemRole: Boolean     // false ise system promptu ilk user mesajına gömülür
) {
    companion object {
        fun getTemplate(type: ChatTemplateType): ChatTemplate {
            return when (type) {
                ChatTemplateType.CHAT_ML -> ChatTemplate(
                    bos = "",
                    systemPrefix = "<|im_start|>system\n",
                    systemSuffix = "<|im_end|>\n",
                    userPrefix = "<|im_start|>user\n",
                    userSuffix = "<|im_end|>\n",
                    assistantPrefix = "<|im_start|>assistant\n",
                    assistantSuffix = "<|im_end|>\n",
                    stopStrings = listOf("<|im_start|>", "<|im_end|>"),
                    supportsSystemRole = true
                )

                ChatTemplateType.MISTRAL_INSTRUCT -> ChatTemplate(
                    bos = "<s>",
                    systemPrefix = "[SYSTEM_PROMPT]",
                    systemSuffix = "[/SYSTEM_PROMPT]",
                    userPrefix = "[INST]",
                    userSuffix = "[/INST]",
                    assistantPrefix = "",
                    assistantSuffix = "</s>",
                    stopStrings = listOf("</s>", "[INST]", "[/INST]", "[SYSTEM_PROMPT]", "[/SYSTEM_PROMPT]"),
                    supportsSystemRole = true
                )

                ChatTemplateType.LLAMA3 -> ChatTemplate(
                    bos = "<|begin_of_text|>",
                    systemPrefix = "<|start_header_id|>system<|end_header_id|>\n\n",
                    systemSuffix = "<|eot_id|>",
                    userPrefix = "<|start_header_id|>user<|end_header_id|>\n\n",
                    userSuffix = "<|eot_id|>",
                    assistantPrefix = "<|start_header_id|>assistant<|end_header_id|>\n\n",
                    assistantSuffix = "<|eot_id|>",
                    stopStrings = listOf("<|eot_id|>", "<|start_header_id|>"),
                    supportsSystemRole = true
                )

                ChatTemplateType.GEMMA -> ChatTemplate(
                    bos = "<bos>",
                    systemPrefix = "",
                    systemSuffix = "",
                    userPrefix = "<start_of_turn>user\n",
                    userSuffix = "<end_of_turn>\n",
                    assistantPrefix = "<start_of_turn>model\n",
                    assistantSuffix = "<end_of_turn>\n",
                    stopStrings = listOf("<end_of_turn>", "</start_of_turn>", "<start_of_turn>", "<eos>"),
                    supportsSystemRole = false
                )

                ChatTemplateType.PHI3 -> ChatTemplate(
                    bos = "",
                    systemPrefix = "<|system|>\n",
                    systemSuffix = "<|end|>\n",
                    userPrefix = "<|user|>\n",
                    userSuffix = "<|end|>\n",
                    assistantPrefix = "<|assistant|>\n",
                    assistantSuffix = "<|end|>\n",
                    stopStrings = listOf("<|end|>", "<|user|>", "<|system|>"),
                    supportsSystemRole = true
                )
            }
        }
    }
}