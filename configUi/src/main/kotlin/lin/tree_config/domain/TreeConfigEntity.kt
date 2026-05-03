package lin.tree_config.domain

data class TreeConfigEntity(
    val id: String,          // SQLite 主键
    val groupId: String,     // 绑定的分组 ID
    val name: String,        // 界面上展示的配置名称
    val configData: String   // EvaluatorTreeConfig 的 JSON 字符串
)
