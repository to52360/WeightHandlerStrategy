package lin.repository.tree_config

data class TreeConfigEntity(
    val id: String,                 // SQLite 主键
    val bindingType: String,        // 绑定类型 (GROUP / PURPOSE_TAG)，作为权威类型
    val bindingIds: String,         // 绑定目标 ID 列表（逗号分隔，用于列表展示）
    val name: String,               // 界面上展示的配置名称
    val description: String? = null,// 描述，MI 生成配置时可附带说明
    val configData: String,         // root（树结构）的 JSON 字符串，leafConfigs 已拆到 evaluator_leaf_config 表
    val enabled: Boolean,           // 是否启用
    val managerId: String? = null,  // 所属管理 ID，null 表示全局共享（如用途标签绑定）
    val channel: String? = null     // 评分通道显式声明（GENERAL/TACTICAL，null=跟随绑定目标候选策略推导）
)
