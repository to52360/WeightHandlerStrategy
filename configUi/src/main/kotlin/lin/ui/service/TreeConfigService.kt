package lin.ui.service

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.jsontype.NamedType
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import lin.repository.delete_snapshot.*
import lin.repository.tree_config.EvaluatorLeafConfigRepository
import lin.repository.tree_config.TreeConfigEntity
import lin.repository.tree_config.TreeConfigRepository
import lin.rule.condition.ConditionPayload
import lin.rule.score.ScoreEffect
import lin.rule.tree.*
import lin.utils.json.registerLogicNodeMixin
import org.springframework.transaction.support.TransactionTemplate
import java.util.*

fun createTreeConfigMapper(): ObjectMapper {
    return jacksonObjectMapper()
        .registerLogicNodeMixin()
        // ── 评估树节点 ──
        .apply {
            addMixIn(EvaluatorPayload::class.java, EvaluatorPayloadMixin::class.java)
            registerSubtypes(
                NamedType(EvaluatorPayload.Rule::class.java, "Rule"),
                NamedType(EvaluatorPayload.BranchCondition::class.java, "BranchCondition")
            )
        }
        // ── 评分效应 ──
        .apply {
            addMixIn(ScoreEffect::class.java, ScoreEffectMixin::class.java)
            registerSubtypes(
                NamedType(ScoreEffect.ConstantScore::class.java, "ConstantScore"),
                NamedType(ScoreEffect.SourceScore::class.java, "SourceScore")
            )
        }
        // ── 条件节点 ──
        .apply {
            addMixIn(ConditionPayload::class.java, ConditionPayloadMixin::class.java)
            registerSubtypes(
                NamedType(ConditionPayload.ConditionRef::class.java, "ConditionRef"),
                NamedType(ConditionPayload.PipelineRef::class.java, "PipelineRef")
            )
        }
        // ── 叶子节点配置 ──
        .apply {
            addMixIn(EvaluatorLeafConfig::class.java, EvaluatorLeafConfigMixin::class.java)
            registerSubtypes(
                NamedType(RuleLeafConfig::class.java, "RULE"),
                NamedType(OrthogonalRuleLeafConfig::class.java, "ORTHOGONAL_RULE"),
                NamedType(OrthogonalConditionLeafConfig::class.java, "ORTHOGONAL_CONDITION"),
                NamedType(ConditionLeafConfig::class.java, "CONDITION"),
                NamedType(ConditionTreeLeafConfig::class.java, "CONDITION_TREE")
            )
        }
}



@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
abstract class EvaluatorPayloadMixin

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
abstract class ScoreEffectMixin

class TreeConfigService(
    private val repository: TreeConfigRepository,
    private val leafConfigRepository: EvaluatorLeafConfigRepository,
    private val mapper: ObjectMapper,
    /** T-008：tree_config 与 evaluator_leaf_config 两表写的事务边界。 */
    private val tx: TransactionTemplate
) {

    // ─────────────────── 删除 + 快照 / 恢复（T-TG-021：业务归域 + 机制由快照域编排，无跨域、无中转）───────────────────

    /** 导出本资源的删除操作值；树不存在 / 配置无法解析时抛 [SnapshotRefused]（不落快照、不删）。 */
    fun deleteOps(): SnapshotOps = SnapshotOps(
        collect = { entityId ->
            val found = findById(entityId)
                ?: throw SnapshotRefused(
                    "树不存在: $entityId。当前存在的树列表: ${
                        loadSummaries().map { mapOf("id" to it["id"], "name" to it["name"]) }
                    }"
                )
            val entity = found.first
            val config = found.second
                ?: throw SnapshotRefused("树配置解析失败，无法采集快照，拒绝删除: $entityId")
            SnapshotDraft(
                entityName = entity.name,
                payload = SnapshotPayloads.evaluatorTree(entityId, entity, config),
                echo = mapOf("deleted" to true, "treeId" to entityId, "treeName" to entity.name)
            )
        },
        remove = { entityId -> delete(entityId) }
    )

    /**
     * 按快照内容写回（原 id 保留）；两类冲突拒绝：**原 id 已被占用** / **快照的归属卡组已不存在**
     * （T-TG-023：防造出"悬空归属" —— 该树此后对任何卡组都不输出，只在日志留一条 warn）。
     *
     * @param deckExists 归属卡组存在性判定，**由调用方注入**：本服务不认识"卡组"域 ⇒ 依赖反转，
     *   不引入跨域依赖（判定方是恢复路由所在的 MCP 适配层，它本就消费卡组域）。
     */
    fun restoreFromSnapshot(id: String, payload: String, deckExists: (String) -> Boolean): RestoreResult {
        findById(id)?.let { occupied ->
            return RestoreResult(
                "恢复失败：原 id=$id 已被现有数据占用（现有名称: ${occupied.first.name}）。请先删除/改名现有数据再恢复",
                isError = true
            )
        }
        val p = SnapshotPayloads.treeMapper.readValue(payload, EvaluatorTreeSnapshot::class.java)
        val managerId = p.managerId?.takeIf { it.isNotBlank() }
        if (managerId != null && !deckExists(managerId)) {
            return RestoreResult(
                "恢复失败：快照的归属卡组已不存在（managerId=$managerId），直接恢复会造出**悬空归属**" +
                        "（该树对任何卡组都不会生效）。请先 restore_snapshot 恢复该卡组后再恢复此树。",
                isError = true
            )
        }
        saveConfig(
            name = p.name,
            config = EvaluatorTreeConfig(
                bindingType = EvaluatorTreeBindingType.valueOf(p.bindingType),
                bindingIds = p.bindingIds,
                root = p.root,
                leafConfigs = p.leafConfigs
            ),
            existingId = id,
            enabled = p.enabled,
            managerId = p.managerId,
            description = p.description
        )
        return RestoreResult("已恢复 evaluator_tree $id（原 id 保留，含 root/leafConfigs）", isError = false)
    }
    /**
     * 避免 EvaluatorNode (typealias) 导致的 Jackson 泛型解析丢失。
     * 显式声明 LogicNode&lt;EvaluatorPayload&gt; 使 JsonTypeInfo 正确处理 payload 多态。
     */
    private data class RootHolder(val root: LogicNode<EvaluatorPayload>)

    /** T-008：`tree_config` + `evaluator_leaf_config` 两表写，包事务防"树存了、叶子没存全"。 */
    fun saveConfig(
        name: String,
        config: EvaluatorTreeConfig,
        existingId: String? = null,
        enabled: Boolean = true,
        managerId: String? = null,
        description: String? = null
    ): String = tx.execute {
        val id = existingId ?: UUID.randomUUID().toString().substring(0, 8)

        // 仅序列化 root（树结构），leafConfigs 走独立表。
        // 用 RootHolder 包装以绕过 typealias 的泛型解析丢失问题。
        val rootJson = mapper.writeValueAsString(RootHolder(config.root))
        val entity = TreeConfigEntity(
            id = id,
            bindingType = config.bindingType.name,
            bindingIds = config.bindingIds.joinToString(","),
            name = name,
            description = description,
            configData = rootJson,
            enabled = enabled,
            managerId = managerId
        )
        repository.save(entity)
        leafConfigRepository.saveAll(id, config.leafConfigs, mapper)
        id
    }!!

    fun loadAll(): List<Pair<TreeConfigEntity, EvaluatorTreeConfig?>> {
        return repository.findAll().map { entity ->
            entity to parseConfig(entity)
        }
    }

    fun countConfigs(bindingType: String? = null): Int {
        return repository.countAll(bindingType)
    }

    fun loadSummaries(managerId: String? = null, limit: Int? = null): List<Map<String, Any?>> {
        return repository.findSummaries(managerId, limit).map {
            mapOf(
                "id" to it.id,
                "name" to it.name,
                "description" to it.description,
                "bindingType" to it.bindingType,
                "managerId" to it.managerId,
                "enabled" to it.enabled
            )
        }
    }

    fun loadPage(
        offset: Int,
        limit: Int,
        bindingType: String? = null
    ): List<Pair<TreeConfigEntity, EvaluatorTreeConfig?>> {
        return repository.findPage(offset, limit, bindingType).map { entity ->
            entity to parseConfig(entity)
        }
    }

    fun findById(id: String): Pair<TreeConfigEntity, EvaluatorTreeConfig?>? {
        val entity = repository.findById(id) ?: return null
        return entity to parseConfig(entity)
    }

    private fun parseConfig(entity: TreeConfigEntity): EvaluatorTreeConfig? {
        val root = try {
            mapper.readValue(entity.configData, RootHolder::class.java).root
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
        val leafConfigs = leafConfigRepository.findByConfigId(entity.id, mapper)
        return EvaluatorTreeConfig(
            bindingType = EvaluatorTreeBindingType.valueOf(entity.bindingType),
            bindingIds = entity.bindingIdList,
            root = root,
            leafConfigs = leafConfigs
        )
    }

    /** T-008：叶子 + 树两表删，包事务防"叶子删了、树还在"（或反之）。 */
    fun delete(id: String) {
        tx.execute {
            leafConfigRepository.deleteByConfigId(id)
            repository.deleteById(id)
        }
    }

    /** 按 managerId 加载配置（包含全局共享） */
    fun loadByManagerId(managerId: String?): List<Pair<TreeConfigEntity, EvaluatorTreeConfig?>> {
        return repository.findByManagerId(managerId, includeGlobal = true).map { entity ->
            entity to parseConfig(entity)
        }
    }

    /**
     * 查仅全局共享（manager_id IS NULL）且匹配指定 bindingType 的树摘要（不解析 AST 与叶子表）。
     *
     * 供预设工作台（T-TG-017）作为候选树来源。
     */
    fun findGlobalSummaries(
        bindingType: EvaluatorTreeBindingType = EvaluatorTreeBindingType.PURPOSE_TAG,
        enabledOnly: Boolean = false
    ): List<TreeConfigEntity> =
        repository.findGlobalByBindingType(bindingType.name, enabledOnly)

    /**
     * 库中全局生效的用途标签全集（仅统计 manager_id IS NULL 且 enabled=1 的 PURPOSE_TAG 树）。
     *
     * 供策略预设（MCP 与 UI 看板）计算「被禁用用途清单」：
     * disabledPurposes = purposeTagUniverse() - declaredTags
     *
     * ⚠️ 铁律（D-TG-015）：卡组专属用途树归属即拥有，跳过预设白名单，绝不能算进本全集，
     * 否则未声明时会导致预设禁用看板误报。
     */
    fun purposeTagUniverse(): Set<String> =
        findGlobalSummaries(EvaluatorTreeBindingType.PURPOSE_TAG, enabledOnly = true)
            .flatMap { it.bindingIdList }
            .toSet()
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
abstract class ConditionPayloadMixin

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.WRAPPER_OBJECT
)
@JsonIgnoreProperties(ignoreUnknown = true)
abstract class EvaluatorLeafConfigMixin



