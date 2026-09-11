-- 2026-09-11 T-TG-015 用途预设载体统一：strategy_dimension_item（单表 · 键列 + payload JSON）
-- 方案依据：architecture-context/card-tag-model/cross-dialogue/Q-TG-003-use-preset-final.md
--
-- 变更：
--   · 新表 strategy_dimension_item：预设项与卡组增量项**结构同构**，用 scope 区分
--     （PRESET = 预设自己声明的项；CARD_GROUP = 卡组引用预设时的增量项）；
--   · 语义「**行 = (归属域, 归属方, 维度, 用途) 的一条声明；payload = 该声明的值**」——
--     `PURPOSE_TREE` → `{"treeIds":["treeA",...]}`（空数组 = 声明为"一棵都不要"）；
--     `PURPOSE_TIMING` → `{"defaultStage":...}`（**字段未出现 = 不覆盖**）；
--   · 旧表 strategy_preset_purpose 的时序覆盖行**搬运**为 payload JSON：
--     N 列 IS NULL 时**不写入**该键 —— 保持「字段未出现 = 不覆盖」与
--     「字段出现且为 null = 覆盖为无门槛」两态可分（即 K-TG-005 的解法）；
--   · 旧表 strategy_preset_excluded_tree **不搬运**：树维度语义由「排除（黑名单）」改为
--     「按用途选择（白名单）」，旧行**不可平移**（实测源库与部署库均 0 行）；
--   · 锚表 strategy_preset 保留不动。
--
-- 执行（两库都要跑；执行前先备份 .bak-<日期>）：
--   sqlite3 weightHandlerStrategy.db ".read docs/sql/migrations/2026-09-11_t-tg-015_strategy_dimension_item.sql"
--   sqlite3 F:/myApp/HBuddy_2/plugin/WeightHandlerStrategy/weightHandlerStrategy.db ".read ..."
--
-- ⚠️ 本脚本按**一次性**设计：旧表已被 DROP 后再跑，会在「搬运」段报
--    `no such table: strategy_preset_purpose` —— 属**预期噪音**（建表与前两个 DROP 已生效），
--    不影响后续验证段；若已无旧表可搬，可忽略该行错误直接看验证段结果。

BEGIN;

-- 同日修订说明：`item_key` 已并入 payload（键留列、值进 payload）⇒ 先丢弃当日创建、尚无消费的旧形状表。
-- 该表创建于同日、两库均 0 行、无代码写过 ⇒ 无数据损失。
DROP TABLE IF EXISTS strategy_dimension_item;

CREATE TABLE strategy_dimension_item
(
    scope       TEXT NOT NULL, -- PRESET | CARD_GROUP
    owner_id    TEXT NOT NULL, -- scope=PRESET → preset_id；scope=CARD_GROUP → card_group_manager.id
    dimension   TEXT NOT NULL, -- PURPOSE_TREE | PURPOSE_TIMING（可扩）
    purpose_tag TEXT NOT NULL, -- 归属用途（CLEAN…）
    payload     TEXT NOT NULL, -- 该维度的值（JSON）
    PRIMARY KEY (scope, owner_id, dimension, purpose_tag)
);

-- 搬运旧时序覆盖行（可重入：先清同源项再插）
DELETE
FROM strategy_dimension_item
WHERE scope = 'PRESET'
  AND dimension = 'PURPOSE_TIMING';

INSERT INTO strategy_dimension_item (scope, owner_id, dimension, purpose_tag, payload)
SELECT 'PRESET',
       preset_id,
       'PURPOSE_TIMING',
       tag_id,
       json_remove(
               json_object(
                       'defaultStage', default_stage,
                       'defaultOrderWeight', default_order_weight,
                       'defaultReplanAfterUse', json(CASE
                                                         WHEN default_replan_after_use IS NULL THEN 'null'
                                                         WHEN default_replan_after_use = 1 THEN 'true'
                                                         ELSE 'false' END),
                       'defaultSurplusIdleThreshold', default_surplus_idle_threshold
               ),
               CASE
                   WHEN default_surplus_idle_threshold IS NULL
                       THEN '$.defaultSurplusIdleThreshold'
                   ELSE '$.keep' -- 路径不存在 ⇒ json_remove 为 no-op
                   END
       )
FROM strategy_preset_purpose;

DROP TABLE IF EXISTS strategy_preset_excluded_tree;
DROP TABLE IF EXISTS strategy_preset_purpose;

COMMIT;

-- ── 验证段（必跑）──

SELECT 'strategy_dimension_item 结构' AS check_item, name, type, "notnull" AS not_null
FROM pragma_table_info('strategy_dimension_item');

SELECT '旧表残留（应为 0 行）' AS check_item, name
FROM sqlite_master
WHERE type = 'table'
  AND name IN ('strategy_preset_excluded_tree', 'strategy_preset_purpose');

SELECT '维度项行数' AS check_item, scope, dimension, count(*) AS cnt
FROM strategy_dimension_item
GROUP BY scope, dimension;

SELECT '锚表 strategy_preset 保留' AS check_item, count(*) AS presets
FROM strategy_preset;
