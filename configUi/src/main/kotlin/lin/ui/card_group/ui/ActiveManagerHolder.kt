package lin.ui.card_group.ui

import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ObservableValue
import lin.ui.card_group.db.CardManagerEntity

/**
 * Shell 级当前卡组方案选择。
 *
 * 只用于页面过滤、刷新和创建时默认值快照。
 * 保存归属必须来自草稿或编辑对象自己的 managerId，避免保存瞬间被全局选择变化影响。
 */
class ActiveManagerHolder {

    private val _activeManager = SimpleObjectProperty<CardManagerEntity?>(null)

    val activeManagerProperty: ObservableValue<CardManagerEntity?> = _activeManager

    var activeManager: CardManagerEntity?
        get() = _activeManager.get()
        set(value) {
            _activeManager.set(value)
        }

    /** 当前选中卡组方案的 ID，未选中时返回 null */
    val activeManagerId: String?
        get() = _activeManager.get()?.id
}
