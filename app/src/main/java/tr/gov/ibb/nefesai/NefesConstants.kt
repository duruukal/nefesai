package tr.gov.ibb.nefesai

object NefesConstants {
    const val MODEL_NAME = "nefes.gguf"
    const val HEAD_REQUEST_TIMEOUT = 4L // Saniye
    const val DOWNLOAD_REQUEST_TIMEOUT = 60L
    val currentTemplate = ChatTemplate.getTemplate(ChatTemplateType.GEMMA)
}