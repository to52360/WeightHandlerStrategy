-- 2026-08-25 T-026 数据库迁移脚本
-- 目标库: weightHandlerStrategy.db
-- 用途: CandidatePolicy 枚举全链退役 → card_purpose.candidate_policy 列废弃；
--       TACTICS_DOMINANT 语义由 tag 默认 N 承载；清理 T-023 测试孤儿行为行。
--       新库 CREATE TABLE 已不含 candidate_policy（CardPurposeRepository 已改），本脚本仅供旧库清理遗留。
--
-- 用法: sqlite3 weightHandlerStrategy.db < 本文件
--      或手动在 sqlite3 中逐条执行。

-- 1. card_purpose.candidate_policy 列废弃（死列，代码已不读写；T-018 先例：保留死列无害，可择机 DROP）
--   检查: PRAGMA table_info(card_purpose);
--   若存在 candidate_policy 列，执行（需 SQLite >= 3.35；旧版报错可忽略，保留死列无害）:
--   ALTER TABLE card_purpose DROP COLUMN candidate_policy;

-- 2. 显式 TACTICS_DOMINANT（2 行: PET_2_1、T010_TEST_CARD_1）→ N=1 语义迁移说明
--   ⚠ 逐卡 N 存储在上游卡数据（powerWeight 小数位编码，D-007），不在本库，SQL 无法在此迁移。
--   当前 2 行均带 DRAW_CARD 标签 → 新模型 tag 默认 N=1（DefaultPurposeTagIntentRuleProvider）自动保持
--   「战术未命中惜售」行为，无需数据改动。若生产环境存在显式 TACTICS_DOMINANT 且未带战术标签的卡，
--   需在卡权重配置（逐卡小数位）或用途标签层补 N=1，防止行为回退（Q-026 方案 C 迁移硬依赖）。
--   查证（期望 0 行）: SELECT card_id, purpose_tags FROM card_purpose
--     WHERE candidate_policy = 'TACTICS_DOMINANT' AND purpose_tags NOT LIKE '%DRAW_CARD%'
--     AND purpose_tags NOT LIKE '%CLEAN%' AND purpose_tags NOT LIKE '%FINISH%'
--     AND purpose_tags NOT LIKE '%SAVE_LIFE%' AND purpose_tags NOT LIKE '%GREED%';

-- 3. 显式 NORMAL（2 行: PET_2_2、T010_TEST_CARD_2）清值说明
--   新模型 N=0 = 全自由即原 NORMAL 语义；列废弃后清值无意义，随第 1 步 DROP 一并消除。

-- 4. card_group_behavior 孤儿行清理（T-023 configUi 测试数据泄漏，parent binding 已不存在）
--   检查: SELECT binding_id FROM card_group_behavior WHERE binding_id IN ('t023_keep','t023_set');
--   若存在，执行:
--   DELETE FROM card_group_behavior WHERE binding_id IN ('t023_keep','t023_set');
