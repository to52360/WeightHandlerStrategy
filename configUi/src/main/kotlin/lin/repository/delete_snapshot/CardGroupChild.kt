package lin.repository.delete_snapshot

import com.fasterxml.jackson.databind.JsonNode

/**
 * 卡组（聚合根）的**从属资源**：三动作值化 —— 采集 / 删除 / 恢复
 * （K-TG-014 专项；范式同 D-TG-013「能力＝值」、`SnapshotOps(collect, remove)`）。
 *
 * **为什么三动作绑成一个值**：它们是同一件事（"该资源随卡组整体进 / 出库"）的三面 ——
 * 拆成三张表会让「新增一个从属资源」变成三处各写一次，且**漏写任一处都不报错**：
 * 漏 `delete` = 留孤儿行；漏 `collect` = `restore_snapshot` 后该资源永久缺失。
 * ⇒ 绑成一个值 = 「要么一起登记，要么整体缺席」。
 *
 * **约定**：
 * - [collect] 返回**数组**（空数组 = 该类没有内容）—— 调用侧不引入"没有 / 有"的第二形态；
 * - [delete] **无条件删**：级联场景是"聚合整体消失"，聚合内相互引用不构成约束
 *   （单资源删除路径的引用校验仍留在各自的 MCP Provider，如条件树的"引用中拒绝"）；
 * - [restore] 收到空数组 ⇒ 无事可做（与"有内容"同一条码路）；返回单条结果供编排方汇总。
 *
 * **声明落点**：各域内声明（谁的表谁知道，见各域服务的 `cardGroupChild()`），
 * 唯一装配点只列清单（`ModelsDefine`）—— 装配点不认识任何域的表结构。
 */
data class CardGroupChild(
    /** 快照 payload 字段名（[CardGroupSnapshot.children] 的键）+ 报错定位，如 `"auraBoosts"`。 */
    val key: String,
    /** 采集该卡组名下的全部内容（数组节点；无内容 ⇒ 空数组）。 */
    val collect: (managerId: String) -> JsonNode,
    /** 整类删除（级联语义，不做引用校验）。 */
    val delete: (managerId: String) -> Unit,
    /** 按 [collect] 的产物整类写回。 */
    val restore: (managerId: String, payload: JsonNode) -> RestoreResult
)
