package lin.utils.startup

import lin.bean.ConfigSlice
import lin.bean.ConfigSliceScope
import lin.bean.SliceEntry
import lin.bean.usePlan.PurposeTagId
import lin.domain.context.ChangeAnimationTime
import lin.domain.use.AwaitAnimationStrategy

/**
 * 用途标签 → 使用策略绑定：将用途标签映射为 per-card 使用动作（分片方式）。
 *
 * 通过 builder.slices 追加 [SliceEntry]（scope=Tag），
 * builder.expandSlices() 自动按 tag→cardIds 索引展开到对应卡。
 *
 * 新增标签策略：在此追加 SliceEntry，builder 零改动。
 */
class CardPurposeBehaviorStep : ConfigBindingStep {
    override fun contribute(builder: CardCombinedConfigBuilder) {
        // 清场 → 等待清场动画
        builder.slices += SliceEntry(
            scope = ConfigSliceScope.Tag(PurposeTagId.CLEAN),
            slice = ConfigSlice(useStrategies = listOf(AwaitAnimationStrategy(ChangeAnimationTime)))
        )
    }
}
