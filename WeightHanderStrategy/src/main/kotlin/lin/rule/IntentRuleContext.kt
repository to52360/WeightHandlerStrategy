package lin.rule

interface IntentRuleContext

/**
 * 占位符
 */
object DefRuleContext

// 1. 定义规则等级
enum class RuleLevel(val value: Int) {
    CRITICAL(3), // 强制、否决等
    HIGH(2),     // 核心策略
    DEF(1)       // 默认
}

