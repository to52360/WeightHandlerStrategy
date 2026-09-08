package lin.utils

import lin.config.EngineConfig
import lin.myLog

/**
 * 决策日志统一出口（T-PV-002）。
 *
 * 选牌分量（T-PV-003）/ 门控落选原因（T-PV-004）/ 排序明细（T-PV-005）**三处共用这一个入口**，
 * 避免各处各写一遍开关判断与 logger 选择。
 *
 * ## 为什么是引擎侧开关，而不是调 logback 的 debug
 *
 * 引擎 jar **不带 `logback.xml`**，日志级别由宿主控制——`debug` 级在部署侧可能根本输出不出来
 * （排序追溯日志 `UsePlanOrderer.logOrderDecision` 就卡在这：内容齐全但 debug 级默认关，
 * 部署侧又未必能开）。故改由引擎自己的开关 `decision.log.enabled` 控制，统一以 **info** 输出。
 *
 * 用法：
 * ```
 * DecisionLog.log { "选牌 ${card.cardId()} baseValue=$baseValue powerWeight=${card.powerWeight}" }
 * ```
 *
 * 开关关闭时 `block` 不执行（零字符串拼接成本），故可放心用模板字符串。
 */
object DecisionLog {

    /** 决策日志是否启用（`decision.log.enabled`，默认 false）。 */
    val enabled: Boolean get() = EngineConfig.decisionLogEnabled

    inline fun log(crossinline block: () -> String) {
        if (enabled) myLog.info { block() }
    }
}
