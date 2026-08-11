package lin.mcp

import org.junit.Test

/**
 * 一次性审计：统计 MCP 工具 name/description/inputSchema 的占用（Q-004 指标①）。
 * 复用 McpTestEnv 已加载的 tools map，仅统计不调用。
 */
class McpToolSizeAuditTest : McpTestEnv() {

    private data class Row(
        val name: String,
        val descChars: Int,
        val schemaChars: Int,
        val descTokens: Int,
        val schemaTokens: Int
    ) {
        val totalChars: Int get() = descChars + schemaChars
        val totalTokens: Int get() = descTokens + schemaTokens
    }

    /** 混合中英粗估 token：ASCII 约 4 chars/token，中文约 0.75 token/字 */
    private fun estimateTokens(s: String): Int {
        var tokens = 0.0
        for (ch in s) {
            tokens += if (ch.code > 127) 0.75 else 0.25
        }
        return tokens.toInt()
    }

    @Test
    fun auditToolSchemaSize() {
        val rows = tools.values.map { h ->
            Row(
                name = h.name,
                descChars = h.description.length,
                schemaChars = h.inputSchemaJson.length,
                descTokens = estimateTokens(h.description),
                schemaTokens = estimateTokens(h.inputSchemaJson)
            )
        }.sortedByDescending { it.totalChars }

        println()
        println("===== 工具占用明细（按 totalChars 降序） =====")
        println("${"tool".padEnd(34)} | descChars | schemaChars | totalChars | descTok | schemaTok | totalTok")
        println("-".repeat(100))
        for (r in rows) {
            println(
                "${r.name.padEnd(34)} | ${r.descChars.toString().padStart(10)} | " +
                        "${r.schemaChars.toString().padStart(11)} | ${r.totalChars.toString().padStart(10)} | " +
                        "${r.descTokens.toString().padStart(7)} | ${r.schemaTokens.toString().padStart(9)} | " +
                        r.totalTokens.toString().padStart(8)
            )
        }

        val totalChars = rows.sumOf { it.totalChars }
        val totalDescTokens = rows.sumOf { it.descTokens }
        val totalSchemaTokens = rows.sumOf { it.schemaTokens }
        val totalTokens = rows.sumOf { it.totalTokens }
        val avg = totalChars / rows.size

        println("-".repeat(100))
        println("工具数: ${rows.size}")
        println("合计字符: desc=$totalChars（不含 name）")
        println("  其中 description ${rows.sumOf { it.descChars }} chars / $totalDescTokens tok")
        println("  其中 inputSchema ${rows.sumOf { it.schemaChars }} chars / $totalSchemaTokens tok")
        println("估算总 token（desc+schema）: $totalTokens")
        println("平均每工具: $avg chars / ${totalTokens / rows.size} tok")
        println("（name 每个仅 ${tools.values.sumOf { it.name.length }} 字符，可忽略）")
        println()

        // 参考对比：常见上下文预算
        val gpt4o_128k = 128_000
        val usagePct = totalTokens.toDouble() * 100 / gpt4o_128k
        println("参考：占 128k 上下文 = ${"%.2f".format(usagePct)}%")
        println("（此为全量工具 schema 常驻上下文的最小估，真实还有系统提示/会话历史）")
    }
}
