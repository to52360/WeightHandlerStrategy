package lin.group_use_override.ui

import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleDoubleProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import lin.bean.usePlan.UseStage
import lin.card_group.db.CardGroupRepository
import lin.group_use_override.db.GroupUseOverrideEntity
import lin.group_use_override.db.GroupUseOverrideRepository

/** 卡牌分组选项，用于下拉框展示 bindingName (managerName) */
data class GroupOption(
    val bindingId: String,
    val displayName: String
) {
    override fun toString() = displayName
}

/** 三态选项，用于 replanAfterUse 的下拉 */
enum class TriState(val label: String, val value: Boolean?) {
    NOT_OVERRIDE("不覆盖", null),
    YES("是", true),
    NO("否", false);

    override fun toString() = label
}

/**
 * TableView 行模型，字段使用 JavaFX Property 以支持内联编辑。
 */
class GroupUseOverrideRow {
    val cardGroupId = SimpleStringProperty("")
    val stageOverride = SimpleObjectProperty<UseStage?>(null)
    val replanAfterUse = SimpleObjectProperty<Boolean?>(null)
    val orderWeight = SimpleDoubleProperty(0.0)

    fun toEntity() = GroupUseOverrideEntity(
        cardGroupId = cardGroupId.get(),
        stageOverride = stageOverride.get()?.name,
        replanAfterUse = replanAfterUse.get(),
        orderWeight = orderWeight.get()
    )

    companion object {
        fun fromEntity(entity: GroupUseOverrideEntity) = GroupUseOverrideRow().apply {
            val domain = entity.toDomain()
            cardGroupId.set(entity.cardGroupId)
            stageOverride.set(domain.stageOverride)
            replanAfterUse.set(domain.replanAfterUse)
            orderWeight.set(entity.orderWeight)
        }
    }
}

/**
 * 轻量 Store：管理数据加载、增删改，以及从 [CardGroupRepository]
 * 获取可选分组列表（分组数据来源）。
 */
class GroupUseOverrideStore(
    private val overrideRepo: GroupUseOverrideRepository,
    private val cardGroupRepo: CardGroupRepository
) {
    val rows: ObservableList<GroupUseOverrideRow> = FXCollections.observableArrayList()
    val availableGroups: ObservableList<GroupOption> = FXCollections.observableArrayList()
    val isDirty = SimpleBooleanProperty(false)

    /** 从 DB 加载全部覆盖配置，并构建可选分组列表 */
    fun loadAll() {
        val entities = overrideRepo.findAll()
        val managers = cardGroupRepo.findAllManagers()
        val managerNameMap = managers.associate { it.id to it.name }
        val allBindings = managers.flatMap { cardGroupRepo.findBindingsByManager(it.id) }

        availableGroups.setAll(
            allBindings.map { b ->
                GroupOption(
                    bindingId = b.id,
                    displayName = "${b.name} (${managerNameMap[b.mangerId] ?: "?"})"
                )
            }
        )

        // 清旧监听再设新行
        rows.forEach { unbindRowListener(it) }
        rows.setAll(entities.map { GroupUseOverrideRow.fromEntity(it) })
        rows.forEach { bindRowListener(it) }

        isDirty.set(false)
    }

    /** 新增空白行 */
    fun addRow() {
        val row = GroupUseOverrideRow()
        bindRowListener(row)
        rows.add(row)
        isDirty.set(true)
    }

    /** 删除选中行并同步 DB */
    fun deleteRow(row: GroupUseOverrideRow) {
        val entity = row.toEntity()
        if (entity.cardGroupId.isNotBlank()) {
            overrideRepo.deleteByCardGroupId(entity.cardGroupId)
        }
        unbindRowListener(row)
        rows.remove(row)
        isDirty.set(false)
    }

    /** 将所有有效行保存到 DB */
    fun saveAll() {
        rows.forEach { row ->
            val entity = row.toEntity()
            if (entity.cardGroupId.isNotBlank()) {
                overrideRepo.save(entity)
            }
        }
        isDirty.set(false)
    }

    // ─── 脏标记绑定 / 解绑 ──────────────────────────────────────

    private val listeners = mutableMapOf<GroupUseOverrideRow, List<() -> Unit>>()

    private fun bindRowListener(row: GroupUseOverrideRow) {
        val markDirty = { _: Any? -> if (!isDirty.get()) isDirty.set(true) }
        val l1: (Any?, String, String) -> Unit = { _, _, _ -> markDirty(null) }
        val l2: (Any?, UseStage?, UseStage?) -> Unit = { _, _, _ -> markDirty(null) }
        val l3: (Any?, Boolean?, Boolean?) -> Unit = { _, _, _ -> markDirty(null) }
        val l4: (Any?, Number, Number) -> Unit = { _, _, _ -> markDirty(null) }

        row.cardGroupId.addListener(l1)
        row.stageOverride.addListener(l2)
        row.replanAfterUse.addListener(l3)
        row.orderWeight.addListener(l4)

        listeners[row] = listOf(
            { row.cardGroupId.removeListener(l1) },
            { row.stageOverride.removeListener(l2) },
            { row.replanAfterUse.removeListener(l3) },
            { row.orderWeight.removeListener(l4) },
        )
    }

    private fun unbindRowListener(row: GroupUseOverrideRow) {
        listeners.remove(row)?.forEach { it() }
    }
}
