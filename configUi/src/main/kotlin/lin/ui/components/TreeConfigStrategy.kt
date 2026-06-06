package lin.ui.components

import lin.rule.tree.LogicNode

/**
 * 树配置的持久化策略。
 * 负责将内存中的 LogicNode 树转换为特定配置类型并保存/加载。
 */
interface TreeConfigStrategy<L> {
    /**
     * 保存配置。
     * @param name 配置名称
     * @param root 逻辑树根节点
     * @param existingId 已存在配置的 id（null 表示新建）
     * @param extras 额外状态（如评估树的 bindings、leafConfigs）
     * @return 保存后的配置 id
     */
    fun save(
        name: String,
        root: LogicNode<L>,
        existingId: String? = null,
        extras: Map<String, Any> = emptyMap()
    ): String

    /**
     * 加载所有配置。
     */
    fun loadAll(): List<LoadedConfig<L>>

    /**
     * 删除配置。
     */
    fun delete(id: String)

    data class LoadedConfig<L>(
        val id: String,
        val name: String,
        val root: LogicNode<L>?,
        val extras: Map<String, Any> = emptyMap()
    )
}
