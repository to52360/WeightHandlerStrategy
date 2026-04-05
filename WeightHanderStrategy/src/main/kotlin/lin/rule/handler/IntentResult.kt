package lin.rule.handler


/**
 * 可有意图的结果
 */
sealed interface IntentResult
class EnableResult(
    val weight: Double, val modifyCard: ComboCardAction? = null
) : IntentResult


//跳过
object SkipResult : IntentResult

//强制停止
object StopResult : IntentResult












