ALTER TABLE categories
    ADD COLUMN system_category BOOLEAN
        NOT NULL
        DEFAULT FALSE;

UPDATE categories
SET system_category = TRUE
WHERE name = '未分類';