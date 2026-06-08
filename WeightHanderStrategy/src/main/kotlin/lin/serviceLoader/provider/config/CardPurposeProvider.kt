package lin.serviceLoader.provider.config

import lin.bean.usePlan.CardPurpose

/**
 * 卡牌用途标签提供者。
 *
 * SPI 入口，configUi 通过此接口读取 card_purpose 表。
 * Provider 自主决定数据范围（全量/按启用状态筛选/其他数据源）。
 * 引擎端提供默认实现返回空 Map。
 * key 是cardId
 */
fun interface CardPurposeProvider {
    fun findAllEnabled(): Map<String, CardPurpose>

    // ARCH-TODO: 未来按 manager_id 管理或采用 UI 分页，当前暂用 id 集合查询
    fun findByIds(ids: Set<String>): Map<String, CardPurpose> {
        if (ids.isEmpty()) return emptyMap()
        return findAllEnabled().filterKeys { it in ids }
    }
}
