package testRuleRegistration


// 1. 定义第一个上下文：日志组件
class GameLogger {
    fun info(msg: String) {
        println("[GameLog] $msg")
    }
}

// 2. 定义第二个上下文：战局快照 (用纯 val 的数据类，也就是我们刚才讨论的模式)
data class WarStatusSnapshot(
    val meSumAtc: Int,
    val rivalSumAtc: Int,
    val excessDamage: Int
) {
    fun isAdv(): Boolean = excessDamage > 0
}

// ==========================================
// 重点来了：定义一个同时依赖 GameLogger 和 WarStatusSnapshot 的规则函数
// 这个函数不属于任何类，但它能直接调用上下文中提供的 info() 和 excessDamage！
// ==========================================
context(logger: GameLogger, status: WarStatusSnapshot)
fun evaluateBoardAndAction() {
    // 使用时，通过显式的参数名调用，彻底告别作用域混乱
    logger.info("开始评估场面...")

    if (status.isAdv()) {
        logger.info("场面优势！伤害溢出: ${status.excessDamage}。建议：全军突击！")
    } else {
        logger.info("敌方攻击力高达 ${status.rivalSumAtc}，我方处于劣势。建议：防守！")
    }
}

// 3. 引擎层：如何调用它？
fun main() {
    val logger = GameLogger()
    val currentWarStatus = WarStatusSnapshot(meSumAtc = 10, rivalSumAtc = 5, excessDamage = 8)

    println("--- 推演引擎启动 ---")

    // 使用 with() 将对象引入当前作用域
    // 只有当 GameLogger 和 WarStatusSnapshot 都在作用域内时，编译器才允许调用 evaluateBoardAndAction()
    with(logger) {
        with(currentWarStatus) {
            // 魔法发生在这里：它自动感知到了外部的 with 环境
            evaluateBoardAndAction()
        }
    }

    println("--- 推演引擎结束 ---")
}