package lin.ai.config

/**
 * 卡组卡池查询服务。
 * 独立于评估树配置，专门负责 .cardgroup 文件读取 + hs.cards 卡牌元数据查询。
 * MCP 让 AI 先知道有哪些卡组可编排，再指定某个卡组获取卡池详情。
 */
interface CardGroupQueryService {
    /**
     * 列出 data/cardgroup/ 下所有 .cardgroup 文件。
     * 返回文件名（不含后缀）+ 启用状态 + 卡牌数量。
     */
    fun listCardGroupSources(): List<CardGroupSourceInfo>

    /**
     * 获取指定 .cardgroup 文件的完整卡池详情。
     * 每张卡的 name / text 从 hs.cards 批量查询，text 可能为 null。
     * 文件不存在时返回 null。
     */
    fun getCardGroupDetail(fileName: String): CardGroupDetail?
}