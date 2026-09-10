package lin.provider

import com.fasterxml.jackson.databind.ObjectMapper
import lin.myLog
import lin.repository.card_group.CardGroupRepository
import lin.repository.tree_config.TreeConfigRepository
import lin.rule.tree.EvaluatorTreeBindingType
import lin.rule.tree.EvaluatorTreeConfig
import lin.serviceLoader.provider.TreeConfigProvider
import lin.utils.runCatchingLog

/**
 * [TreeConfigProvider] 的 SQLite 实现，供策略层通过 SPI 加载评估树配置。
 *
 * 在 SPI 边界完成绑定过滤：GROUP 绑定参考 [CardManagerEntity.enabled]，
 * PURPOSE_TAG 绑定参考 [lin.ui.card_purpose.PurposeTagTreeBindingPolicy]。
 * 引擎层收到的 [EvaluatorTreeConfig] 只包含有效的绑定目标。
 */
class SqliteTreeConfigProvider(
    private val repository: TreeConfigRepository,
    private val mapper: ObjectMapper,
    private val groupRepository: CardGroupRepository,
    private val tagPolicy: lin.ui.card_purpose.PurposeTagTreeBindingPolicy
) : TreeConfigProvider {
    override fun findById(id: String): EvaluatorTreeConfig? {
        val entity = repository.findById(id) ?: return null
        if (!entity.enabled) return null

        val currentEnabledGroupIds = groupRepository.findManagers(onlyEnabled = true).map { it.id }.toSet()
        // T-TG-001：enabledTags 为每次访问重算，用 lazy 保证一次加载只读一次库
        val enabledTagIds by lazy(LazyThreadSafetyMode.NONE) { tagPolicy.enabledTags.map { it.value }.toSet() }
        return runCatchingLog("反序列化评估树配置失败: id=$id") {
            val config = mapper.readValue(entity.configData, EvaluatorTreeConfig::class.java)
            filterBindings(config, currentEnabledGroupIds, enabledTagIds)
        }.getOrNull()
    }

    override fun findAll(): List<EvaluatorTreeConfig> {
        val currentEnabledGroupIds = groupRepository.findManagers(onlyEnabled = true).map { it.id }.toSet()
        val enabledTagIds by lazy(LazyThreadSafetyMode.NONE) { tagPolicy.enabledTags.map { it.value }.toSet() }
        return repository.findAll().filter { it.enabled }.mapNotNull { entity ->
            runCatchingLog("反序列化评估树配置失败: id=${entity.id}") {
                val config = mapper.readValue(entity.configData, EvaluatorTreeConfig::class.java)
                filterBindings(config, currentEnabledGroupIds, enabledTagIds)
            }.getOrNull()
        }
    }

    // @defect purpose-tag-configurable/K-001: GROUP 绑定跟随分组管理 enabled 状态，已修复缓存缺陷，现为动态查询。
    private fun filterBindings(
        config: EvaluatorTreeConfig,
        currentEnabledGroupIds: Set<String>,
        enabledTagIds: Set<String>
    ): EvaluatorTreeConfig {
        val filtered = config.bindingIds.filter { id ->
            when (config.bindingType) {
                EvaluatorTreeBindingType.GROUP -> {
                    val enabled = id in currentEnabledGroupIds
                    if (!enabled) {
                        myLog.debug { "跳过已禁用分组的绑定: groupId=$id" }
                    }
                    enabled
                }

                EvaluatorTreeBindingType.PURPOSE_TAG -> {
                    val enabled = id in enabledTagIds
                    if (!enabled) {
                        myLog.debug { "跳过已禁用用途标签的绑定: tagId=$id" }
                    }
                    enabled
                }

                EvaluatorTreeBindingType.CARD -> {
                    true
                }
            }
        }
        return config.copy(bindingIds = filtered)
    }
}
