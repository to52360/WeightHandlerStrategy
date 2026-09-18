package lin.ui.card_purpose

import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.event.ActionEvent
import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.layout.VBox
import lin.repository.card_purpose.PurposeTagDefEntity
import lin.repository.card_purpose.PurposeTagDefRepository
import lin.repository.card_purpose.PurposeTagService
import lin.repository.card_purpose.SaveTagDefCommand
import lin.repository.card_purpose.SaveTagDefResult
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 标记定义管理对话框（D-DP-004 的 UI 操作面）：查看全部标记 + 晋级 / 降级「可声明作用」。
 *
 * 为什么独立成对话框而不嵌进打标工作台：打标工作台的保存是**给选中卡打标**，与"改标记定义"是两件事
 * （语义不同、生效范围不同），混在一块容易误操作。
 *
 * ⚠️ 校验单点在 [PurposeTagService.saveTagDef]（与 MCP `save_purpose_tag_def` 同一入口），
 * 本对话框不自写校验（否则两处守门必漂移）。
 * ⚠️ 晋级 / 降级需**重启引擎**才对出牌生效（装配期一次性解析，与切换预设同款约束）；
 * 重新打开预设 / 卡组编辑面板即可看到新的可声明候选。
 */
class PurposeTagDefDialog : Dialog<Unit>(), KoinComponent {

    private val tagDefRepository: PurposeTagDefRepository by inject()
    private val purposeTagService: PurposeTagService by inject()

    private val items = FXCollections.observableArrayList<PurposeTagDefEntity>()
    private val table = TableView<PurposeTagDefEntity>()
    private val declarableCheck = CheckBox("晋级为「可声明作用」（可被预设 / 卡组增量项声明时序与惜售）")
    private val hintLabel = Label("").apply { isWrapText = true; style = "-fx-text-fill: #7f8c8d;" }
    private val errorLabel = Label("").apply { isWrapText = true; style = "-fx-text-fill: #c0392b;" }

    init {
        title = "标记定义（可声明作用）"
        headerText = "选中标记后可晋级为「可声明作用」；内置作用的可声明性由内置清单决定，不可改"

        table.apply {
            items = this@PurposeTagDefDialog.items
            columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY
            prefHeight = 320.0
            columns.addAll(
                column("标记 ID", 160.0) { it.tagId },
                column("显示名", 140.0) { it.displayName },
                column("类型", 170.0) { typeText(it) },
                column("绑定", 100.0) { it.boundPurpose ?: "-" }
            )
            selectionModel.selectedItemProperty().addListener { _, _, def -> onSelectionChanged(def) }
        }

        declarableCheck.isDisable = true

        val content = VBox(10.0).apply {
            padding = Insets(15.0)
            children.addAll(table, Separator(), declarableCheck, hintLabel, errorLabel)
        }
        dialogPane.content = content
        dialogPane.prefWidth = 760.0

        val saveType = ButtonType("保存", ButtonBar.ButtonData.OK_DONE)
        dialogPane.buttonTypes.addAll(saveType, ButtonType.CLOSE)
        // 事件过滤（而非 onAction）：保存失败（互斥 / 降级守卫 / 库未迁移）时保持对话框打开并显示错误
        dialogPane.lookupButton(saveType).addEventFilter(ActionEvent.ACTION) { _ -> saveSelected() }

        reload()
    }

    private fun column(
        title: String,
        width: Double,
        selector: (PurposeTagDefEntity) -> String
    ): TableColumn<PurposeTagDefEntity, String> =
        TableColumn<PurposeTagDefEntity, String>(title).apply {
            setCellValueFactory { SimpleStringProperty(selector(it.value)) }
            prefWidth = width
        }

    private fun typeText(def: PurposeTagDefEntity): String = when {
        def.builtin -> "内置作用"
        def.declarable -> "可声明作用（已晋级）"
        def.boundPurpose != null -> "绑定继承"
        else -> "纯标记"
    }

    private fun onSelectionChanged(def: PurposeTagDefEntity?) {
        if (def == null) {
            declarableCheck.isDisable = true
            declarableCheck.isSelected = false
            hintLabel.text = ""
            return
        }
        declarableCheck.isDisable = def.builtin
        declarableCheck.isSelected = def.builtin || def.declarable
        hintLabel.text = when {
            def.builtin ->
                "内置作用：可声明性由内置清单决定（FINISH / EXTRA_COST 恒不可声明），此处开关会被忽略"
            def.boundPurpose != null ->
                "该标记绑定了 ${def.boundPurpose}：晋级与绑定**互斥**（绑定 = 继承行为，晋级 = 自己就是作用）。" +
                        "请先解绑（save_purpose_tag_def 里把 boundPurpose 传空保存）再晋级"
            def.declarable -> "当前已晋级：可被预设 / 卡组增量项声明时序与惜售"
            else -> "当前为纯标记 / 自定义标记：勾选后即可被声明（保存即生效；出牌生效需重启引擎）"
        }
    }

    private fun reload(keepSelection: String? = null) {
        items.setAll(tagDefRepository.findAll())
        if (keepSelection != null) {
            val index = items.indexOfFirst { it.tagId == keepSelection }
            if (index >= 0) table.selectionModel.select(index)
        }
    }

    /** 保存当前选中标记的 `declarable`（校验走域服务单点）。 */
    fun saveSelected() {
        val def = table.selectionModel.selectedItem ?: return
        if (def.builtin) return
        val result = runCatching {
            purposeTagService.saveTagDef(
                SaveTagDefCommand(
                    tagId = def.tagId,
                    displayName = def.displayName,
                    description = def.description,
                    boundPurpose = def.boundPurpose,
                    declarable = declarableCheck.isSelected
                )
            )
        }.getOrElse { e -> SaveTagDefResult(error = "保存失败: ${e.message}") }

        if (result.error != null) {
            errorLabel.text = result.error
            return
        }
        errorLabel.text = ""
        reload(keepSelection = def.tagId)
        hintLabel.text = "已保存（declarable=${result.entity?.declarable == true}）。⚠️ 需重启引擎后对出牌生效"
    }
}
