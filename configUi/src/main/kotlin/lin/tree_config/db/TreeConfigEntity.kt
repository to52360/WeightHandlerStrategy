package lin.tree_config.db

data class TreeConfigEntity(
    val id: String,              // SQLite 主键
    val bindingType: String,     // 新增：绑定类型 (GROUP / PURPOSE_TAG)
    val bindingsSummary: String, // 绑定目标摘要字符串（用于列表展示），实际绑定由 configData JSON 承载
    val name: String,            // 界面上展示的配置名称
    val configData: String,      // EvaluatorTreeConfig 的 JSON 字符串
    val enabled: Boolean         // 是否启用
)
