-- 開発用サンプルカテゴリ
-- Flyway管理対象外
-- 再実行可能

INSERT INTO categories (
    name,
    active,
    system_category,
    display_order
)
VALUES
    ('パソコン・周辺機器', TRUE, FALSE, 100),
    ('スマートフォン・タブレット', TRUE, FALSE, 200),
    ('家電', TRUE, FALSE, 300),
    ('文房具', TRUE, FALSE, 400),
    ('書籍', TRUE, FALSE, 500)
ON CONFLICT (name)
DO UPDATE SET
    active = EXCLUDED.active,
    system_category = EXCLUDED.system_category,
    display_order = EXCLUDED.display_order,
    updated_at = CURRENT_TIMESTAMP;
