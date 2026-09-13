package lin.repository.card_purpose

import lin.repository.delete_snapshot.*

/** 标记定义（purpose_tag_def）的删除 / 恢复（T-TG-021：业务归域，导出 [SnapshotOps] 由快照域编排）。 */
class PurposeTagService(
    private val tagDefRepository: PurposeTagDefRepository,
    private val cardPurposeRepository: CardPurposeRepository
) {
    /**
     * 导出本资源的删除操作值。
     * 内置战略用途不可删；**仍被卡牌引用的标记不可删**（否则留下"幽灵标记"：条件树仍能命中但列表里已不存在）。
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
            SnapshotDraft(
                entityName = def.displayName,
                payload = SnapshotPayloads.purposeTag(def),
                echo = mapOf("deleted" to def.tagId, "displayName" to def.displayName)
            )
        },
        remove = { entityId -> tagDefRepository.deleteByTagId(entityId) }
    )

    /** 按快照写回（原 tagId 保留）；原 tagId 已被占用则拒绝。 */
    fun restoreFromSnapshot(tagId: String, payload: String): RestoreResult {
        tagDefRepository.findByTagId(tagId)?.let { occupied ->
            return RestoreResult(
                "恢复失败：原 tagId=$tagId 已被现有数据占用（现有名称: ${occupied.displayName}）。请先删除/改名现有数据再恢复",
                isError = true
            )
        }
        val entity = SnapshotPayloads.mapper.readValue(payload, PurposeTagDefEntity::class.java)
        tagDefRepository.save(entity)
        return RestoreResult(
            "已恢复 purpose_tag ${entity.tagId}（原 tagId 保留，boundPurpose=${entity.boundPurpose ?: "null"}）",
            isError = false
        )
    }
}
