package lin.mcp

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-123 第 1 分组「圣契减费引擎」真实端到端 MCP 提交落盘测试。
 */
class DeckFlowStage3_Group1CommitTest : McpTestEnv() {

    companion object {
        const val FILE_NAME = "real_libram_deck"
        const val MANAGER_NAME = "real_libram_groups"
        const val GROUP_NAME = "圣契减费引擎"
    }

    @Test
    fun commitGroup1Tree() {
        println("========== T-123 第 1 分组「圣契减费引擎」MCP 真实落盘 ==========")

        // 1. 获取 Card Group Manager 与第 1 分组 Binding ID
        val listResp = call("card_group", """{"action":"LIST"}""")

        @Suppress("UNCHECKED_CAST")
        val groups = mapper.readValue(listResp.contentJson, List::class.java) as List<Map<String, Any>>
        val manager = groups.find { it["name"] == MANAGER_NAME }
            ?: error("未找到 Manager: $MANAGER_NAME，请确认分组已初始化")
        val managerId = manager["id"] as String
        println(">>> Manager ID: $managerId")

        val mgrResp = call("card_group", """{"action":"GET","managerId":"$managerId"}""")
        val mgrData = mapper.readValue(mgrResp.contentJson, Map::class.java)

        @Suppress("UNCHECKED_CAST")
        val bindings = mgrData["bindings"] as List<Map<String, Any>>
        val targetBinding = bindings.find { it["name"] == GROUP_NAME }
            ?: error("未在 Manager 中找到分组: $GROUP_NAME")
        val bindingId = targetBinding["id"] as String

        @Suppress("UNCHECKED_CAST")
        val cardIds = targetBinding["cardIds"] as List<String>
        println(">>> 分组名称: $GROUP_NAME | Binding ID: $bindingId | 包含卡牌: $cardIds")

        // 2. Step 1: 创建草稿骨架 (create_draft_tree)
        val root = """{"Leaf":{"payload":{"Rule":{"nodeId":"r1"}}}}"""
        val createResp = call(
            "create_draft_tree", """{"CreateDraftTree":{
            "name":"tree_$GROUP_NAME",
            "root":$root,
            "bindingType":"GROUP",
            "bindingIds":["$bindingId"],
            "managerId":"$managerId",
            "description":"[$GROUP_NAME] 减费未满4费时给8.0优先分，满4费后降优先级"
        }}"""
        )
        assertTrue("create_draft_tree 应成功: ${createResp.contentJson}", !createResp.isError)
        val draftId = mapper.readValue(createResp.contentJson, Map::class.java)["draftId"] as String
        println(">>> ✅ Step 1: 草稿已创建, draftId='$draftId'")

        // 3. Step 2: 注入正交叶子节点 (put_draft_leaf)
        val leafConfig = """{
            "ORTHOGONAL_CONDITION": {
              "nodeId": "r1",
              "sourceId": "orthogonal_condition",
              "guardCondition": {
                "PipelineRef": {
                  "sourceId": "match_activity_events",
                  "transforms": [
                    {
                      "transformId": "weighted_activity_sum",
                      "args": {
                        "playedCardIds": ["BT_020", "GDB_726"],
                        "graveyardCardIds": ["GDB_726"],
                        "weightPerEvent": 1
                      }
                    }
                  ],
                  "operatorId": "less_than",
                  "operatorArgs": { "threshold": 4 },
                  "refId": "r1"
                }
              },
              "scoreEffect": {
                "ConstantScore": { "value": 8.0 }
              },
              "args": {},
              "guardMissBehavior": "SCORE"
            }
        }"""

        val putResp = call(
            "put_draft_leaf", """{
              "draftId": "$draftId",
              "nodeId": "r1",
              "leafConfig": $leafConfig
            }"""
        )
        assertTrue("put_draft_leaf 应成功: ${putResp.contentJson}", !putResp.isError)
        println(">>> ✅ Step 2: 正交叶子节点注入成功 (played=['BT_020','GDB_726'], graveyard=['GDB_726'], less_than(4), ConstantScore(8.0))")

        // 4. Step 3: 提交落盘 (commit_draft_tree)
        val commitResp = call("commit_draft_tree", """{"draftId":"$draftId"}""")
        assertTrue("commit_draft_tree 应成功: ${commitResp.contentJson}", !commitResp.isError)
        val treeId = mapper.readValue(commitResp.contentJson, Map::class.java)["id"] as String
        println(">>> ✅ Step 3: 提交落盘成功！产生正式 EvaluatorTree ID: '$treeId'")

        // 5. 校验：读取刚提交的 EvaluatorTree 确认落库
        val getResp = call("evaluator_tree", """{"action":"GET","id":"$treeId"}""")
        assertTrue("evaluator_tree GET 应成功: ${getResp.contentJson}", !getResp.isError)
        println(">>> DB 数据校验成功！EvaluatorTree 详情:")
        println(getResp.contentJson)
    }
}
