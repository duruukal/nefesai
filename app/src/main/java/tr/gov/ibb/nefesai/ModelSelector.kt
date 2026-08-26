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
            remoteUrl = "https://huggingface.co/Mungert/gemma-3-4b-it-qat-q4_0-GGUF/resolve/main/gemma-3-4b-it-qat-q4_0-iq2_xs.gguf?download=true"
        ),
        ModelDefinition(
            ramClass = DeviceRAMClass.STANDARD,
            filename = NefesConstants.MODEL_NAME,
            remoteUrl = "https://huggingface.co/Mungert/gemma-3-4b-it-qat-q4_0-GGUF/resolve/main/gemma-3-4b-it-qat-q4_0-iq2_xs.gguf?download=true"
        ),
        ModelDefinition(
            ramClass = DeviceRAMClass.HIGH,
            filename = NefesConstants.MODEL_NAME,
            remoteUrl = "https://huggingface.co/Mungert/gemma-3-4b-it-qat-q4_0-GGUF/resolve/main/gemma-3-4b-it-qat-q4_0-iq2_xs.gguf?download=true"
        )
    )

    fun definition(ramClass: DeviceRAMClass): ModelDefinition =
        models.firstOrNull { it.ramClass == ramClass } ?: models[0]

    fun definition(context: android.content.Context): ModelDefinition =
        definition(DeviceRAMClass.getCurrent(context))
}