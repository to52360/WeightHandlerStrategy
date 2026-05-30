package lin.domain.use

import lin.bean.ComboCard

data class UseContext(
    val card: ComboCard,
    var replanRequested: Boolean = false,
    var shouldReplan: Boolean = false,
    var stateChanged: Boolean = false,
    var extraAwaitMillis: Long = 0,
    var useSucceeded: Boolean = false,
    var discoverTicket: DiscoverTicket? = null
) {
    fun toResult(): UseCardResult {
        return UseCardResult(
            card = card,
            succeeded = useSucceeded,
            shouldReplan = shouldReplan,
            stateChanged = stateChanged
        )
    }
}

data class UseCardResult(
    val card: ComboCard,
    val succeeded: Boolean,
    val shouldReplan: Boolean,
    val stateChanged: Boolean
)
