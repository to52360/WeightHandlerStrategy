package lin.tree_config.db

data class TreeConfigEntity(
    val id: String,          // SQLite 主键
    val groupIds: String,    // 绑定的分组 ID (多个用逗号分隔)
    val name: String,        // 界面上展示的配置名称
    val configData: String   // EvaluatorTreeConfig 的 JSON 字符串
)
