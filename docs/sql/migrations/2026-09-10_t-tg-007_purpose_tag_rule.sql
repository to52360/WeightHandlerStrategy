-- 2026-09-10 T-TG-007 用途行为落库（purpose_tag_rule）
-- 背景：card-tag-model 阶段三 —— 用途意图规则（stage / orderWeight / N / replan / priority）
--       此前硬编码在 DefaultPurposeTagIntentRuleProvider（Kotlin），与标记定义/评估树绑定三处分散，
--       违反「声明集中」（用户：声明不集中易复发）。本表承载第一层排序兜底。
-- 语义（D-TG-003）：
--   行存在 = 有规则（参与 UseIntentDeriver 的 priority 选优）；无行 = 无规则（如 FINISH / 纯标记）。
--   ⚠️ 不可用「行存在但列全默认值」冒充无规则 —— 那会以默认 priority=100 参与选优，
--      与 GREED(100) 平手时 maxByOrNull 取集合首遇项，导致 stage 不确定地在 SETUP/GENERAL 间跳。
--   字段级只有二态：列值即最终值，不区分「未声明」与「声明为默认值」
--   （UseIntentDeriver 的 `config.x ?: rule?.x ?: 内建默认` 已把两者归一）。
-- 兼容：CREATE TABLE IF NOT EXISTS + INSERT OR IGNORE —— 新库由 PurposeTagRuleRepository.initSchema
--       建表播种；本脚本供旧库补建，重复执行安全、不覆盖用户改过的规则值。
-- 执行（两库都要跑，表结构对齐）：
--   sqlite3 weightHandlerStrategy.db ".read 2026-09-10_t-tg-007_purpose_tag_rule.sql"
--   sqlite3 F:/myApp/HBuddy_2/plugin/WeightHandlerStrategy/weightHandlerStrategy.db ".read ..."

CREATE TABLE IF NOT EXISTS purpose_tag_rule
(
    tag_id                         TEXT PRIMARY KEY,
    default_stage                  TEXT    NOT NULL,
    default_order_weight           REAL    NOT NULL DEFAULT 0.0,
    default_surplus_idle_threshold INTEGER,
    default_replan_after_use       INTEGER NOT NULL DEFAULT 0,
    priority                       INTEGER NOT NULL DEFAULT 100
);

-- 内置 6 条（与 DefaultPurposeTagIntentRuleProvider 现值逐字对齐；FINISH 无条目）
INSERT OR IGNORE INTO purpose_tag_rule
(tag_id, default_stage, default_order_weight, default_surplus_idle_threshold, default_replan_after_use, priority)
VALUES ('SAVE_LIFE', 'LATE', 0.0, 1, 0, 400),
       ('CLEAN', 'MID', 1.0, 1, 0, 300),
       ('GREED', 'SETUP', 0.0, NULL, 0, 100),
       ('VALUE', 'GENERAL', 0.0, NULL, 0, 50),
       ('EXTRA_COST', 'GENERAL', 0.0, NULL, 0, 50),
       ('DRAW_CARD', 'MID', 0.0, NULL, 0, 60);

-- ── 验证段（必跑）──
SELECT '表结构' AS check_item, name, type, "notnull" AS not_null, dflt_value AS default_value
FROM pragma_table_info('purpose_tag_rule');

-- 期望 6 行（FINISH 不在其中 —— 它无规则，不参与 priority 选优）
SELECT '内置规则数（应为 6）' AS check_item, COUNT(*) AS cnt
FROM purpose_tag_rule;

-- 期望 0 行：stage 必须是合法 UseStage（FIRST/SETUP/MID/LATE/GENERAL/LAST）
SELECT '非法 stage（应为 0）' AS check_item, tag_id, default_stage
FROM purpose_tag_rule
WHERE default_stage NOT IN ('FIRST', 'SETUP', 'MID', 'LATE', 'GENERAL', 'LAST');

-- 全部明细（人工比对：SAVE_LIFE LATE/N=1/400、CLEAN MID/1.0/N=1/300、DRAW_CARD MID/60 …）
SELECT tag_id,
       default_stage,
       default_order_weight,
       default_surplus_idle_threshold,
       default_replan_after_use,
       priority
FROM purpose_tag_rule
ORDER BY priority DESC;
