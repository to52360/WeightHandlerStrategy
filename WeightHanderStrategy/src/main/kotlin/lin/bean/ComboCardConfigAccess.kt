package lin.bean

/**
 * ComboCard 的配置读取扩展。
 *
 * ComboCard 本体只承担运行时状态容器职责。以后新增 CardCombinedConfig 字段时，
 * 优先在实际消费它的领域目录提供扩展函数；只有跨领域高频使用的读取入口才放这里。
 *
 * ⚠️ **本体已有的成员方法禁止在此重复定义**——Kotlin 中成员方法会 shadow 同名扩展，
 * 重复定义的扩展是永不执行的死代码（且只报 warning 不报错，极易漏）。
 * 新增入口前先全局搜 `fun ComboCard.xxx`。
 */

/**
 * 这张卡属于哪些分组 = 静态组成员（启动期预算）∪ 谓词组成员（运行时求值）。
 *
 * 两部分成员来源不同但对外等价——下游（组合评分 / 排序约束 / 起手互斥 /
 * `group_filter` 算子）无需区分，都按同一个 id 空间处理。
 *
 * 结果由 [ComboCard.allGroupIds] per-实例缓存（谓词判定 + Set 合并各只做一次）。
 *
 * 注：`groupId` / `toDie` 是本体成员方法，不在此重复（见文件头警告）。
 */
fun ComboCard.groupIds(): Set<String> = allGroupIds

fun ComboCard.hasGroup(groupId: String): Boolean = groupId in groupIds()

fun ComboCard.hasAnyGroup(groupIds: Collection<String>): Boolean = groupIds.any { it in this.groupIds() }
