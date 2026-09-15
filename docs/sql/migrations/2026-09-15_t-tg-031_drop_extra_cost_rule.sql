-- T-TG-031：删除 EXTRA_COST 的用途规则行（配置面无效旋钮）
--
-- 背景：持有 EXTRA_COST 标签的牌由 ExtCostStrategy 接管（额外费用双世界比较），
--       出牌时机写死在 find 阶段先于组合、**不进 UsePlanOrderer** ⇒ `purpose_tag_rule` 里
--       EXTRA_COST 行的 stage / orderWeight **不被消费**，留着只会以 priority=50 与 VALUE 平手，
--       在 `UseIntentDeriver.maxByOrNull` 里制造不确定的 stage，并让它出现在时序候选面上。
-- 语义：删行 = 该用途退出「规则存在」集合（同 FINISH 的处理）；仍是**查询/路由标签**
--       （`PurposeTagId.EXTRA_COST` + `COINProvide.mechanismPurposes` 启动期注入，不依赖 DB）。
-- 兼容：无列变更、可重复执行（DELETE 幂等）；行删除后 `BUILTIN_RULES` 的 `INSERT OR IGNORE`
--       也不会把它种回来（种子已同步移除，见 `PurposeTagRuleRepository.BUILTIN_RULES`）。
--
-- 执行（两库都要跑，表结构对齐 / 数据不同步）：
--   sqlite3 weightHandlerStrategy.db ".read 2026-09-15_t-tg-031_drop_extra_cost_rule.sql"
--   sqlite3 F:/myApp/HBuddy_2/plugin/WeightHandlerStrategy/weightHandlerStrategy.db ".read ..."

DELETE FROM purpose_tag_rule WHERE tag_id = 'EXTRA_COST';

-- ── 验证段（必跑）──
SELECT '剩余规则行' AS check_item, count(*) AS value FROM purpose_tag_rule
UNION ALL
SELECT 'EXTRA_COST 残留(应为0)', count(*) FROM purpose_tag_rule WHERE tag_id = 'EXTRA_COST';
