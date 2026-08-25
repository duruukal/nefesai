package tr.gov.ibb.nefesai

data class ModelDefinition(
    val ramClass: DeviceRAMClass,
    val filename: String,
    val remoteUrl: String
)

object ModelRegistry {
    private val models = listOf(
        ModelDefinition(
            ramClass = DeviceRAMClass.LOW,
            filename = NefesConstants.MODEL_NAME,
            remoteUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf?download=true"
        ),
        ModelDefinition(
            ramClass = DeviceRAMClass.STANDARD,
            filename = NefesConstants.MODEL_NAME,
            remoteUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf?download=true"
        ),
        ModelDefinition(
            ramClass = DeviceRAMClass.HIGH,
            filename = NefesConstants.MODEL_NAME,
            remoteUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf?download=true"
        )
    )

    fun definition(ramClass: DeviceRAMClass): ModelDefinition =
        models.firstOrNull { it.ramClass == ramClass } ?: models[0]

    fun definition(context: android.content.Context): ModelDefinition =
        definition(DeviceRAMClass.getCurrent(context))
}