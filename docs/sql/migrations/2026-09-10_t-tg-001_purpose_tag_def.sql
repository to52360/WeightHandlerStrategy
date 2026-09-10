-- 2026-09-10 T-TG-001 标记定义落库 + 用途绑定
-- 背景：card-tag-model T-TG-001 —— 标记（Tag）此前无持久化载体，定义硬编码在
--       DefaultPurposeTagProvider（Kotlin），自定义标记无处登记 → MCP 打标被白名单拒绝。
-- 语义（术语见 architecture-context/docs/术语表.md）：
--   purpose_tag_def = 标记的**唯一集中声明处**（T-TG-001 载体先建对，用途行为切库排后续）。
--   bound_purpose   = 用途绑定：指向某个战略用途的 tag_id；NULL = 纯标记（零副作用，仅供查询）。
--   builtin         = 1 系统内置战略用途（不可删，且只有它能作为绑定目标）；0 用户/AI 登记的自定义标记。
-- ⚠️ 绑定只允许一层，目标只能是战略用途（builtin=1），禁止 tag → tag 链式（D-TG-002）。
-- 兼容：CREATE TABLE IF NOT EXISTS + INSERT OR IGNORE —— 新库由 PurposeTagDefRepository.initSchema
--       建表播种，本脚本仅供旧库补建；重复执行安全，不覆盖用户改名/改绑定的结果。
-- 执行（两库都要跑，表结构对齐）：
--   sqlite3 weightHandlerStrategy.db ".read 2026-09-10_t-tg-001_purpose_tag_def.sql"
--   sqlite3 F:/myApp/HBuddy_2/plugin/WeightHandlerStrategy/weightHandlerStrategy.db ".read ..."

CREATE TABLE IF NOT EXISTS purpose_tag_def
(
    tag_id        TEXT PRIMARY KEY,
    display_name  TEXT    NOT NULL,
    description   TEXT,
    bound_purpose TEXT,
    builtin       INTEGER NOT NULL DEFAULT 0,
    created_date  TEXT
);

INSERT OR IGNORE INTO purpose_tag_def
(tag_id, display_name, description, bound_purpose, builtin, created_date)
VALUES ('SAVE_LIFE', '保命', '系统内置战略用途', NULL, 1, date('now')),
       ('CLEAN', '解场/清场', '系统内置战略用途', NULL, 1, date('now')),
       ('GREED', '成长/贪婪', '系统内置战略用途', NULL, 1, date('now')),
       ('FINISH', '斩杀', '系统内置战略用途', NULL, 1, date('now')),
       ('VALUE', '普通价值', '系统内置战略用途', NULL, 1, date('now')),
       ('EXTRA_COST', '额外费用', '系统内置战略用途', NULL, 1, date('now')),
       ('DRAW_CARD', '过牌', '系统内置战略用途', NULL, 1, date('now'));

-- ── 验证段（必跑，ALTER/CREATE 曾静默不生效）──
SELECT '表结构' AS check_item, name, type, "notnull" AS not_null, dflt_value AS default_value
FROM pragma_table_info('purpose_tag_def');

SELECT '内置战略用途数（应为 7）' AS check_item, COUNT(*) AS cnt
FROM purpose_tag_def
WHERE builtin = 1;

SELECT '标记定义总数（应 >= 7）' AS check_item, COUNT(*) AS cnt
FROM purpose_tag_def;

-- 绑定目标必须落在战略用途上（应为 0 行；非 0 说明写入侧校验失效）
SELECT '非法绑定目标（应为 0）' AS check_item, d.tag_id, d.bound_purpose
FROM purpose_tag_def d
WHERE d.bound_purpose IS NOT NULL
  AND d.bound_purpose NOT IN (SELECT tag_id FROM purpose_tag_def WHERE builtin = 1);
