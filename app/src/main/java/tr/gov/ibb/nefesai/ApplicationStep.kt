package tr.gov.ibb.nefesai

sealed class ChatDecision {
    data class ShowSelectionOptions(val message: String, val options: List<String>) : ChatDecision()
    data class DirectResponse(val message: String) : ChatDecision()
    data class Generate(val prompt: String) : ChatDecision()
}

enum class DisasterState {
    IDLE,
    EARTHQUAKE_DURING,
    EARTHQUAKE_AFTER,
    FIRE,
    GAS_LEAK,
    FIRST_AID,
    EMERGENCY_CONTACT,
    GO_BAG
}

data class ActiveDisasterSession(
    var state: DisasterState = DisasterState.IDLE,
    var lastEmergencyDetail: String = ""
)