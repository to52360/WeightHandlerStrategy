package lin.ui.tree_config.db

/**
 * 评估树模板实体类（独立的模板表结构）
 */
data class EvaluatorTreeTemplateEntity(
    val id: String,                 // SQLite 主键
    val name: String,               // 界面上展示的模板名称
    val description: String? = null,// 模板描述
    val groupId: String? = null,    // 外键 FK → template_group.id (跟真正交模板统一分组)
    val configData: String          // root（树结构）的 JSON 字符串
)
