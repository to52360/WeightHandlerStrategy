-- 2026-08-19 T-005/T-010 数据库迁移脚本
-- 目标库: weightHandlerStrategy.db
-- 用途: 为旧库补加候选策略/评分通道列（新库由 CREATE TABLE IF NOT EXISTS 自动建好，无需执行本脚本）
--
-- 用法: sqlite3 weightHandlerStrategy.db < 本文件
--      或手动在 sqlite3 中逐条执行。

-- 1. card_purpose 表补 candidate_policy 列（三态：NULL=跟随用途标签默认，显式值=覆盖）
-- 幂等性说明: 仅当列不存在时执行（SQLite 无 IF NOT EXISTS for ADD COLUMN，需先检查）
--   检查: PRAGMA table_info(card_purpose);
--   若列不存在，执行:
--   ALTER TABLE card_purpose ADD COLUMN candidate_policy TEXT;

-- 2. tree_config 表补 channel 列（评分通道显式声明：GENERAL/TACTICAL，NULL=跟随绑定目标候选策略推导）
--   检查: PRAGMA table_info(tree_config);
--   若列不存在，执行:
--   ALTER TABLE tree_config ADD COLUMN channel TEXT;

-- 建议执行方式（一次性命令，自动判断列是否存在）:
--   sqlite3 weightHandlerStrategy.db "ALTER TABLE card_purpose ADD COLUMN candidate_policy TEXT;"
--   sqlite3 weightHandlerStrategy.db "ALTER TABLE tree_config ADD COLUMN channel TEXT;"
--   若报 duplicate column 说明列已存在，可忽略。
