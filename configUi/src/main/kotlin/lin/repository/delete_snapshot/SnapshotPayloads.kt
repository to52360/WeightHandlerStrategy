package lin.repository.delete_snapshot

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.jsontype.NamedType
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import lin.dao.CardGroupConfig
import lin.repository.aura_boost.AuraBoostEntity
import lin.repository.card_group.DimensionItemEntity
import lin.repository.card_group.StrategyPresetEntity
import lin.repository.card_purpose.PurposeTagDefEntity
import lin.repository.combo_plan.ComboPlanDefinitionEntity
import lin.repository.condition_tree.ConditionTreeConfigEntity
import lin.repository.condition_tree.createConditionTreeConfigMapper
import lin.repository.tree_config.TreeConfigEntity
import lin.rule.condition.ConditionTreeConfig
import lin.rule.tree.*
import lin.ui.service.createTreeConfigMapper

// ─────────────────────────────── 快照内容载体 ───────────────────────────────

/** 评估树快照载体（root/leafConfigs 多态经 createTreeConfigMapper 往返）。 */
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

/**
 * card_group 快照载体（bindings 多态 membership/behaviors 经 cardGroupMapper 往返）。
 *
 * [presetId] 与 [dimensionItems]：删除卡组时维度项一并清理、恢复时须写回，
 * 否则「用预设」在恢复后会退化成「不用预设」，且卡组增量项丢失。
 *
 * [children]（K-TG-014）：**值化从属资源** —— 键 = [CardGroupChild.key]，
 * 值 = 该资源内容的 JSON 数组（如 `auraBoosts` / `comboPlans` / `privateConditionTrees`）。
 * ⚠️ 默认空表 ⇒ **本字段出现前的老快照照常可读**（只是不含这几类）；
 * [bindings] / [trees] / [dimensionItems] 保持类型化字段不动 —— 兼容既有快照 JSON 契约
 * （全量改值化会破坏老快照的可恢复性，收益只是形式统一 ⇒ 不做）。
 */
data class CardGroupSnapshot(
    val managerName: String,
    val sourceFile: String,
    val enabled: Boolean,
    val managerDescription: String?,
    val managerStatus: String?,
    val defaultIncludeDerived: Boolean?,
    val bindings: List<CardGroupBinding>,
    val trees: List<EvaluatorTreeSnapshot>,
    val presetId: String? = null,
    val dimensionItems: List<DimensionItemEntity> = emptyList(),
    val children: Map<String, JsonNode> = emptyMap()
)

/** 策略预设快照载体（锚 + 两维度原始行，恢复时按维度覆盖写回）。 */
data class StrategyPresetSnapshot(
    val preset: StrategyPresetEntity,
    val dimensionItems: List<DimensionItemEntity>
)

// ─────────────────────────────── payload 编解码 ───────────────────────────────

/**
 * 各资源 payload 的序列化入口（采集侧与恢复侧共用，防两侧 JSON 契约漂移）。
 *
 * 纯数据编解码，不含任何业务判断 —— 业务在各域服务的 `deleteWithSnapshot` / `restoreFromSnapshot` 里。
 */
object SnapshotPayloads {
    /** 简单实体（combo_plan / aura_boost / card_pool / purpose_tag / strategy_preset）用普通 mapper。 */
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

    /** 标记定义（简单实体；恢复侧按原 tagId 写回）。 */
    fun purposeTag(entity: PurposeTagDefEntity): String = mapper.writeValueAsString(entity)

    fun cardPool(config: CardGroupConfig): String = mapper.writeValueAsString(config)

    /** 策略预设（锚 + 维度项原始行）。 */
    fun strategyPreset(snapshot: StrategyPresetSnapshot): String = mapper.writeValueAsString(snapshot)

    /** 评估树：由 treeConfigService 返回的 (entity, config) 构建。 */
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

    /** card_group：由 ops 采集侧组装载体后经多态 mapper 序列化。 */
    fun cardGroup(snapshot: CardGroupSnapshot): String = cardGroupMapper.writeValueAsString(snapshot)

    /**
     * 值化从属资源用：把「逐项 payload JSON」装成数组节点（空表 ⇒ 空数组）。
     *
     * 逐项 payload 复用各域既有的 `xxx(entity)` 单条编解码 ⇒ **不产生第二套 JSON 形态**
     * （恢复侧同样逐项交给各域既有的 `restoreFromSnapshot`）。
     */
    fun itemsArray(itemPayloads: List<String>): ArrayNode =
        mapper.createArrayNode().apply { itemPayloads.forEach { add(mapper.readTree(it)) } }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
private abstract class GroupMembershipMixin

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
private abstract class CardGroupBehaviorMixin
