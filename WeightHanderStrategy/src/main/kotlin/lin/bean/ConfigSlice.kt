package lin.bean

import lin.bean.usePlan.PurposeTagId
import lin.domain.use.UseStrategy

/**
 * 分片的作用域键：描述一份 [ConfigSlice] 作用于哪些卡。
 * 密封类保证 expandSlices() 穷举所有 scope 类型。
 */
sealed class ConfigSliceScope {
    /** 按分组 groupId 作用 */
    data class Group(val groupId: String) : ConfigSliceScope()

    /** 按用途标签作用 */
    data class Tag(val tagId: PurposeTagId) : ConfigSliceScope()

    /** 直绑单卡 */
    data class Card(val cardId: String) : ConfigSliceScope()
}

/**
 * 来源贡献的一份配置片段。
 * 只装"来源直接投入"的字段，派生字段（UseIntent/combo）在 build 里由 Assembler 计算。
 */
data class ConfigSlice(
    val useStrategies: List<UseStrategy> = emptyList(),
)

/**
 * 分片条目：作用域 + 配置片段的键值对。
 * 由各 Step 在 contribute 阶段追加到 builder.slices，build 时 expandSlices() 统一解析。
 */
data class SliceEntry(
    val scope: ConfigSliceScope,
    val slice: ConfigSlice,
)
