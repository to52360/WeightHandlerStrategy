package lin.repository.card_purpose

import lin.repository.card_group.StrategyPresetRepository
import lin.repository.delete_snapshot.*

/** 标记定义保存命令（MCP 与 UI 共用；校验单点在 [PurposeTagService.saveTagDef]）。 */
data class SaveTagDefCommand(
    val tagId: String,
    val displayName: String,
    val description: String? = null,
    val boundPurpose: String? = null,
    /** 晋级为「可声明作用」（D-DP-004）；null = 不修改（保留现值）；内置行忽略该入参。 */
    val declarable: Boolean? = null
)

/** [PurposeTagService.saveTagDef] 的结果：成功给 [entity]，失败给 [error]。 */
data class SaveTagDefResult(
    val entity: PurposeTagDefEntity? = null,
    val error: String? = null
)

/**
 * 标记定义（purpose_tag_def）的写 / 删 / 恢复（T-TG-021 归域；D-DP-004 扩权）。
 *
 * 「可声明作用」的全部校验单点在本类（[saveTagDef]），MCP 与 UI 共用 ——
 * 防两处各写一遍守门而漂移（项目铁律：机制必须单点）。
 */
class PurposeTagService(
    private val tagDefRepository: PurposeTagDefRepository,
    private val cardPurposeRepository: CardPurposeRepository,
    private val presetRepository: StrategyPresetRepository
) {

    /** 该 tagId 的活跃声明数（任意 scope / 维度的维度项行数；见 [StrategyPresetRepository.countByPurposeTag]）。 */
    fun countDeclaredReferences(tagId: String): Int = presetRepository.countByPurposeTag(tagId)

    /**
     * 登记 / 更新标记定义（single point of truth：绑定校验 + 晋级 ↔ 绑定互斥 + 降级守卫）。
     *
     * 规则（D-DP-004 / D-TG-002）：
     * - `builtin = 1` 行：`boundPurpose` 恒 null（它们自己就是战略用途），`declarable` 由代码常量决定 ⇒ 入参忽略；
     * - 绑定只允许一层、目标必须是内置战略用途；
     * - **晋级 ↔ 绑定互斥**（继承别人的行为 vs 自己就是作用，行为来源不能有两个）；
     * - **降级守卫**：`declarable = 1 → 0` 时若仍被预设 / 卡组增量项声明引用 ⇒ 拒绝。
     */
    fun saveTagDef(command: SaveTagDefCommand): SaveTagDefResult {
        val tagId = command.tagId.trim()
        if (tagId.isBlank()) return SaveTagDefResult(error = "tagId 不能为空")
        if (command.displayName.isBlank()) return SaveTagDefResult(error = "displayName 不能为空")

        val existing = tagDefRepository.findByTagId(tagId)
        val isBuiltin = existing?.builtin == true
        val bound = command.boundPurpose?.trim()?.takeIf { it.isNotBlank() }
        // 战略用途自身不参与绑定（其 boundPurpose 恒为 null），故内置标记忽略该入参
        val boundAfterSave = if (isBuiltin) null else bound
        // D-DP-004：内置行是可声明性的**忽略域**（由代码常量决定），入参一律忽略
        val declarable = if (isBuiltin) false else (command.declarable ?: existing?.declarable ?: false)

        if (bound != null) {
            if (bound == tagId) return SaveTagDefResult(error = "不能绑定到自身: $tagId")
            val target = tagDefRepository.findByTagId(bound)
                ?: return SaveTagDefResult(error = "绑定目标不存在: $bound")
            if (!target.builtin) {
                return SaveTagDefResult(error = "绑定目标必须是战略用途，不能是自定义标记: $bound")
            }
        }
        if (declarable && boundAfterSave != null) {
            return SaveTagDefResult(
                error = "标记 $tagId 不能同时「晋级为可声明作用」与「绑定用途 $boundAfterSave」：" +
                        "绑定 = 继承该作用的行为，晋级 = 自己就是作用，二者互斥。" +
                        "请先解绑（boundPurpose 传空）再晋级，或放弃晋级。"
            )
        }
        if (existing?.declarable == true && !declarable) {
            val declared = countDeclaredReferences(tagId)
            if (declared > 0) {
                return SaveTagDefResult(
                    error = "标记 $tagId 仍被 $declared 处预设 / 卡组增量项的声明引用，不可降级为不可声明——" +
                            "请先在预设 / 卡组 Delta 中移除该声明"
                )
            }
        }

        val entity = PurposeTagDefEntity(
            tagId = tagId,
            displayName = command.displayName.trim(),
            description = command.description,
            boundPurpose = boundAfterSave,
            builtin = isBuiltin,
            declarable = declarable,
            createdDate = existing?.createdDate
        )
        tagDefRepository.save(entity)
        return SaveTagDefResult(entity = entity)
    }

    /**
     * 导出本资源的删除操作值。
     * 内置战略用途不可删；**仍被卡牌引用的标记不可删**（否则留下"幽灵标记"：条件树仍能命中但列表里已不存在）；
     * **仍被维度项声明引用的标记不可删**（D-DP-004）。
     */
    fun deleteOps(): SnapshotOps = SnapshotOps(
        collect = { entityId ->
            val def = tagDefRepository.findByTagId(entityId)
                ?: throw SnapshotRefused("标记定义不存在: $entityId")
            if (def.builtin) {
                throw SnapshotRefused("战略用途不可删除: $entityId（内置 7 个是行为承载者，只能改显示名）")
            }
            val referenced = cardPurposeRepository.findAll().count { entity ->
                entity.purposeTags.split(",").map { it.trim() }.contains(entityId)
            }
            if (referenced > 0) {
                throw SnapshotRefused("标记 $entityId 仍被 $referenced 张卡使用，请先用 save_card_purpose 摘除该标记后再删除")
            }
            val declared = presetRepository.countByPurposeTag(entityId)
            if (declared > 0) {
                throw SnapshotRefused(
                    "标记 $entityId 仍被 $declared 处预设 / 卡组增量项的声明引用，" +
                            "删除会让这些声明失去载体（引擎仍生效、写侧拒绝再编辑）——" +
                            "请先在预设 / 卡组 Delta 中移除该声明"
                )
            }
            SnapshotDraft(
                entityName = def.displayName,
                payload = SnapshotPayloads.purposeTag(def),
                echo = mapOf("deleted" to def.tagId, "displayName" to def.displayName)
            )
        },
        remove = { entityId -> tagDefRepository.deleteByTagId(entityId) }
    )

    /**
     * 按快照写回（原 tagId 保留）；原 tagId 已被占用则拒绝。
     *
     * ⚠️ 老快照 JSON 无 `declarable` 字段（K-DP-001）⇒ Jackson 用 Kotlin 默认值 false 落库，
     * 「已晋级」状态会静默丢失；若该标记仍有声明引用，这里显式提示调用方重新晋级。
     */
    fun restoreFromSnapshot(tagId: String, payload: String): RestoreResult {
        tagDefRepository.findByTagId(tagId)?.let { occupied ->
            return RestoreResult(
                "恢复失败：原 tagId=$tagId 已被现有数据占用（现有名称: ${occupied.displayName}）。请先删除/改名现有数据再恢复",
                isError = true
            )
        }
        val entity = SnapshotPayloads.mapper.readValue(payload, PurposeTagDefEntity::class.java)
        tagDefRepository.save(entity)
        val declared = presetRepository.countByPurposeTag(tagId)
        val note = if (!entity.declarable && declared > 0) {
            " ⚠️ 该标记仍有 $declared 处声明引用，但快照里的 declarable=false（老快照无该字段）⇒" +
                    " 这些声明当前不可再编辑；如仍需要，请重新 save_purpose_tag_def(declarable=true)"
        } else {
            ""
        }
        return RestoreResult(
            "已恢复 purpose_tag ${entity.tagId}（原 tagId 保留，boundPurpose=${entity.boundPurpose ?: "null"}）$note",
            isError = false
        )
    }
}
