package lin.repository.card_group

import lin.rule.tree.CardGroupBinding
import lin.rule.tree.GroupMembership
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files

/**
 * T-001 成员资格（[GroupMembership]）持久化往返验证：
 * saveManager → card_group_binding 行 → loadBindings 还原。
 *
 * 覆盖：
 * - 静态组：cardIds 原样往返；
 * - 谓词组：conditionId / includeDerived 原样往返，card_ids 列存空数组；
 * - 混合：同一方案下两种类型共存互不干扰；
 * - 数据损坏保护：member_type=PREDICATE 但 condition_id 为空 → 退化为静态组，不抛异常；
 * - 存量行（未指定 member_type，走列默认值）加载为静态组。
 */
class CardGroupMembershipRoundTripTest {

    private lateinit var dataSource: SingleConnectionDataSource
    private lateinit var service: CardGroupService
    private val dbFile = Files.createTempFile("membership_test", ".db")

    @Before
    fun setup() {
        dataSource = SingleConnectionDataSource("jdbc:sqlite:$dbFile", true)
        val behaviorRepo = CardGroupBehaviorRepository(JdbcTemplate(dataSource))
        // T-008：service 构造新增事务模板（与生产 Koin 装配一致）
        service = CardGroupService(
            CardGroupRepository(JdbcTemplate(dataSource), behaviorRepo),
            TransactionTemplate(DataSourceTransactionManager(dataSource))
        )
    }

    @After
    fun teardown() {
        dataSource.close()
        Files.deleteIfExists(dbFile)
    }

    private fun saveAndLoad(
        bindings: List<CardGroupBinding>,
        defaultIncludeDerived: Boolean? = null
    ): List<CardGroupBinding> {
        val managerId = service.saveManager(
            ManagerSaveCommand(
                name = "成员资格测试", sourceFile = "membership_test.cardgroup",
                enabled = true, bindings = bindings,
                defaultIncludeDerived = defaultIncludeDerived
            )
        )
        return service.loadBindings(managerId)
    }

    @Test
    fun `静态组往返保留 cardIds`() {
        val loaded = saveAndLoad(
            listOf(
                CardGroupBinding(
                    id = "s1", managerId = "", name = "静态组",
                    membership = GroupMembership.Static(listOf("CARD_A", "CARD_B"))
                )
            )
        )

        assertEquals(1, loaded.size)
        assertEquals(
            GroupMembership.Static(listOf("CARD_A", "CARD_B")),
            loaded[0].membership
        )
    }

    @Test
    fun `谓词组往返保留 conditionId 与 includeDerived`() {
        val loaded = saveAndLoad(
            listOf(
                CardGroupBinding(
                    id = "p1", managerId = "", name = "谓词组",
                    membership = GroupMembership.Predicate(conditionId = "cond_spell", includeDerived = true)
                )
            )
        )

        assertEquals(1, loaded.size)
        val membership = loaded[0].membership
        assertTrue("应还原为 Predicate，实际 $membership", membership is GroupMembership.Predicate)
        assertEquals("cond_spell", (membership as GroupMembership.Predicate).conditionId)
        assertEquals(true, membership.includeDerived)
        // 谓词组没有静态成员清单
        assertTrue(membership.let { loaded[0].cardIds }.isEmpty())
    }

    @Test
    fun `谓词组 includeDerived 未声明时保持 null 以回落卡组级`() {
        val loaded = saveAndLoad(
            listOf(
                CardGroupBinding(
                    id = "p2", managerId = "", name = "未声明",
                    membership = GroupMembership.Predicate(conditionId = "cond_taunt")
                )
            )
        )

        // null 必须原样保留：null=回落卡组级默认，与显式 false 语义不同
        assertEquals(null, (loaded[0].membership as GroupMembership.Predicate).includeDerived)
    }

    @Test
    fun `静态组与谓词组共存互不干扰`() {
        val loaded = saveAndLoad(
            listOf(
                CardGroupBinding(
                    id = "s", managerId = "", name = "静态组",
                    membership = GroupMembership.Static(listOf("CARD_A"))
                ),
                CardGroupBinding(
                    id = "p", managerId = "", name = "谓词组",
                    membership = GroupMembership.Predicate(conditionId = "cond_spell")
                )
            )
        ).associateBy { it.name }

        assertEquals(
            GroupMembership.Static(listOf("CARD_A")),
            loaded["静态组"]!!.membership
        )
        assertEquals(
            GroupMembership.Predicate(conditionId = "cond_spell"),
            loaded["谓词组"]!!.membership
        )
    }

    @Test
    fun `声明为 PREDICATE 但条件 id 为空时退化为静态组`() {
        // 构造数据损坏：直接写库，绕开领域层约束
        JdbcTemplate(dataSource).update(
            """
            INSERT INTO card_group_binding (id, manager_id, name, card_ids, member_type, condition_id)
            VALUES ('bad', 'mgr', '损坏组', '["CARD_X"]', 'PREDICATE', NULL)
            """.trimIndent()
        )

        val loaded = service.loadBindings("mgr")

        assertEquals(1, loaded.size)
        // 退化为静态组而非抛异常，避免整卡组加载失败
        assertEquals(GroupMembership.Static(listOf("CARD_X")), loaded[0].membership)
    }

    /**
     * 存量行（迁移前写入、未指定 member_type）必须加载为静态组。
     *
     * 这是迁移的正确性保证：`member_type` 列带 `NOT NULL DEFAULT 'STATIC'`，
     * 存量行自动落在默认值上，行为与迁移前完全一致。
     * ⚠️ 存量库加列需手动执行 docs/sql/migrations/2026-08-31_t001_group_membership.sql，
     * 代码不做自动 ALTER——故本用例用「已迁移的表结构 + 存量数据行」来锁定这个保证。
     */
    @Test
    fun `存量行未指定 member_type 时加载为静态组`() {
        // 只写迁移前就存在的列，member_type 走 DEFAULT
        JdbcTemplate(dataSource).update(
            """
            INSERT INTO card_group_binding (id, manager_id, name, card_ids)
            VALUES ('old', 'mgr_old', '旧组', '["CARD_Y"]')
            """.trimIndent()
        )

        val loaded = service.loadBindings("mgr_old")

        assertEquals(1, loaded.size)
        assertEquals(GroupMembership.Static(listOf("CARD_Y")), loaded[0].membership)
    }

    /**
     * 卡组级 defaultIncludeDerived 往返（P0-2 回归锁定）：
     * 此前 [CardGroupService] 的 toDomain 漏传该字段 → 写入端能存、loadAll 读出恒 null，
     * 两级覆盖链（组级 > 卡组级 > false）的中间层在读路径断裂。
     */
    @Test
    fun `卡组级 defaultIncludeDerived 往返`() {
        val managerId = service.saveManager(
            ManagerSaveCommand(
                name = "卡组级默认", sourceFile = "membership_default.cardgroup",
                enabled = true, bindings = emptyList(),
                defaultIncludeDerived = true
            )
        )

        val loaded = service.loadAll(onlyEnabled = true)
            .first { it.cardGroupManagerId == managerId }

        assertEquals(true, loaded.defaultIncludeDerived)
    }

    /** 二次保存未传（null）时保持原值——「缺省不覆盖」语义对 defaultIncludeDerived 同样成立。 */
    @Test
    fun `卡组级 defaultIncludeDerived 缺省保存保持原值`() {
        val managerId = service.saveManager(
            ManagerSaveCommand(
                name = "保持原值", sourceFile = "membership_keep.cardgroup",
                enabled = true, bindings = emptyList(),
                defaultIncludeDerived = false
            )
        )
        // 显式 false 已落库；二次保存不传 → 不应被 null 覆盖
        service.saveManager(
            ManagerSaveCommand(
                name = "保持原值", sourceFile = "membership_keep.cardgroup",
                enabled = true, bindings = emptyList(), existingId = managerId
            )
        )

        val loaded = service.loadAll(onlyEnabled = true)
            .first { it.cardGroupManagerId == managerId }

        assertEquals(false, loaded.defaultIncludeDerived)
    }
}
