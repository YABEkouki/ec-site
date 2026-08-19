ALTER TABLE categories
ADD COLUMN display_order INTEGER NOT NULL DEFAULT 1000;

UPDATE categories
SET display_order = 9999
WHERE system_category = TRUE;
