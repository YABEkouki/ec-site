-- Feature64 browser test sample data
-- 30 products are intentionally assigned to ONE category for pagination testing.
-- This is sample/manual test data, NOT a Flyway migration.
-- PostgreSQL
BEGIN;

UPDATE products p
SET image_path = 'sample/feature64_lightweight_camp_chair.png'
FROM categories c
WHERE p.category_id = c.id
  AND c.name = 'Feature64検索テスト'
  AND p.name = '軽量キャンプチェア';

UPDATE products p
SET image_path = 'sample/feature64_compact_fire_pit.png'
FROM categories c
WHERE p.category_id = c.id
  AND c.name = 'Feature64検索テスト'
  AND p.name = 'コンパクト焚き火台';

UPDATE products p
SET image_path = 'sample/feature64_camp_table.png'
FROM categories c
WHERE p.category_id = c.id
  AND c.name = 'Feature64検索テスト'
  AND p.name = 'キャンプテーブル';

UPDATE products p
SET image_path = 'sample/feature64_led_camp_lantern.png'
FROM categories c
WHERE p.category_id = c.id
  AND c.name = 'Feature64検索テスト'
  AND p.name = 'LEDキャンプランタン';

UPDATE products p
SET image_path = 'sample/feature64_camp_kettle.png'
FROM categories c
WHERE p.category_id = c.id
  AND c.name = 'Feature64検索テスト'
  AND p.name = 'キャンプ用ケトル';

UPDATE products p
SET image_path = 'sample/feature64_solo_tent.png'
FROM categories c
WHERE p.category_id = c.id
  AND c.name = 'Feature64検索テスト'
  AND p.name = 'ソロテント';

UPDATE products p
SET image_path = 'sample/feature64_camp_mat.png'
FROM categories c
WHERE p.category_id = c.id
  AND c.name = 'Feature64検索テスト'
  AND p.name = 'キャンプマット';

UPDATE products p
SET image_path = 'sample/feature64_outdoor_coffee_mill.png'
FROM categories c
WHERE p.category_id = c.id
  AND c.name = 'Feature64検索テスト'
  AND p.name = 'アウトドアコーヒーミル';

UPDATE products p
SET image_path = 'sample/feature64_camp_cooker_set.png'
FROM categories c
WHERE p.category_id = c.id
  AND c.name = 'Feature64検索テスト'
  AND p.name = 'キャンプクッカーセット';

UPDATE products p
SET image_path = 'sample/feature64_folding_water_tank.png'
FROM categories c
WHERE p.category_id = c.id
  AND c.name = 'Feature64検索テスト'
  AND p.name = '折りたたみウォータータンク';

UPDATE products p
SET image_path = 'sample/feature64_lightweight_backpack.png'
FROM categories c
WHERE p.category_id = c.id
  AND c.name = 'Feature64検索テスト'
  AND p.name = '軽量バックパック';

UPDATE products p
SET image_path = 'sample/feature64_trekking_poles.png'
FROM categories c
WHERE p.category_id = c.id
  AND c.name = 'Feature64検索テスト'
  AND p.name = 'トレッキングポール';

UPDATE products p
SET image_path = 'sample/feature64_outdoor_jacket.png'
FROM categories c
WHERE p.category_id = c.id
  AND c.name = 'Feature64検索テスト'
  AND p.name = 'アウトドアジャケット';

UPDATE products p
SET image_path = 'sample/feature64_waterproof_stuff_bag.png'
FROM categories c
WHERE p.category_id = c.id
  AND c.name = 'Feature64検索テスト'
  AND p.name = '防水スタッフバッグ';

UPDATE products p
SET image_path = 'sample/feature64_lightweight_rain_poncho.png'
FROM categories c
WHERE p.category_id = c.id
  AND c.name = 'Feature64検索テスト'
  AND p.name = '軽量レインポンチョ';

UPDATE products p
SET image_path = 'sample/feature64_outdoor_gloves.png'
FROM categories c
WHERE p.category_id = c.id
  AND c.name = 'Feature64検索テスト'
  AND p.name = 'アウトドアグローブ';

UPDATE products p
SET image_path = 'sample/feature64_folding_stool.png'
FROM categories c
WHERE p.category_id = c.id
  AND c.name = 'Feature64検索テスト'
  AND p.name = '折りたたみスツール';

UPDATE products p
SET image_path = 'sample/feature64_headlamp.png'
FROM categories c
WHERE p.category_id = c.id
  AND c.name = 'Feature64検索テスト'
  AND p.name = 'ヘッドライト';

UPDATE products p
SET image_path = 'sample/feature64_insulated_bottle.png'
FROM categories c
WHERE p.category_id = c.id
  AND c.name = 'Feature64検索テスト'
  AND p.name = '保温ボトル';

UPDATE products p
SET image_path = 'sample/feature64_outdoor_blanket.png'
FROM categories c
WHERE p.category_id = c.id
  AND c.name = 'Feature64検索テスト'
  AND p.name = 'アウトドアブランケット';

UPDATE products p
SET image_path = 'sample/feature64_lightweight_travel_bag.png'
FROM categories c
WHERE p.category_id = c.id
  AND c.name = 'Feature64検索テスト'
  AND p.name = '軽量トラベルバッグ';

UPDATE products p
SET image_path = 'sample/feature64_travel_pouch_set.png'
FROM categories c
WHERE p.category_id = c.id
  AND c.name = 'Feature64検索テスト'
  AND p.name = 'トラベルポーチセット';

UPDATE products p
SET image_path = 'sample/feature64_folding_travel_bag.png'
FROM categories c
WHERE p.category_id = c.id
  AND c.name = 'Feature64検索テスト'
  AND p.name = '折りたたみ旅行バッグ';

UPDATE products p
SET image_path = 'sample/feature64_neck_pillow.png'
FROM categories c
WHERE p.category_id = c.id
  AND c.name = 'Feature64検索テスト'
  AND p.name = 'ネックピロー';

UPDATE products p
SET image_path = 'sample/feature64_travel_bottle_set.png'
FROM categories c
WHERE p.category_id = c.id
  AND c.name = 'Feature64検索テスト'
  AND p.name = 'トラベルボトルセット';

UPDATE products p
SET image_path = 'sample/feature64_passport_case.png'
FROM categories c
WHERE p.category_id = c.id
  AND c.name = 'Feature64検索テスト'
  AND p.name = 'パスポートケース';

UPDATE products p
SET image_path = 'sample/feature64_compact_folding_umbrella.png'
FROM categories c
WHERE p.category_id = c.id
  AND c.name = 'Feature64検索テスト'
  AND p.name = 'コンパクト折りたたみ傘';

UPDATE products p
SET image_path = 'sample/feature64_travel_sacoche.png'
FROM categories c
WHERE p.category_id = c.id
  AND c.name = 'Feature64検索テスト'
  AND p.name = 'トラベルサコッシュ';

UPDATE products p
SET image_path = 'sample/feature64_usb_travel_adapter.png'
FROM categories c
WHERE p.category_id = c.id
  AND c.name = 'Feature64検索テスト'
  AND p.name = 'USBトラベルアダプター';

UPDATE products p
SET image_path = 'sample/feature64_lightweight_suitcase.png'
FROM categories c
WHERE p.category_id = c.id
  AND c.name = 'Feature64検索テスト'
  AND p.name = '軽量キャリーケース';

COMMIT;
