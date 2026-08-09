package lin.repository.tree_config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import lin.rule.tree.EvaluatorLeafConfig
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

/**
 * evaluator_leaf_config 表的 CRUD。
 * 每条叶子配置以完整 JSON 存储，便于多态 sealed 类的序列化/反序列化。
 * 不提供单条更新 —— 每次保存整批替换，保证与 root 的 nodeId 引用一致。
 */
class EvaluatorLeafConfigRepository(
    private val jdbcTemplate: JdbcTemplate
) {
    private val rowMapper = RowMapper { rs, _ ->
        rs.getString("node_id") to rs.getString("leaf_config")
    }

    /** 替换该 config 下的所有叶子配置（先删后插） */
    fun saveAll(configId: String, leafConfigs: Map<String, EvaluatorLeafConfig>, mapper: ObjectMapper) {
        if (leafConfigs.isEmpty()) return

        deleteByConfigId(configId)

        val sql = "INSERT INTO evaluator_leaf_config (config_id, node_id, leaf_config) VALUES (?, ?, ?)"
        val batchArgs = leafConfigs.map { (nodeId, config) ->
            arrayOf(configId, nodeId, mapper.writeValueAsString(config))
        }
        jdbcTemplate.batchUpdate(sql, batchArgs)
    }

    /** 按 config_id 加载所有叶子配置 */
    fun findByConfigId(configId: String, mapper: ObjectMapper): Map<String, EvaluatorLeafConfig> {
        val sql = "SELECT node_id, leaf_config FROM evaluator_leaf_config WHERE config_id = ?"
        val rows = jdbcTemplate.query(sql, rowMapper, configId)
        return rows.associate { (nodeId, json) ->
            nodeId to mapper.readValue<EvaluatorLeafConfig>(json)
        }
    }

    /** 全量返回 (config_id, leaf_config 原文)，供引用检查扫描（不反序列化，避免多态解析开销） */
    fun findAllRaw(): List<Pair<String, String>> {
        val sql = "SELECT config_id, leaf_config FROM evaluator_leaf_config"
        return jdbcTemplate.query(sql) { rs, _ ->
            rs.getString("config_id") to rs.getString("leaf_config")
        }
    }

    /** 删除该 config 下的所有叶子配置 */
    fun deleteByConfigId(configId: String) {
        val sql = "DELETE FROM evaluator_leaf_config WHERE config_id = ?"
        jdbcTemplate.update(sql, configId)
    }
}
