package lin.group_use_override.ui

import javafx.scene.Node
import lin.card_group.db.CardGroupRepository
import lin.group_use_override.db.GroupUseOverrideRepository
import lin.ui.UiExtension
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class GroupUseOverrideExtension : UiExtension, KoinComponent {
    override val title: String = "分组使用覆盖"
    override val order: Int = 20

    private val overrideRepo: GroupUseOverrideRepository by inject()
    private val cardGroupRepo: CardGroupRepository by inject()

    override fun createWorkbench(): Node {
        val store = GroupUseOverrideStore(overrideRepo, cardGroupRepo)
        val workbench = GroupUseOverrideWorkbench(store)
        store.loadAll()
        return workbench
    }
}
