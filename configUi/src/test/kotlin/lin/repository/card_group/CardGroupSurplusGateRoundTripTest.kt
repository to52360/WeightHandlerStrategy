package lin.repository.card_group

import lin.rule.tree.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.nio.file.Files

/**
 * T-019 SURPLUS_GATE 持久化往返验证：saveManager → card_group_behavior 行 → loadBindings 还原。
 * 复现 UI 报告「保存后数据库没数据」的排查：多 binding 各自独立落行、更新与移除语义正确。
 */
class CardGroupSurplusGateRoundTripTest {

    private lateinit var dataSource: SingleConnectionDataSource
    private lateinit var service: CardGroupService
    private val dbFile = Files.createTempFile("surplus_gate_test", ".db")

    @Before
    fun setup() {
        dataSource = SingleConnectionDataSource("jdbc:sqlite:$dbFile", true)
        val behaviorRepo = CardGroupBehaviorRepository(org.springframework.jdbc.core.JdbcTemplate(dataSource))
        val repo = CardGroupRepository(org.springframework.jdbc.core.JdbcTemplate(dataSource), behaviorRepo)
        service = CardGroupService(repo)
    }

    @After
    fun teardown() {
        dataSource.close()
        Files.deleteIfExists(dbFile)
    }

    private fun binding(id: String, managerId: String, name: String, gate: Int?) = CardGroupBinding(
        id = id, managerId = managerId, name = name,
        membership = GroupMembership.Static(listOf("CARD_A")),
        behaviors = emptyList<CardGroupBehavior>().withSurplusGate(gate)
    )

    @Test
    fun `多分组各自落行并往返还原`() {
        val managerId = service.saveManager(
            ManagerSaveCommand(
                name = "测试方案", sourceFile = "test.cardgroup", enabled = true,
                bindings = listOf(
                    binding("b1", "m1", "分组 1", gate = 1),
                    binding("b2", "m1", "分组 2", gate = 4)
                )
            )
        )

        val rows = org.springframework.jdbc.core.JdbcTemplate(dataSource)
            .queryForMap("SELECT behavior_type, payload FROM card_group_behavior WHERE binding_id = 'b2'")
        assertEquals("SURPLUS_GATE", rows["behavior_type"])
        assertEquals("{\"idleThreshold\":4}", rows["payload"])

        val reloaded = service.loadBindings(managerId).associateBy { it.name }
        assertEquals(1, reloaded["分组 1"]?.behaviors?.findSurplusGate()?.idleThreshold)
        assertEquals(4, reloaded["分组 2"]?.behaviors?.findSurplusGate()?.idleThreshold)
    }

    @Test
    fun `gate为null时移除行为行`() {
        val managerId = service.saveManager(
            ManagerSaveCommand(
                name = "测试方案", sourceFile = "test.cardgroup", enabled = true,
                bindings = listOf(binding("b1", "m1", "分组 1", gate = 4))
            )
        )
        assertEquals(4, service.loadBindings(managerId).first().behaviors.findSurplusGate()?.idleThreshold)

        // 二次保存：gate 清空 → 行应被移除
        val noGate = binding("b1", "m1", "分组 1", gate = null)
        val existingId = service.loadAllManagers().first().id
        service.saveManager(
            ManagerSaveCommand(
                name = "测试方案", sourceFile = "test.cardgroup", enabled = true,
                bindings = listOf(noGate), existingId = existingId
            )
        )
        assertNull(service.loadBindings(managerId).first().behaviors.findSurplusGate())
    }

    @Test
    fun `混合行为保存时SURPLUS_GATE与其他行为共存`() {
        val mixed = CardGroupBinding(
            id = "b1", managerId = "m1", name = "分组 1",
            membership = GroupMembership.Static(listOf("CARD_A")),
            behaviors = listOf(
                CardGroupBehavior.UseActionBehavior(listOf("RECORD_PLAY")),
                CardGroupBehavior.SurplusGateBehavior(4)
            )
        )
        val managerId = service.saveManager(
            ManagerSaveCommand(
                name = "测试方案", sourceFile = "test.cardgroup", enabled = true, bindings = listOf(mixed)
            )
        )
        val reloaded = service.loadBindings(managerId).first()
        assertNotNull(reloaded.behaviors.findSurplusGate())
        assertEquals(4, reloaded.behaviors.findSurplusGate()?.idleThreshold)
    }
}
