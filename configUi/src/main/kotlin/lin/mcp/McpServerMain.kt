package lin.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema
import lin.moduls.loadMcpModules
import org.koin.core.context.GlobalContext
import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets

/**
 * MCP 专属 stdout 无损过滤流。
 *
 * 背景：stdio 传输要求 stdout 是纯净的 JSON-RPC 流。但某些部署环境下 stdout 会被非 JSON 杂质污染
 * （如第三方库直写 System.out 的日志/警告），部分客户端（agent）无法容忍这些杂质而要求过滤。
 *
 * 设计要点——必须「按行无损」而非「按 write 边界丢弃」：
 * - 旧实现每次 write 调用独立判断是否以 { / [ 开头，会把一条跨多次 write 的 JSON-RPC 消息的续段
 *   误判为杂质并丢弃，导致客户端收不到完整响应、握手超时（-32001 Request timed out）。
 * - 本实现把字节累积到行缓冲，仅在遇到整行结束符时才对该「完整行」做判定：
 *   以 { / [ 开头的整行 → 透传 stdout（完整 JSON-RPC，绝不被拆）；否则整行 → 转 stderr。
 * - 这样同时满足两类客户端：严格按行解析的（报文不被拆坏）、要求过滤杂质的（杂质整行被导走）。
 */
private class McpStdoutFilterStream(
    private val realStdout: OutputStream,
    private val errStream: OutputStream
) : FilterOutputStream(realStdout) {

    private val buf = StringBuilder()

    private fun flushLine(line: String) {
        // 去掉可能的 \r（Windows CRLF），避免行尾回车被当成杂质或污染 JSON
        val cleaned = line.trimEnd('\r')
        val target = if (cleaned.startsWith("{") || cleaned.startsWith("[")) realStdout else errStream
        target.write(cleaned.toByteArray(StandardCharsets.UTF_8))
        target.write('\n'.code)
        target.flush()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        buf.append(String(b, off, len, StandardCharsets.UTF_8))
        var nl: Int
        while (buf.indexOf("\n").also { nl = it } >= 0) {
            val line = buf.substring(0, nl)
            buf.delete(0, nl + 1)
            flushLine(line)
        }
    }

    override fun write(b: Int) {
        // 单字节写入也走缓冲，保证行边界一致
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun flush() {
        // 不强制 flush 残留未换行缓冲：不完整的行不应作为 JSON-RPC 透传，避免污染。
        realStdout.flush()
        errStream.flush()
    }
}

/**
 * configUi 模块下的 MCP 独立入口。
 * 唯一职责：启动 MCP server。
 */
fun main() {
    val realStdout = System.out
    val realStderr = System.err

    // 安装无损 stdout 过滤：整行 JSON-RPC 透传，杂质整行转 stderr
    System.setOut(PrintStream(McpStdoutFilterStream(realStdout, realStderr), true, "UTF-8"))

    loadMcpModules()
    val koin = GlobalContext.get()
    koin.get<MyMcpServer>().start()
}

/**
 * MCP server 装配器。
 * 通过 Koin 获取所有 McpToolProvider，汇聚 tool 列表后注册到 MCP SDK。
 */
class MyMcpServer(
    private val providers: List<McpToolProvider>
) {
    fun start() {
        val tools = providers.flatMap { it.provide() }
        val mcpJsonMapper = JacksonMcpJsonMapper(ObjectMapper())
        val transportProvider = StdioServerTransportProvider(mcpJsonMapper)

        var server = McpServer.sync(transportProvider)
            .serverInfo("deck-plugin-market-config", "0.1.0")
            .instructions(
                "写类工具两种语义：整体替换（不传 = 不改 / 空值 = 清空）与缺省保留原值——改前先 get 取原值，改后用 get 复查。" +
                        "引用类字段（bindingId / conditionId / aura_boost id / presetId / 卡池文件名）只能引用已存在对象，先用 list 取 id。" +
                        "资源类型、各资源 id 语义与删除语义（引用校验 / 级联 / 快照范围）用 tool_capabilities 查询；" +
                        "不传 resource 时同时返回 databasePath 与 cwd，动工前先查以确认本轮写入哪个库（两库数据不同步）。"
            )

        tools.forEach { handler ->
            val tool = McpSchema.Tool.builder()
                .name(handler.name)
                .description(handler.description)
                .inputSchema(mcpJsonMapper, handler.inputSchemaJson)
                .build()
            server = server.toolCall(tool) { _, request ->
                val result = handler.call(request.arguments().orEmpty())
                McpSchema.CallToolResult.builder()
                    .addTextContent(result.contentJson)
                    .isError(result.isError)
                    .build()
            }
        }

        server.build()
    }
}
