-- 2026-09-10 T-TG-009 卡组策略预设（strategy_preset）
-- 背景：card-tag-model 阶段二 —— 「让同一用途在不同卡组有不同规则」已有现成通道（卡组私有分组
--       + OverrideBehavior / SurplusGateBehavior + GROUP 绑定树，见 D-TG-004）；缺的是**复用**：
--       把这套卡组私有调整搬到下一个卡组，免去逐卡组重配。
-- 形态（D-TG-004 路线 a：模板/复制式）：
--   预设 = 一套分组绑定骨架的快照；**应用时展开**成目标卡组的私有绑定（新 id、新 managerId）。
--   引擎零改动、不需要「当前卡组」上下文（Q-TG-001 阻塞由此解耦）。
--   ⚠️ 代价同 K-TG-003：应用后预设与卡组各自独立，改预设不会回溯已应用的卡组，需重新应用。
-- 设计：strategy_preset_binding 与 card_group_binding **逐列同构**，behaviors 以
--       `[{"type":"OVERRIDE","payload":"<原样>"}]` 原样保存 card_group_behavior.payload 字符串
--       → 提取/应用都是**搬运行**，零多态序列化、零载荷解析。
-- 执行（两库都要跑，表结构对齐）：
--   sqlite3 weightHandlerStrategy.db ".read 2026-09-10_t-tg-009_strategy_preset.sql"
--   sqlite3 F:/myApp/HBuddy_2/plugin/WeightHandlerStrategy/weightHandlerStrategy.db ".read ..."

CREATE TABLE IF NOT EXISTS strategy_preset
(
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    description TEXT,
    created_at  TEXT
);

CREATE TABLE IF NOT EXISTS strategy_preset_binding
(
    id              TEXT PRIMARY KEY,
    preset_id       TEXT NOT NULL,
    name            TEXT NOT NULL,
    card_ids        TEXT NOT NULL DEFAULT '[]',
    description     TEXT,
    member_type     TEXT NOT NULL DEFAULT 'STATIC',
    condition_id    TEXT,
    include_derived INTEGER,
    behaviors_json  TEXT NOT NULL DEFAULT '[]'
);

CREATE INDEX IF NOT EXISTS idx_strategy_preset_binding_preset
    ON strategy_preset_binding (preset_id);

-- ── 验证段（必跑）──
SELECT '表结构 strategy_preset' AS check_item, name, type
FROM pragma_table_info('strategy_preset');

SELECT '表结构 strategy_preset_binding' AS check_item, name, type
FROM pragma_table_info('strategy_preset_binding');

-- 空表起步（预设由 MCP save_strategy_preset 从现有卡组提取，无需种子）
SELECT '预设数（初始应为 0）' AS check_item, COUNT(*) AS cnt
FROM strategy_preset;
SELECT '预设绑定数（初始应为 0）' AS check_item, COUNT(*) AS cnt
FROM strategy_preset_binding;
