package lin.repository.delete_snapshot

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.jsontype.NamedType
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import lin.config.PathConfig
import lin.dao.CardGroupConfig
import lin.dao.CardGroupJsonParser
import lin.repository.aura_boost.AuraBoostConfigService
import lin.repository.aura_boost.AuraBoostEntity
import lin.repository.aura_boost.SaveAuraBoostInput
import lin.repository.card_group.CardGroupService
import lin.repository.card_group.ManagerSaveCommand
import lin.repository.combo_plan.ComboPlanDefinitionEntity
import lin.repository.combo_plan.ComboPlanDefinitionRepository
import lin.repository.condition_tree.ConditionTreeConfigEntity
import lin.repository.condition_tree.ConditionTreeConfigService
import lin.repository.condition_tree.createConditionTreeConfigMapper
import lin.repository.tree_config.TreeConfigEntity
import lin.rule.condition.ConditionTreeConfig
import lin.rule.tree.*
import lin.ui.service.TreeConfigService
import lin.ui.service.createTreeConfigMapper
import lin.utils.nextShortId
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.time.Instant

/** delete_snapshot 保留条数上限（T-010：先常量，后续可配置化）。 */
private const val SNAPSHOT_KEEP_N = 50

/** 资源类型常量（repository 层不反向依赖 lin.mcp.action，局部常量对照，防散落字符串）。 */
object SnapshotResource {
    const val EVALUATOR_TREE = "evaluator_tree"
    const val COMBO_PLAN = "combo_plan"
    const val CARD_GROUP = "card_group"
    const val CARD_POOL = "card_pool"
    const val CONDITION_TREE = "condition_tree"
    const val AURA_BOOST = "aura_boost"
}

/** restore 结果：恢复成功的说明文案，或失败（快照不存在 / 冲突 / 分发失败）的错误文案。 */
data class RestoreResult(
    val message: String,
    val isError: Boolean
)

/**
 * delete 快照服务（T-010）：采集（collect）+ 恢复（restore）。
 *
 * 采集：delete 处理器在删除前把完整数据（保留原 id）序列化为 payload 落 `delete_snapshot` 表并返回 snapshotId；
 * [deleteWithSnapshot] 把快照 insert 与删除动作包同一事务（快照 = 删除前状态）。
 * 恢复：[restore] 按原 id 一键写回，写前先做冲突检查（原 id 被占用则报错拒绝，不覆盖不改名）。
 */
class DeleteSnapshotService(
    private val repository: DeleteSnapshotRepository,
    private val groupService: CardGroupService,
    private val treeConfigService: TreeConfigService,
    private val conditionTreeService: ConditionTreeConfigService,
    private val auraBoostService: AuraBoostConfigService,
    private val comboPlanRepository: ComboPlanDefinitionRepository,
    /** restore(card_group 级联) 与 deleteWithSnapshot 的多步写事务边界。 */
    private val tx: TransactionTemplate
) {

    // ─────────────────── 采集 ───────────────────

    /**
     * 采集并落库一条快照（payload 为调用方已用 [SnapshotPayloads] 序列化好的 JSON），随后 trimTo(N)。
     * 用于非 DB 删除（card_pool 文件删除在 DB 事务外）。
     * @return snapshotId
     */
    fun collect(resource: String, entityId: String, entityName: String?, payload: String): String =
        insertAndTrim(resource, entityId, entityName, payload)

    /**
     * 采集 + 删除同事务：先 insert 快照再执行 [deleteAction]，保证快照 = 删除前状态。
     * payload 由调用方在事务外读好后传入（Java SAM lambda 不能非局部 return）。
     * @return snapshotId
     */
    fun deleteWithSnapshot(
        resource: String,
        entityId: String,
        entityName: String?,
        payload: String,
        deleteAction: () -> Unit
    ): String = tx.execute {
        val snapshotId = insertAndTrim(resource, entityId, entityName, payload)
        deleteAction()
        snapshotId
    }!!

    private fun insertAndTrim(resource: String, entityId: String, entityName: String?, payload: String): String {
        val snapshotId = nextShortId()
        repository.save(
            DeleteSnapshotEntity(
                snapshotId = snapshotId,
                resource = resource,
                entityId = entityId,
                entityName = entityName,
                payload = payload,
                createdAt = Instant.now().toString()
            )
        )
        repository.trimTo(SNAPSHOT_KEEP_N)
        return snapshotId
    }

    /** 供 get(resource=delete_snapshot) 取单条完整快照（含 payload）。 */
    fun get(snapshotId: String): DeleteSnapshotEntity? = repository.findById(snapshotId)

    /** 供 list(resource=delete_snapshot) 取快照列表（created_at 倒序）。 */
    fun list(): List<DeleteSnapshotEntity> = repository.findAll()

    // ─────────────────── 恢复 ───────────────────

    /**
     * 按 snapshotId 一键恢复。写前先做冲突检查（原 id 被占用则报错拒绝，不覆盖不改名）。
     */
    fun restore(snapshotId: String): RestoreResult {
        val snapshot = repository.findById(snapshotId)
            ?: return RestoreResult(
                "恢复失败：快照 $snapshotId 不存在（可能已被保留策略清理）。当前可用: list(resource=delete_snapshot)",
                isError = true
            )
        val conflictName = findConflictName(snapshot.resource, snapshot.entityId)
        if (conflictName != null) {
            return RestoreResult(
                "恢复失败：原 id=${snapshot.entityId} 已被现有数据占用（现有名称: $conflictName）。请先删除/改名现有数据再恢复",
                isError = true
            )
        }
        return restoreDispatch(snapshot)
    }

    private fun restoreDispatch(snapshot: DeleteSnapshotEntity): RestoreResult = when (snapshot.resource) {
        SnapshotResource.COMBO_PLAN -> restoreComboPlan(snapshot)
        SnapshotResource.AURA_BOOST -> restoreAuraBoost(snapshot)
        SnapshotResource.EVALUATOR_TREE -> restoreEvaluatorTree(snapshot)
        SnapshotResource.CONDITION_TREE -> restoreConditionTree(snapshot)
        SnapshotResource.CARD_GROUP -> restoreCardGroup(snapshot)
        SnapshotResource.CARD_POOL -> restoreCardPool(snapshot)
        else -> RestoreResult(
            "恢复失败：未知资源类型 ${snapshot.resource}（快照 ${snapshot.snapshotId}）", isError = true
        )
    }

    /** 冲突检查：restore 前按 resource 判断原 id 是否已被占用。 */
    private fun findConflictName(resource: String, entityId: String): String? = when (resource) {
        SnapshotResource.CARD_GROUP -> groupService.loadAllManagers().firstOrNull { it.id == entityId }?.name
        SnapshotResource.EVALUATOR_TREE -> treeConfigService.findById(entityId)?.first?.name
        SnapshotResource.CONDITION_TREE ->
            conditionTreeService.loadAll().firstOrNull { it.first.id == entityId }?.first?.name

        SnapshotResource.AURA_BOOST -> auraBoostService.findById(entityId)?.name
        SnapshotResource.COMBO_PLAN -> comboPlanRepository.findById(entityId)?.id
        SnapshotResource.CARD_POOL ->
            if (Files.exists(PathConfig.defaultDirPath.resolve("$entityId.cardgroup"))) entityId else null

        else -> null
    }

    private fun restoreComboPlan(snapshot: DeleteSnapshotEntity): RestoreResult {
        val entity = SnapshotPayloads.mapper.readValue(snapshot.payload, ComboPlanDefinitionEntity::class.java)
        comboPlanRepository.save(entity)
        return RestoreResult("已恢复 combo_plan ${entity.id}（原 id 保留）", isError = false)
    }

    private fun restoreAuraBoost(snapshot: DeleteSnapshotEntity): RestoreResult {
        val entity = SnapshotPayloads.mapper.readValue(snapshot.payload, AuraBoostEntity::class.java)
        auraBoostService.save(
            SaveAuraBoostInput(
                name = entity.name,
                conditionId = entity.conditionId,
                targetConditionId = entity.targetConditionId,
                score = entity.score,
                managerId = entity.managerId,
                existingId = entity.id,
                // T-SR-012：恢复时一并还原启用状态（快照 payload 含 enabled）
                enabled = entity.enabled
            )
        )
        return RestoreResult("已恢复 aura_boost ${entity.id}（原 id 保留）", isError = false)
    }

    private fun restoreEvaluatorTree(snapshot: DeleteSnapshotEntity): RestoreResult {
        val p = SnapshotPayloads.treeMapper.readValue(snapshot.payload, EvaluatorTreeSnapshot::class.java)
        treeConfigService.saveConfig(
            name = p.name,
            config = EvaluatorTreeConfig(
                bindingType = EvaluatorTreeBindingType.valueOf(p.bindingType),
                bindingIds = p.bindingIds,
                root = p.root,
                leafConfigs = p.leafConfigs
            ),
            existingId = snapshot.entityId,
            enabled = p.enabled,
            managerId = p.managerId,
            description = p.description
        )
        return RestoreResult(
            "已恢复 evaluator_tree ${snapshot.entityId}（原 id 保留，含 root/leafConfigs）",
            isError = false
        )
    }

    private fun restoreConditionTree(snapshot: DeleteSnapshotEntity): RestoreResult {
        val p = SnapshotPayloads.conditionTreeMapper.readValue(
            snapshot.payload, ConditionTreeSnapshot::class.java
        )
        conditionTreeService.saveConfig(
            name = p.config.name ?: snapshot.entityId,
            config = p.config,
            existingId = snapshot.entityId,
            managerId = p.managerId?.takeIf { it.isNotBlank() },
            inlineCreated = p.inlineCreated
        )
        return RestoreResult("已恢复 condition_tree ${snapshot.entityId}（原 id 保留）", isError = false)
    }

    private fun restoreCardGroup(snapshot: DeleteSnapshotEntity): RestoreResult {
        val p = SnapshotPayloads.cardGroupMapper.readValue(snapshot.payload, CardGroupSnapshot::class.java)
        tx.execute {
            groupService.saveManager(
                ManagerSaveCommand(
                    name = p.managerName,
                    sourceFile = p.sourceFile,
                    enabled = p.enabled,
                    bindings = p.bindings.map { it.copy(managerId = "") },
                    existingId = snapshot.entityId,
                    managerDescription = p.managerDescription,
                    managerStatus = p.managerStatus,
                    defaultIncludeDerived = p.defaultIncludeDerived
                )
            )
            p.trees.forEach { t ->
                treeConfigService.saveConfig(
                    name = t.name,
                    config = EvaluatorTreeConfig(
                        bindingType = EvaluatorTreeBindingType.valueOf(t.bindingType),
                        bindingIds = t.bindingIds,
                        root = t.root,
                        leafConfigs = t.leafConfigs
                    ),
                    existingId = t.id,
                    enabled = t.enabled,
                    managerId = t.managerId,
                    description = t.description
                )
            }
        }
        return RestoreResult(
            "已恢复 card_group ${snapshot.entityId}（manager + ${p.bindings.size} binding + ${p.trees.size} 棵关联树，原 id 全保留）",
            isError = false
        )
    }

    private fun restoreCardPool(snapshot: DeleteSnapshotEntity): RestoreResult {
        val config = SnapshotPayloads.mapper.readValue(snapshot.payload, CardGroupConfig::class.java)
        CardGroupJsonParser.saveCardGroupConfigs(config.cards, snapshot.entityId, config.enabled)
        return RestoreResult(
            "已恢复 card_pool ${snapshot.entityId}.cardgroup（原文件重建，含 cards 权重）",
            isError = false
        )
    }
}

// ─────────────────── payload 序列化（快照写入与恢复两侧共用，保证多态一致） ───────────────────

/**
 * 评估树快照载体（root/leafConfigs 多态经 createTreeConfigMapper 往返）。
 * 由删除 handler 采集时用 [SnapshotPayloads.evaluatorTree] 构建 JSON。
 */
data class EvaluatorTreeSnapshot(
    val id: String,
    val name: String,
    val description: String?,
    val enabled: Boolean,
    val managerId: String?,
    val bindingType: String,
    val bindingIds: List<String>,
    val root: EvaluatorNode,
    val leafConfigs: Map<String, EvaluatorLeafConfig>
)

/** 条件树快照载体（managerId / inlineCreated 在实体上，config 含 id/name/root）。 */
data class ConditionTreeSnapshot(
    val config: ConditionTreeConfig,
    val managerId: String?,
    val inlineCreated: Boolean
)

/** card_group 快照载体（bindings 多态 membership/behaviors 经 cardGroupMapper 往返）。 */
data class CardGroupSnapshot(
    val managerName: String,
    val sourceFile: String,
    val enabled: Boolean,
    val managerDescription: String?,
    val managerStatus: String?,
    val defaultIncludeDerived: Boolean?,
    val bindings: List<CardGroupBinding>,
    val trees: List<EvaluatorTreeSnapshot>
)

/** 各资源 payload 的序列化入口（删除 handler 采集侧 + 服务恢复侧共用，防两侧 JSON 契约漂移）。 */
object SnapshotPayloads {
    /** 简单实体（combo_plan / aura_boost / card_pool）用普通 mapper。 */
    val mapper: ObjectMapper = jacksonObjectMapper()

    /** 评估树 / card_group 用 createTreeConfigMapper（含 root/leafConfigs 多态注册）。 */
    val treeMapper: ObjectMapper = createTreeConfigMapper()

    /** 条件树用 createConditionTreeConfigMapper。 */
    val conditionTreeMapper: ObjectMapper = createConditionTreeConfigMapper()

    /** card_group 额外注册 GroupMembership / CardGroupBehavior 多态。 */
    val cardGroupMapper: ObjectMapper = treeMapper.copy().apply {
        addMixIn(GroupMembership::class.java, GroupMembershipMixin::class.java)
        registerSubtypes(
            NamedType(GroupMembership.Static::class.java, "Static"),
            NamedType(GroupMembership.Predicate::class.java, "Predicate")
        )
        addMixIn(CardGroupBehavior::class.java, CardGroupBehaviorMixin::class.java)
        registerSubtypes(
            NamedType(CardGroupBehavior.OverrideBehavior::class.java, "OverrideBehavior"),
            NamedType(CardGroupBehavior.UseActionBehavior::class.java, "UseActionBehavior"),
            NamedType(CardGroupBehavior.SurplusGateBehavior::class.java, "SurplusGateBehavior")
        )
    }

    fun comboPlan(entity: ComboPlanDefinitionEntity): String = mapper.writeValueAsString(entity)

    fun auraBoost(entity: AuraBoostEntity): String = mapper.writeValueAsString(entity)

    fun cardPool(config: CardGroupConfig): String = mapper.writeValueAsString(config)

    /** 由 treeConfigService.findById(id) 返回的 (entity, config) 构建。 */
    fun evaluatorTree(id: String, entity: TreeConfigEntity, config: EvaluatorTreeConfig): String =
        treeMapper.writeValueAsString(
            EvaluatorTreeSnapshot(
                id = id,
                name = entity.name,
                description = entity.description,
                enabled = entity.enabled,
                managerId = entity.managerId,
                bindingType = config.bindingType.name,
                bindingIds = config.bindingIds,
                root = config.root,
                leafConfigs = config.leafConfigs
            )
        )

    /** 条件树：由 entity(meta) + config 构建。 */
    fun conditionTree(entity: ConditionTreeConfigEntity, config: ConditionTreeConfig): String =
        conditionTreeMapper.writeValueAsString(
            ConditionTreeSnapshot(
                config = config,
                managerId = entity.managerId,
                inlineCreated = entity.inlineCreated
            )
        )

    /** card_group：由 manager 元数据 + bindings + 各关联树 (id, entity, config) 构建。 */
    fun cardGroup(
        managerName: String,
        sourceFile: String,
        enabled: Boolean,
        managerDescription: String?,
        managerStatus: String?,
        defaultIncludeDerived: Boolean?,
        bindings: List<CardGroupBinding>,
        trees: List<Triple<String, TreeConfigEntity, EvaluatorTreeConfig>>
    ): String = cardGroupMapper.writeValueAsString(
        CardGroupSnapshot(
            managerName = managerName,
            sourceFile = sourceFile,
            enabled = enabled,
            managerDescription = managerDescription,
            managerStatus = managerStatus,
            defaultIncludeDerived = defaultIncludeDerived,
            bindings = bindings,
            trees = trees.map { (id, e, c) ->
                EvaluatorTreeSnapshot(
                    id = id,
                    name = e.name,
                    description = e.description,
                    enabled = e.enabled,
                    managerId = e.managerId,
                    bindingType = c.bindingType.name,
                    bindingIds = c.bindingIds,
                    root = c.root,
                    leafConfigs = c.leafConfigs
                )
            }
        )
    )
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
private abstract class GroupMembershipMixin

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
private abstract class CardGroupBehaviorMixin
