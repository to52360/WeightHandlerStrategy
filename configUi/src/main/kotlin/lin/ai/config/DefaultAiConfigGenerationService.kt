package lin.ai.config

import lin.rule.condition.PipelineAssembler
import lin.ui.service.TreeConfigService
import lin.ui.tree_config.db.EvaluatorLeafSourceCatalog
import lin.ui.tree_config.validation.EvaluatorTreeValidator

/**
 * 面向 MCP 的配置生成门面。
 * 这里只承接作者侧流程；运行期真正解释配置的契约仍由 WeightHanderStrategy 的树模型决定。
 */
class DefaultAiConfigGenerationService(
    private val leafSourceCatalog: EvaluatorLeafSourceCatalog,
    private val treeConfigService: TreeConfigService,
    pipelineAssembler: PipelineAssembler
) : AiConfigGenerationService {
    private val validator = EvaluatorTreeValidator(leafSourceCatalog, pipelineAssembler)

    override fun listEvaluatorLeafKinds(): List<AiEvaluatorLeafKind> {
        return leafSourceCatalog.loadAll().map { item ->
            AiEvaluatorLeafKind(
                kind = item.kind,
                sourceId = item.sourceId,
                name = item.name,
                desc = item.desc,
                fields = (item.builtInFields + item.fields).map { it.toAiFieldSpec() }
            )
        }
    }

    override fun validateEvaluatorTree(request: SaveEvaluatorTreeRequest): ValidationReport {
        val treeReport = validator.validate(
            config = request.config,
            name = request.name,
            managerId = request.managerId,
            requireMetadata = true
        )
        return ValidationReport(
            ok = treeReport.ok,
            diagnostics = treeReport.diagnostics.map { d ->
                ConfigDiagnostic(code = d.code, message = d.message, path = d.path)
            }
        )
    }

    override fun saveEvaluatorTree(request: SaveEvaluatorTreeRequest): SaveEvaluatorTreeResult {
        val validation = validateEvaluatorTree(request)
        if (!validation.ok) {
            return SaveEvaluatorTreeResult(id = request.existingId.orEmpty(), validation = validation)
        }

        val id = treeConfigService.saveConfig(
            name = request.name,
            config = request.config,
            description = request.description,
            existingId = request.existingId,
            enabled = request.enabled,
            managerId = request.managerId
        )
        return SaveEvaluatorTreeResult(id = id, validation = validation)
    }

    override fun getEvaluatorTreeInputSchema(): String {
        return buildInputSchemaJson()
    }

    //todo 弃用方案,准备拆解多部分配置
    private fun buildInputSchemaJson(): String {
        return """
        {
          "${"$"}schema": "http://json-schema.org/draft-07/schema#",
          "title": "SaveEvaluatorTreeRequest",
          "type": "object",
          "required": ["name", "config"],
          "properties": {
            "name": {
              "type": "string",
              "description": "评估树名称"
            },
            "existingId": {
              "type": "string",
              "description": "更新配置时的目标ID；若新建则传 null 或不传"
            },
            "description": {
              "type": "string",
              "description": "评估树说明描述"
            },
            "enabled": {
              "type": "boolean",
              "default": true,
              "description": "是否启用"
            },
            "managerId": {
              "type": "string",
              "description": "管理者/归属分组ID"
            },
            "config": {
              "type": "object",
              "description": "评估树核心配置 EvaluatorTreeConfig",
              "required": ["bindingType", "bindingIds", "root", "leafConfigs"],
              "properties": {
                "bindingType": {
                  "type": "string",
                  "description": "绑定资源类型"
                },
                "bindingIds": {
                  "type": "array",
                  "items": { "type": "string" },
                  "description": "绑定的目标ID列表"
                },
                "root": {
                  "${"$"}ref": "#/${"$"}defs/LogicNode",
                  "description": "评估树根节点逻辑表达式树"
                },
                "leafConfigs": {
                  "type": "object",
                  "description": "叶子节点配置字典 (nodeId -> EvaluatorLeafConfig)",
                  "additionalProperties": {
                    "${"$"}ref": "#/${"$"}defs/EvaluatorLeafConfig"
                  }
                }
              }
            }
          },
          "${"$"}defs": {
            "LogicNode": {
              "type": "object",
              "required": ["type"],
              "properties": {
                "type": {
                  "type": "string",
                  "enum": ["AND", "OR", "NOT", "LEAF"],
                  "description": "逻辑节点类型"
                },
                "children": {
                  "type": "array",
                  "items": { "${"$"}ref": "#/${"$"}defs/LogicNode" },
                  "description": "子逻辑节点列表 (仅组合节点类型使用)"
                },
                "payload": {
                  "type": "object",
                  "description": "叶子节点负载负载 (仅 LEAF 类型节点使用)",
                  "properties": {
                    "type": {
                      "type": "string",
                      "enum": ["Rule", "BranchCondition"],
                      "description": "负载分类"
                    },
                    "nodeId": {
                      "type": "string",
                      "description": "对应 leafConfigs 中的 key (nodeId)"
                    }
                  }
                }
              }
            },
            "ScoreEffectConstant": {
              "type": "object",
              "required": ["score"],
              "properties": {
                "type": { "type": "string", "enum": ["ConstantScore"] },
                "score": { "type": "number", "description": "固定分值" }
              }
            },
            "ScoreEffectSource": {
              "type": "object",
              "required": ["sourceId", "operatorId"],
              "properties": {
                "type": { "type": "string", "enum": ["SourceScore"] },
                "sourceId": { "type": "string", "description": "动态得分数据源ID" },
                "operatorId": { "type": "string", "description": "评分算子ID" },
                "missValue": { "type": "number", "default": 0.0, "description": "缺失兜底分值" }
              }
            },
            "ConditionPayloadRef": {
              "type": "object",
              "required": ["conditionId"],
              "properties": {
                "type": { "type": "string", "enum": ["ConditionRef"] },
                "conditionId": { "type": "string" },
                "args": { "type": "object" }
              }
            },
            "ConditionPayloadPipeline": {
              "type": "object",
              "required": ["sourceId", "pipelineId"],
              "properties": {
                "type": { "type": "string", "enum": ["PipelineRef"] },
                "sourceId": { "type": "string", "description": "正交数据源ID" },
                "pipelineId": { "type": "string", "description": "正交处理管道ID" }
              }
            },
            "EvaluatorLeafConfig": {
              "type": "object",
              "description": "评估树叶子节点配置 (5类多态区分)",
              "oneOf": [
                {
                  "title": "ConditionLeafConfig (基础条件叶子)",
                  "type": "object",
                  "required": ["nodeId", "sourceId"],
                  "properties": {
                    "kind": { "type": "string", "enum": ["CONDITION"] },
                    "nodeId": { "type": "string" },
                    "sourceId": { "type": "string", "description": "条件原子/数据源ID" },
                    "scoreEffect": { "${"$"}ref": "#/${"$"}defs/ScoreEffectConstant" },
                    "args": { "type": "object", "description": "条件校验参数" },
                    "guardMissBehavior": { "type": "string", "enum": ["SCORE", "SKIP", "FAIL"] }
                  }
                },
                {
                  "title": "ConditionTreeLeafConfig (条件树组叶子)",
                  "type": "object",
                  "required": ["nodeId", "sourceId"],
                  "properties": {
                    "kind": { "type": "string", "enum": ["CONDITION_TREE"] },
                    "nodeId": { "type": "string" },
                    "sourceId": { "type": "string", "description": "引用的条件树组ID" },
                    "scoreEffect": { "${"$"}ref": "#/${"$"}defs/ScoreEffectConstant" },
                    "args": { "type": "object" },
                    "guardMissBehavior": { "type": "string", "enum": ["SCORE", "SKIP", "FAIL"] }
                  }
                },
                {
                  "title": "OrthogonalConditionLeafConfig (正交条件叶子)",
                  "type": "object",
                  "required": ["nodeId", "guardCondition"],
                  "properties": {
                    "kind": { "type": "string", "enum": ["ORTHOGONAL_CONDITION"] },
                    "nodeId": { "type": "string" },
                    "sourceId": { "type": "string", "default": "orthogonal_condition" },
                    "guardCondition": { "${"$"}ref": "#/${"$"}defs/ConditionPayloadPipeline" },
                    "scoreEffect": { "${"$"}ref": "#/${"$"}defs/ScoreEffectConstant" },
                    "args": { "type": "object" },
                    "guardMissBehavior": { "type": "string", "enum": ["SCORE", "SKIP", "FAIL"] }
                  }
                },
                {
                  "title": "RuleLeafConfig (基础规则叶子)",
                  "type": "object",
                  "required": ["nodeId", "sourceId"],
                  "properties": {
                    "kind": { "type": "string", "enum": ["RULE"] },
                    "nodeId": { "type": "string" },
                    "sourceId": { "type": "string", "description": "规则定义ID" },
                    "guardCondition": {
                      "oneOf": [
                        { "${"$"}ref": "#/${"$"}defs/ConditionPayloadRef" },
                        { "${"$"}ref": "#/${"$"}defs/ConditionPayloadPipeline" }
                      ]
                    },
                    "scoreEffect": {
                      "oneOf": [
                        { "${"$"}ref": "#/${"$"}defs/ScoreEffectConstant" },
                        { "${"$"}ref": "#/${"$"}defs/ScoreEffectSource" }
                      ]
                    },
                    "args": { "type": "object" },
                    "guardMissBehavior": { "type": "string", "enum": ["SCORE", "SKIP", "FAIL"] }
                  }
                },
                {
                  "title": "OrthogonalRuleLeafConfig (正交规则叶子)",
                  "type": "object",
                  "required": ["nodeId", "scoreEffect"],
                  "properties": {
                    "kind": { "type": "string", "enum": ["ORTHOGONAL_RULE"] },
                    "nodeId": { "type": "string" },
                    "sourceId": { "type": "string", "default": "orthogonal_rule" },
                    "guardCondition": {
                      "oneOf": [
                        { "${"$"}ref": "#/${"$"}defs/ConditionPayloadRef" },
                        { "${"$"}ref": "#/${"$"}defs/ConditionPayloadPipeline" }
                      ]
                    },
                    "scoreEffect": { "${"$"}ref": "#/${"$"}defs/ScoreEffectSource" },
                    "args": { "type": "object" },
                    "guardMissBehavior": { "type": "string", "enum": ["SCORE", "SKIP", "FAIL"] }
                  }
                }
              ]
            }
          }
        }
        """.trimIndent()
    }
}
