package lin.rule.orthogonal

import lin.rule.condition.PipelineAssembler
import lin.rule.context.RuleContext
import lin.rule.context.RuleEnv

/**
 * 管道缓存 Key：包含 Source Key 与各 Transform Step 的累积路径 Key。
 *
 * 提示：条件管道 (Condition) 与评分管道 (Score) 共享 `pipe:src:` 与 `pipe:step:` 键前缀，
 * 只要同 sourceId 与 transforms 链路一致，中间计算结果将自动跨侧复用，极其高效。
 */
data class PipelineCacheKeys(
    val sourceKey: String,
    val stepKeys: List<String>
) {
    companion object {
        fun build(sourceId: String, transforms: List<TransformCall>): PipelineCacheKeys {
            val sourceKey = "pipe:src:$sourceId"
            val cumulativeBuilder = StringBuilder(sourceId)
            val stepKeys = transforms.map { call ->
                cumulativeBuilder.append("->").append(call.transformId)
                if (call.args.isNotEmpty()) {
                    cumulativeBuilder.append("(").append(PipelineAssembler.canonicalArgsString(call.args)).append(")")
                }
                "pipe:step:" + cumulativeBuilder.toString()
            }
            return PipelineCacheKeys(sourceKey, stepKeys)
        }
    }
}

/**
 * 在 [RuleContext] 下通用求值管道并返回最终转换输出对象。
 *
 * - [crossCard] = false (默认)：直连评估求值（零 Map 查找与零 Key 拼接开销）。不开启跨卡缓存，
 *   虽然放弃了极其罕见的单卡内多叶子节点复用，但换取了 100% 保守安全与极致直连性能。
 * - [crossCard] = true (显式开启)：开启闭包层分段内容哈希缓存。在同一个 [RuleEnv] 评估周期（多手牌轮询评估）内，
 *   100% 共享前 N 步 Transform 计算结果。
 */
@Suppress("UNCHECKED_CAST")
fun RuleContext.evaluatePipelineOutput(
    env: RuleEnv,
    source: DataSource<Any>,
    transformInstances: List<Pair<Transform<Any, Any>, Map<String, Any>>>,
    cacheKeys: PipelineCacheKeys,
    crossCard: Boolean
): Any {
    if (!crossCard) {
        var currentVal: Any = source.resolve(this, env)
        for ((transform, args) in transformInstances) {
            currentVal = transform.transform(currentVal, args)
        }
        return currentVal
    }

    var currentVal: Any = env.cache(cacheKeys.sourceKey) {
        source.resolve(this, env)
    }
    for (i in transformInstances.indices) {
        val (transform, args) = transformInstances[i]
        val stepKey = cacheKeys.stepKeys[i]
        currentVal = env.cache(stepKey) {
            transform.transform(currentVal, args)
        }
    }
    return currentVal
}
