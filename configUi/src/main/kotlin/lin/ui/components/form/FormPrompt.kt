package lin.ui.components.form

import javafx.scene.control.Alert

/**
 * 校验提示呈现单点（T-DC-006）：弹窗类型 / 标题 / 文案格式全系统归一。
 *
 * 消费 [FieldProblem]（T-DC-010 的 `DeclarativeForm.validate()` / [evaluateField] 产出），
 * 消除各面板「判空 → `Alert.showAndWait()` → return」散装样板。
 * 确认框（删除 Confirm）不在此列：三面板删除键已配 `ActionCondition.Guard` + Store 守卫，
 * Confirm 属删除 handle 内既定形态、无跨面板样板痛点。
 */
object FormPrompt {

    /**
     * 聚合呈现全部校验问题：一次弹窗列出（WARNING +「校验失败」标题，行格式 `• 字段：文案`）。
     */
    fun showProblems(problems: List<FieldProblem>) {
        if (problems.isEmpty()) return
        Alert(Alert.AlertType.WARNING).apply {
            title = "校验失败"
            headerText = null
            contentText = problems.joinToString("\n") { "• ${it.label}：${it.message}" }
        }.showAndWait()
    }
}
