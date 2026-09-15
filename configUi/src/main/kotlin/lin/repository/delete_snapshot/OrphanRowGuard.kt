package lin.repository.delete_snapshot

import lin.myLog
import org.springframework.jdbc.core.JdbcTemplate

/**
 * 孤儿行**自证守卫**（K-TG-014 专项 §5.4）。
 *
 * **为什么要它**：从属资源清理是「**漏写一步不报错**」的典型 —— 新增一个"归属卡组"的表若忘了登记为
 * `CardGroupChild`，级联删之后只会静默留垃圾（本次实测：aura 32 条 / combo 1 条 / 维度项 66 条都这么来的）。
 * 本守卫**不依赖任何域的自觉**：直接问库**哪些表带归属键列**，逐表数残留，有残留就告警。
 *
 * ⚠️ **只告警不删**（"未知即删"是破坏性兜底）；同一机制也供审计侧复用（列存量孤儿）。
 * ⚠️ 覆盖范围 = **直接归属键**（`manager_id` / `owner_id`）；二级从属（如 `card_group_behavior` 挂在
 * `card_group_binding` 上）不在本守卫内，由各自的级联路径负责。
 *
 * ⚠️ 这是**机制**依赖（同 `TransactionTemplate` 层级），不是业务域服务（见 `CardGroupCascadeDeleteService` KDoc）。
 */
class OrphanRowGuard(private val jdbcTemplate: JdbcTemplate) {

    /**
     * 库中带归属键列的表 → 列名（动态取自 `sqlite_master` + `pragma_table_info`）
     * ⇒ **新增从属资源表会自动纳入检测**，无需维护清单。
     */
    fun tablesWithOwnerKey(): List<Pair<String, String>> = jdbcTemplate.query(
        """
        SELECT m.name AS table_name, c.name AS column_name
        FROM sqlite_master m, pragma_table_info(m.name) c
        WHERE m.type = 'table'
          AND c.name IN ('manager_id', 'owner_id')
        ORDER BY m.name
        """.trimIndent()
    ) { rs, _ -> rs.getString("table_name") to rs.getString("column_name") }

    /** 某归属方在各表下的残留行数（只返回 > 0 的项；空表 = 干净）。 */
    fun residues(managerId: String): Map<String, Int> =
        tablesWithOwnerKey()
            .associate { (table, column) ->
                table to (jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM \"$table\" WHERE $column = ?", Int::class.java, managerId
                ) ?: 0)
            }
            .filterValues { it > 0 }

    /** 级联删后的自证：仍有归属行 ⇒ 告警（列出表名与行数，便于定位"谁忘了登记"）。 */
    fun assertNoResidue(managerId: String) {
        val left = residues(managerId)
        if (left.isNotEmpty()) {
            myLog.warn {
                "卡组 $managerId 级联删除后仍有归属行残留：$left —— " +
                        "疑似新增了「归属卡组」的表但未登记为 CardGroupChild（见 K-TG-014 专项）"
            }
        }
    }
}
