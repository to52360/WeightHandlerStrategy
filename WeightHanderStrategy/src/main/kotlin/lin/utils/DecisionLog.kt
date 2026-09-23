package lin.utils

import lin.config.EngineConfig
import lin.myLog

/**
 * 日志分类（T-FO-011）：把**高频逐卡明细**按分析场景分档，避免"要看的东西混在一堆里"。
 *
 * 分工原则：
 * - **骨架日志**（每回合一行：执行策略 / 选定组合 / 打出结果）**不进分类**，恒以 info 输出
 *   ——它是复盘的索引，缺了就没法定位回合。
 * - **明细日志**（逐卡分量、落选原因、排序追溯、换牌逐卡归因）走分类开关，默认关。
 *
 * 启用方式（三选一，见 `engine.properties` 的 `log.categories`）：
 * - `log.categories=CHANGE,PICK` 只开这两类；
 * - `decision.log.enabled=true` 等价于**全部类别 + 所有历史明细**（向后兼容旧 SOP）；
 * - `-Dlog.categories=…` 亦可（ConfigStore 只对 props 里已存在的键做系统属性覆盖）。
 */
enum class LogCategory {
    /** 哨兵：不属任何分类。写进 `log.categories` 不产生效果（解析时被剔除）。 */
    NONE,

    /** 起手换牌 / 留牌：逐卡保留与换掉、资格判定归因。 */
    CHANGE,

    /** 选牌：逐卡分值分量（基础 / 树 / 光环 / 其余 / combo 声明）、门控落选原因。 */
    PICK,

    /** 排序：阶段 / 段内权重 / 兜底键的逐卡比较追溯。 */
    ORDER,

    /** 出牌：逐张打出的细节（等待、指向、重规划触发点）。 */
    USE,

    /** 发现：三选一权重与选择结果。 */
    DISCOVER,

    /** 余费填充 / 失败补偿：为什么补、补了哪张。 */
    FILL,

    /** 动画 / 等待（sleep、发现同步、地标点击）：刷屏但对决策判读无价值，默认关。 */
    ANIM,
}

/**
 * 解析 `log.categories`：逗号分隔、**大小写不敏感**、非法值静默忽略（拼错不会炸，但也不会开）。
 *
 * 纯函数（不读配置），便于单测直接覆盖解析规则——`EngineConfig` 是 object，其 store 进程内只初始化一次，
 * 用系统属性在测试里改分类不可靠。
 */
fun parseLogCategories(raw: String?): Set<LogCategory> = raw
    ?.split(',')
    ?.mapNotNull { name -> LogCategory.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } }
    ?.filter { it != LogCategory.NONE }
    ?.toSet()
    ?: emptySet()

/**
 * 决策日志统一出口（T-PV-002 起，T-FO-011 升级为分类）。
 *
 * 选牌分量 / 门控落选原因 / 排序明细 / 换牌归因等**逐卡明细共用这一个入口**，
 * 避免各处各写一遍开关判断与 logger 选择（机制必须单点）。
 *
 * ## 为什么是引擎侧开关，而不是调 logback 的 debug
 *
 * 引擎 jar **不带 `logback.xml`**，日志级别由宿主控制——`debug` 级在部署侧可能根本输出不出来
 * （排序追溯日志 `UsePlanOrderer.logOrderDecision` 就卡在这：内容齐全但 debug 级默认关，
 * 部署侧又未必能开）。故改由引擎自己的开关控制，统一以 **info** 输出。
 *
 * 用法：
 * ```
 * DecisionLog.log(LogCategory.CHANGE) { "保留 ${decision.keepCards.size} 张" }
 * ```
 *
 * 开关关闭时 `block` 不执行（零字符串拼接成本），故可放心用模板字符串。
 */
object DecisionLog {

    /** 全量明细开关（`decision.log.enabled`，默认 false）：开 = 所有分类全开（向后兼容旧 SOP）。 */
    val enabled: Boolean get() = EngineConfig.decisionLogEnabled

    /** 指定分类是否启用 = 全量开关开 **或** 该分类列在 `log.categories`。 */
    fun isEnabled(category: LogCategory): Boolean =
        enabled || category in EngineConfig.logCategories

    /** 无分类的历史入口：只有 [enabled] 生效（保持旧行为，勿再加新调用点）。 */
    inline fun log(crossinline block: () -> String) {
        if (enabled) myLog.info { block() }
    }

    /** 分类入口：指定分类开启（或全量开关开启）时输出。 */
    inline fun log(category: LogCategory, crossinline block: () -> String) {
        if (isEnabled(category)) myLog.info { block() }
    }
}
