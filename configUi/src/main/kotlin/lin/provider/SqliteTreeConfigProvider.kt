package lin.provider

import lin.myLog
import lin.repository.card_group.*
import lin.rule.tree.EvaluatorTreeBindingType
import lin.rule.tree.EvaluatorTreeConfig
import lin.serviceLoader.provider.TreeConfigProvider
import lin.ui.service.TreeConfigService

/**
 * [TreeConfigProvider] 的 SQLite 实现，供策略层通过 SPI 加载评估树配置。
 *
 * 在 SPI 边界完成两层处理（引擎零改动，它只收到"剪好的树"）：
 * 1. **绑定过滤**：`GROUP` 绑定按**启用卡组下的绑定条目**过滤；`PURPOSE_TAG` 绑定按
 *    [lin.ui.card_purpose.PurposeTagTreeBindingPolicy] 过滤；`CARD` 绑定不过滤。
 * 2. **用途预设裁剪**（T-TG-015）：当前卡组引用的**用途预设**声明了「某用途保留哪些树」（白名单，
 *    未声明的用途 ⇒ 该用途树全禁），消费方增量项再减；
 *    **粒度是 (用途, 树)** ⇒ 实现为**按 tag 收窄 `bindingIds`**（多 tag 树不能整棵删），
 *    剔空则整棵树不输出。
 * 3. **归属可见性**（Q-TG-004 定论形态 D）：`PURPOSE_TAG` 树的 `manager_id` 是**归属卡组**（引擎语义）——
 *    归属**他组** ⇒ 整棵不输出（私有树不对别组可见）；归属**本组** ⇒ 可见且**跳过预设白名单**（归属即拥有，
 *    但仍受消费方增量项约束 ⇒ 需要时可在本卡组把它排除）；**无归属**（null）⇒ 全局共享，照旧走白名单。
 *    `GROUP` / `CARD` 树**不参与**该判定（各按自身机制：前者靠绑定条目、后者不过滤）。
 * 4. **悬空防御**（T-TG-020 / Q-TG-004）：两处悬空都不静默 —— 卡组引用的**预设行不存在** ⇒ 语义不变
 *    （仍按"引用了预设" ⇒ 用途树全禁，方向与「空预设 = 要精准不要兜底」一致，**不**回落成兜底全开）+ `warn`；
 *    `PURPOSE_TAG` 树的**归属卡组不存在** ⇒ 整棵不输出 + `warn`（此前两个分支都只有 `debug` ⇒ 无法与"我主动禁用"区分）。
 *
 * ⚠️ **组装走 [TreeConfigService]**（`config_data` 只存 root、叶子在 `evaluator_leaf_config` 表）——
 * 此前本类直接 `readValue(configData, EvaluatorTreeConfig::class.java)`，自叶子拆表后**每棵树都反序列化失败**
 * ⇒ 引擎拿不到任何树（K-TG-007，2026-09-11 修复）；`GROUP` 过滤此前拿**卡组 manager id**比**绑定条目 id**
 * ⇒ 恒 false 剔空（K-TG-008，同步修复）。两者都是"**恢复**"，不是新能力。
 *
 * 「当前卡组」的解析统一走 [CurrentDeckContext]（单点），不再各处重抄 `findManagers(onlyEnabled = true)`。
 */
class SqliteTreeConfigProvider(
    private val treeConfigService: TreeConfigService,
    private val cardGroupService: CardGroupService,
    private val tagPolicy: lin.ui.card_purpose.PurposeTagTreeBindingPolicy,
    /** T-TG-015：预设项 + 消费方增量项（同一张 `strategy_dimension_item`，按 scope 区分）。 */
    private val presetRepository: StrategyPresetRepository,
    private val currentDeck: CurrentDeckContext,
    private val resolver: DimensionItemResolver
) : TreeConfigProvider {

    override fun findById(id: String): EvaluatorTreeConfig? {
        val (entity, config) = treeConfigService.findById(id) ?: return null
        if (!entity.enabled) return null
        val resolved = config ?: return null
        return applyDeckFilters(entity.id, entity.managerId, resolved, loadDeckContext())
    }

    override fun findAll(): List<EvaluatorTreeConfig> {
        val context = loadDeckContext()
        return treeConfigService.loadAll()
            .filter { (entity, _) -> entity.enabled }
            .mapNotNull { (entity, config) ->
                config?.let { applyDeckFilters(entity.id, entity.managerId, it, context) }
            }
    }

    // ─────────────────────── 一次加载内的共享上下文 ───────────────────────

    /**
     * 一次加载（`findAll`）内复用，避免逐树查库。
     *
     * @param deckId                 当前卡组（`PURPOSE_TAG` 归属判定基准）；null = 无启用卡组
     * @param allDeckIds             库中**全部**卡组 id（判"归属是否悬空"，**不看 enabled**：暂时停用的卡组不算悬空）
     * @param enabledGroupBindingIds 启用卡组下的**绑定条目** id —— `GROUP` 树的 `bindingIds` 就是这个空间
     * @param enabledTagIds          允许参与 `PURPOSE_TAG` 绑定的用途
     * @param treeSelection          预设白名单 + 消费方额外排除
     */
    private data class DeckContext(
        val deckId: String?,
        val allDeckIds: Set<String>,
        val enabledGroupBindingIds: Set<String>,
        val enabledTagIds: Set<String>,
        val treeSelection: DimensionItemResolver.TreeSelection
    )

    private fun loadDeckContext(): DeckContext {
        val deck = currentDeck.current()
        val presetId = deck?.presetId?.takeIf { it.isNotBlank() }
        // 一次加载同时得到「全部卡组 id（判归属悬空）」与「启用卡组的绑定条目」——
        // 用 onlyEnabled=false 取全量再自行筛 enabled，与只查启用卡组等价，但不重复读一遍全表。
        val managers = cardGroupService.loadAll(onlyEnabled = false)
        // T-TG-020：悬空引用（预设行已不存在）**不改判定** —— 仍按「引用了预设」走白名单，取到空集 ⇒ 用途树全禁；
        // 但必须可见：`presetReferenced = presetId != null` 只看卡组一侧，预设被删/脏数据时用户看不到任何提示。
        if (deck != null && presetId != null && presetRepository.findPresetById(presetId) == null) {
            myLog.warn {
                "悬空预设引用：卡组引用的用途预设不存在 ⇒ 该卡组全部 PURPOSE_TAG 树不输出、时序覆盖退化为未声明" +
                        "（等同空预设语义，非兜底全开）: managerId=${deck.id} presetId=$presetId；" +
                        "修法 = restore_snapshot 恢复该预设快照，或 save_card_group_preset（不带 presetId）清空该卡组的引用"
            }
        }
        return DeckContext(
            deckId = deck?.id,
            allDeckIds = managers.map { it.cardGroupManagerId }.toSet(),
            enabledGroupBindingIds = managers.filter { it.enabled }
                .flatMap { manager -> manager.bindings.map { it.id } }
                .toSet(),
            enabledTagIds = tagPolicy.enabledTags.map { it.value }.toSet(),
            treeSelection = resolver.treeSelection(
                presetReferenced = presetId != null,
                presetKeepByTag = presetId
                    ?.let { presetRepository.findTreeSelections(DimensionScope.PRESET, it) }
                    ?: emptyMap(),
                consumerExcludeByTag = deck
                    ?.let { presetRepository.findTreeSelections(DimensionScope.CARD_GROUP, it.id) }
                    ?: emptyMap()
            )
        )
    }

    /**
     * 按绑定类型分别过滤；返回 null = 该树不输出。
     *
     * - `GROUP`：绑定 id 必须是**启用卡组下的绑定条目**
     * - `CARD`：不过滤
     * - `PURPOSE_TAG`：先判归属（悬空/他组 ⇒ 不输出），再按**归属决定是否走预设白名单**，最后按启用用途过滤
     *
     * @param managerId 树的归属卡组（只有 `PURPOSE_TAG` 分支消费它，见 Q-TG-004 定论形态 D）
     */
    private fun applyDeckFilters(
        treeId: String,
        managerId: String?,
        config: EvaluatorTreeConfig,
        context: DeckContext
    ): EvaluatorTreeConfig? = when (config.bindingType) {
        EvaluatorTreeBindingType.GROUP -> {
            val kept = config.bindingIds.filter { id ->
                val enabled = id in context.enabledGroupBindingIds
                if (!enabled) myLog.debug { "跳过已禁用分组的绑定: bindingId=$id" }
                enabled
            }
            config.copy(bindingIds = kept)
        }

        EvaluatorTreeBindingType.CARD -> config

        EvaluatorTreeBindingType.PURPOSE_TAG -> filterPurposeTagTree(treeId, managerId, config, context)
    }

    /**
     * `PURPOSE_TAG` 树的**归属可见性 + 用途收窄**（Q-TG-004 定论形态 D）。
     *
     * - 归属**悬空**（`manager_id` 指向不存在的卡组）⇒ 整棵不输出 + `warn`（防静默失效，与 T-TG-020 同源）
     * - 归属**他组** ⇒ 整棵不输出（私有树不对别组可见）
     * - 归属**本组** ⇒ **跳过预设白名单**（归属即拥有），仅受消费方增量项约束
     * - **无归属** ⇒ 全局共享，照旧走预设白名单 + 消费方排除
     */
    private fun filterPurposeTagTree(
        treeId: String,
        managerId: String?,
        config: EvaluatorTreeConfig,
        context: DeckContext
    ): EvaluatorTreeConfig? {
        if (managerId != null) {
            if (managerId !in context.allDeckIds) {
                myLog.warn {
                    "用途树的归属卡组不存在，整棵不输出（悬空归属，等同静默失效）: treeId=$treeId managerId=$managerId；" +
                            "修法 = 重建该树并指定存在的归属卡组（create_draft_tree），或在 UI 里重存该树（会把归属清成全局共享）"
                }
                return null
            }
            if (managerId != context.deckId) {
                myLog.debug { "跳过其它卡组的专属用途树: treeId=$treeId managerId=$managerId 当前卡组=${context.deckId}" }
                return null
            }
        }
        val kept = (if (managerId != null) {
            resolver.narrowOwnTreeTags(treeId, config.bindingIds, context.treeSelection)
        } else {
            resolver.narrowTreeTags(treeId, config.bindingIds, context.treeSelection)
        }).filter { tag ->
            val enabled = tag in context.enabledTagIds
            if (!enabled) myLog.debug { "跳过已禁用用途标签的绑定: tagId=$tag" }
            enabled
        }
        if (kept.isEmpty()) {
            myLog.debug { "用途树无保留用途，整棵不输出: treeId=$treeId（绑定=${config.bindingIds}）" }
            return null
        }
        return config.copy(bindingIds = kept)
    }
}
