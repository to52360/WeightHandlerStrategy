package lin.repository.delete_snapshot

import lin.utils.nextShortId
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/** delete_snapshot 保留条数上限（T-010：先常量，后续可配置化）。 */
private const val SNAPSHOT_KEEP_N = 50

/**
 * 删除被拒（校验未通过：实体不存在 / 引用中禁删 / 无法采集）。
 *
 * 由各域服务的 `deleteOps().collect` 抛出，MCP 层按中立异常统一转错误结果 —— 故**不落快照、不执行删除**。
 */
class SnapshotRefused(message: String) : RuntimeException(message)

/** 恢复结果：成功说明文案，或失败（原 id 被占用 / 快照损坏等）的错误文案。 */
data class RestoreResult(
    val message: String,
    val isError: Boolean
)

/**
 * 一个可快照资源的删除**操作值**（T-TG-021：值化载体）。
 *
 * 两个 lambda 已封装好环境（捕获本域依赖），由域服务以 `deleteOps()` 导出；
 * [SnapshotStore] 只做编排：采集 → 落快照 + 删除（同事务）→ 回显。
 *
 * 为什么是**值**而不是接口：快照能力只有 [SnapshotStore] 一个消费者、操作集固定为 collect/remove
 * ⇒ 值化比「接口 + N 个 implements」更省。
 *
 * ⚠️ **不含 `resource`**（Q-TG-007 / 2026-09-13）：资源的**路由标识只声明一次**
 * （`ResourceActions.resource`，见 `lin.mcp.action`），由 `DeleteDispatcher` 调用编排时传入
 * ⇒ 域服务不必知道自己的 MCP resource 名，也消灭了"同一事实两处常量"（原 `SnapshotResource` 已删除）。
 */
data class SnapshotOps(
    /** 采集快照内容；校验不通过（不存在 / 引用中 / 无法解析）抛 [SnapshotRefused] ⇒ 不落快照、不删除。 */
    val collect: (entityId: String) -> SnapshotDraft,
    /** 快照已落库后执行删除（由 [SnapshotStore] 在同一事务内调用）。 */
    val remove: (entityId: String) -> Unit
)

/** 采集结果（纯数据）：条目名 / payload / 删除后回显字段（`snapshotId` 由编排方补）。 */
data class SnapshotDraft(
    val entityName: String?,
    val payload: String,
    val echo: Map<String, Any?> = emptyMap()
)

/**
 * 快照域**唯一入口**：表存取（insert / find / list）+ 删除编排（[deleteWithSnapshot]）。
 *
 * 职责边界（refactoring-review Z2/Z3/Z4）：
 * - **表存取**：只依赖 `delete_snapshot` 表，不认识任何业务领域。
 * - **编排**：只认识 [SnapshotOps] 值 —— 采集什么、删什么由调用方传入的操作值决定，
 *   本类只负责"先落快照再删、两者同事务、回显补 snapshotId"这条**机制**，且只有这一份。
 * - 不判冲突、不解析 payload、不引业务依赖：业务（含恢复）仍在各域服务里。
 */
class SnapshotStore(
    private val repository: DeleteSnapshotRepository,
    /** 编排用事务边界：快照行与业务删除必须同事务。 */
    private val tx: TransactionTemplate
) {

    /**
     * 删除编排（唯一入口）：采集 → 落快照 + 删除（同事务）→ 回显（补 [snapshotId]）。
     * 采集阶段抛 [SnapshotRefused]（不存在 / 引用中 / 无法解析）⇒ 不落快照、不删除。
     *
     * @param resource 资源路由标识（由 `DeleteDispatcher` 从 `ResourceActions.resource` 传入）——
     *   快照行必须记下它，恢复时才能路由回对应资源。
     */
    fun deleteWithSnapshot(resource: String, ops: SnapshotOps, entityId: String): Map<String, Any?> {
        val draft = ops.collect(entityId)
        val snapshotId = tx.execute {
            val sid = insert(resource, entityId, draft.entityName, draft.payload)
            ops.remove(entityId)
            sid
        }!!
        return draft.echo + ("snapshotId" to snapshotId)
    }

    /** 写一条快照（返回 snapshotId）并按保留策略清理超限条目。调用方应在自己的事务内调用。 */
    fun insert(resource: String, entityId: String, entityName: String?, payload: String): String {
        val snapshotId = nextShortId()
        repository.save(
            DeleteSnapshotEntity(
                snapshotId = snapshotId,
                resource = resource,
                entityId = entityId,
                entityName = entityName,
                payload = payload,
                createdAt = Instant.now().toString()
            )
        )
        repository.trimTo(SNAPSHOT_KEEP_N)
        return snapshotId
    }

    /** 取单条完整快照（含 payload）。 */
    fun find(snapshotId: String): DeleteSnapshotEntity? = repository.findById(snapshotId)

    /** 快照目录（created_at 倒序）。 */
    fun list(): List<DeleteSnapshotEntity> = repository.findAll()
}
