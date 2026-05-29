package lin.provider

import lin.bean.usePlan.CardUseConfig
import lin.bean.usePlan.UseIntent
import lin.card_use.db.CardUseConfigRepository
import lin.domain.use.plan.UseIntentDeriver
import lin.domain.use.plan.UseIntentProvider

class SqliteUseIntentProvider(
    private val repository: CardUseConfigRepository
) : UseIntentProvider {
    override fun intentOf(cardGroupIds: Set<String>): UseIntent {
        // 查找这些分组中，第一个在数据库中配置了 CardUseConfig 的记录
        val configEntity = cardGroupIds.asSequence()
            .mapNotNull { repository.findByCardGroupId(it) }
            .firstOrNull()

        val config = configEntity?.toDomain() ?: CardUseConfig()
        return UseIntentDeriver.derive(config)
    }
}
