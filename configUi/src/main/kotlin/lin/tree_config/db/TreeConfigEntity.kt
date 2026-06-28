package lin.tree_config.db

data class TreeConfigEntity(
    val id: String,                 // SQLite 主键
    val bindingType: String,        // 绑定类型 (GROUP / PURPOSE_TAG)，作为权威类型
    val bindingIds: String,         // 绑定目标 ID 列表（逗号分隔，用于列表展示）
    val name: String,               // 界面上展示的配置名称
    val configData: String,         // EvaluatorTreeConfig 的 JSON 字符串
    val enabled: Boolean,           // 是否启用
    val managerId: String? = null,  // 所属管理 ID，null 表示全局共享（如用途标签绑定）
    val isTemplate: Boolean = false // 是否为模板
)
