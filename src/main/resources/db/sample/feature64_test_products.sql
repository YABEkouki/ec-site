-- Feature64 browser test sample data
-- 30 products are intentionally assigned to ONE category for pagination testing.
-- This is sample/manual test data, NOT a Flyway migration.
-- PostgreSQL
BEGIN;
INSERT INTO categories (name, active, system_category, display_order)
SELECT 'Feature64検索テスト', true, false, 9000
WHERE NOT EXISTS (
    SELECT 1 FROM categories WHERE name = 'Feature64検索テスト'
);

INSERT INTO products (name, price, stock, description, active, category_id, created_at, updated_at)
SELECT '軽量キャンプチェア', 4980, 12, '軽量で持ち運びやすい折りたたみ式のキャンプチェア。ソロキャンプやアウトドアで使いやすいモデルです。', true, c.id,
       CURRENT_TIMESTAMP - INTERVAL '29 minutes',
       CURRENT_TIMESTAMP - INTERVAL '29 minutes'
FROM categories c
WHERE c.name = 'Feature64検索テスト'
  AND NOT EXISTS (
      SELECT 1 FROM products p
      WHERE p.name = '軽量キャンプチェア' AND p.category_id = c.id
  );

INSERT INTO products (name, price, stock, description, active, category_id, created_at, updated_at)
SELECT 'コンパクト焚き火台', 5980, 8, 'コンパクトに収納できる焚き火台。ソロキャンプで焚き火や簡単な調理を楽しめます。', true, c.id,
       CURRENT_TIMESTAMP - INTERVAL '28 minutes',
       CURRENT_TIMESTAMP - INTERVAL '28 minutes'
FROM categories c
WHERE c.name = 'Feature64検索テスト'
  AND NOT EXISTS (
      SELECT 1 FROM products p
      WHERE p.name = 'コンパクト焚き火台' AND p.category_id = c.id
  );

INSERT INTO products (name, price, stock, description, active, category_id, created_at, updated_at)
SELECT 'キャンプテーブル', 6980, 15, '軽量で組み立てやすいアウトドアテーブル。キャンプでの食事や調理に便利です。', true, c.id,
       CURRENT_TIMESTAMP - INTERVAL '27 minutes',
       CURRENT_TIMESTAMP - INTERVAL '27 minutes'
FROM categories c
WHERE c.name = 'Feature64検索テスト'
  AND NOT EXISTS (
      SELECT 1 FROM products p
      WHERE p.name = 'キャンプテーブル' AND p.category_id = c.id
  );

INSERT INTO products (name, price, stock, description, active, category_id, created_at, updated_at)
SELECT 'LEDキャンプランタン', 3980, 20, '夜間のキャンプサイトを明るく照らすLEDランタン。持ち運びしやすいコンパクト設計です。', true, c.id,
       CURRENT_TIMESTAMP - INTERVAL '26 minutes',
       CURRENT_TIMESTAMP - INTERVAL '26 minutes'
FROM categories c
WHERE c.name = 'Feature64検索テスト'
  AND NOT EXISTS (
      SELECT 1 FROM products p
      WHERE p.name = 'LEDキャンプランタン' AND p.category_id = c.id
  );

INSERT INTO products (name, price, stock, description, active, category_id, created_at, updated_at)
SELECT 'キャンプ用ケトル', 3480, 9, '焚き火やバーナーでお湯を沸かせるキャンプ用ケトル。コーヒータイムにも適しています。', true, c.id,
       CURRENT_TIMESTAMP - INTERVAL '25 minutes',
       CURRENT_TIMESTAMP - INTERVAL '25 minutes'
FROM categories c
WHERE c.name = 'Feature64検索テスト'
  AND NOT EXISTS (
      SELECT 1 FROM products p
      WHERE p.name = 'キャンプ用ケトル' AND p.category_id = c.id
  );

INSERT INTO products (name, price, stock, description, active, category_id, created_at, updated_at)
SELECT 'ソロテント', 14800, 6, '一人で使いやすい軽量ソロテント。コンパクトに収納でき、ツーリングや徒歩キャンプにも便利です。', true, c.id,
       CURRENT_TIMESTAMP - INTERVAL '24 minutes',
       CURRENT_TIMESTAMP - INTERVAL '24 minutes'
FROM categories c
WHERE c.name = 'Feature64検索テスト'
  AND NOT EXISTS (
      SELECT 1 FROM products p
      WHERE p.name = 'ソロテント' AND p.category_id = c.id
  );

INSERT INTO products (name, price, stock, description, active, category_id, created_at, updated_at)
SELECT 'キャンプマット', 4480, 14, '地面からの冷気と凹凸を和らげるキャンプマット。軽量で持ち運びしやすい仕様です。', true, c.id,
       CURRENT_TIMESTAMP - INTERVAL '23 minutes',
       CURRENT_TIMESTAMP - INTERVAL '23 minutes'
FROM categories c
WHERE c.name = 'Feature64検索テスト'
  AND NOT EXISTS (
      SELECT 1 FROM products p
      WHERE p.name = 'キャンプマット' AND p.category_id = c.id
  );

INSERT INTO products (name, price, stock, description, active, category_id, created_at, updated_at)
SELECT 'アウトドアコーヒーミル', 5280, 11, '屋外で挽きたてのコーヒーを楽しめる手動コーヒーミル。コンパクトで携帯に便利です。', true, c.id,
       CURRENT_TIMESTAMP - INTERVAL '22 minutes',
       CURRENT_TIMESTAMP - INTERVAL '22 minutes'
FROM categories c
WHERE c.name = 'Feature64検索テスト'
  AND NOT EXISTS (
      SELECT 1 FROM products p
      WHERE p.name = 'アウトドアコーヒーミル' AND p.category_id = c.id
  );

INSERT INTO products (name, price, stock, description, active, category_id, created_at, updated_at)
SELECT 'キャンプクッカーセット', 7980, 7, '鍋やフライパンをコンパクトに収納できるキャンプ用クッカーセット。ソロ調理にも対応します。', true, c.id,
       CURRENT_TIMESTAMP - INTERVAL '21 minutes',
       CURRENT_TIMESTAMP - INTERVAL '21 minutes'
FROM categories c
WHERE c.name = 'Feature64検索テスト'
  AND NOT EXISTS (
      SELECT 1 FROM products p
      WHERE p.name = 'キャンプクッカーセット' AND p.category_id = c.id
  );

INSERT INTO products (name, price, stock, description, active, category_id, created_at, updated_at)
SELECT '折りたたみウォータータンク', 2480, 18, '使わないときは小さく折りたためるウォータータンク。キャンプサイトでの水の運搬や保管に便利です。', true, c.id,
       CURRENT_TIMESTAMP - INTERVAL '20 minutes',
       CURRENT_TIMESTAMP - INTERVAL '20 minutes'
FROM categories c
WHERE c.name = 'Feature64検索テスト'
  AND NOT EXISTS (
      SELECT 1 FROM products p
      WHERE p.name = '折りたたみウォータータンク' AND p.category_id = c.id
  );

INSERT INTO products (name, price, stock, description, active, category_id, created_at, updated_at)
SELECT '軽量バックパック', 8980, 10, 'アウトドアや旅行で使いやすい軽量バックパック。荷物を整理しやすい収納を備えています。', true, c.id,
       CURRENT_TIMESTAMP - INTERVAL '19 minutes',
       CURRENT_TIMESTAMP - INTERVAL '19 minutes'
FROM categories c
WHERE c.name = 'Feature64検索テスト'
  AND NOT EXISTS (
      SELECT 1 FROM products p
      WHERE p.name = '軽量バックパック' AND p.category_id = c.id
  );

INSERT INTO products (name, price, stock, description, active, category_id, created_at, updated_at)
SELECT 'トレッキングポール', 6480, 5, '登山やハイキング時の歩行をサポートする軽量トレッキングポール。長さ調整が可能です。', true, c.id,
       CURRENT_TIMESTAMP - INTERVAL '18 minutes',
       CURRENT_TIMESTAMP - INTERVAL '18 minutes'
FROM categories c
WHERE c.name = 'Feature64検索テスト'
  AND NOT EXISTS (
      SELECT 1 FROM products p
      WHERE p.name = 'トレッキングポール' AND p.category_id = c.id
  );

INSERT INTO products (name, price, stock, description, active, category_id, created_at, updated_at)
SELECT 'アウトドアジャケット', 12800, 4, '風のある屋外でも使いやすいアウトドアジャケット。キャンプやハイキングに適した軽量ウェアです。', true, c.id,
       CURRENT_TIMESTAMP - INTERVAL '17 minutes',
       CURRENT_TIMESTAMP - INTERVAL '17 minutes'
FROM categories c
WHERE c.name = 'Feature64検索テスト'
  AND NOT EXISTS (
      SELECT 1 FROM products p
      WHERE p.name = 'アウトドアジャケット' AND p.category_id = c.id
  );

INSERT INTO products (name, price, stock, description, active, category_id, created_at, updated_at)
SELECT '防水スタッフバッグ', 2980, 16, '衣類や小物をまとめて収納できる防水スタッフバッグ。キャンプや旅行の荷物整理に便利です。', true, c.id,
       CURRENT_TIMESTAMP - INTERVAL '16 minutes',
       CURRENT_TIMESTAMP - INTERVAL '16 minutes'
FROM categories c
WHERE c.name = 'Feature64検索テスト'
  AND NOT EXISTS (
      SELECT 1 FROM products p
      WHERE p.name = '防水スタッフバッグ' AND p.category_id = c.id
  );

INSERT INTO products (name, price, stock, description, active, category_id, created_at, updated_at)
SELECT '軽量レインポンチョ', 4280, 0, '急な雨に備えられる軽量レインポンチョ。コンパクトに収納でき、旅行やアウトドアに便利です。', true, c.id,
       CURRENT_TIMESTAMP - INTERVAL '15 minutes',
       CURRENT_TIMESTAMP - INTERVAL '15 minutes'
FROM categories c
WHERE c.name = 'Feature64検索テスト'
  AND NOT EXISTS (
      SELECT 1 FROM products p
      WHERE p.name = '軽量レインポンチョ' AND p.category_id = c.id
  );

INSERT INTO products (name, price, stock, description, active, category_id, created_at, updated_at)
SELECT 'アウトドアグローブ', 3280, 13, 'キャンプ作業やアウトドアで手を保護するグローブ。扱いやすさと耐久性を両立しています。', true, c.id,
       CURRENT_TIMESTAMP - INTERVAL '14 minutes',
       CURRENT_TIMESTAMP - INTERVAL '14 minutes'
FROM categories c
WHERE c.name = 'Feature64検索テスト'
  AND NOT EXISTS (
      SELECT 1 FROM products p
      WHERE p.name = 'アウトドアグローブ' AND p.category_id = c.id
  );

INSERT INTO products (name, price, stock, description, active, category_id, created_at, updated_at)
SELECT '折りたたみスツール', 2980, 17, '短時間の休憩に便利なコンパクト折りたたみスツール。軽量で携帯しやすい設計です。', true, c.id,
       CURRENT_TIMESTAMP - INTERVAL '13 minutes',
       CURRENT_TIMESTAMP - INTERVAL '13 minutes'
FROM categories c
WHERE c.name = 'Feature64検索テスト'
  AND NOT EXISTS (
      SELECT 1 FROM products p
      WHERE p.name = '折りたたみスツール' AND p.category_id = c.id
  );

INSERT INTO products (name, price, stock, description, active, category_id, created_at, updated_at)
SELECT 'ヘッドライト', 3580, 19, '両手を空けたまま周囲を照らせるLEDヘッドライト。夜間のキャンプや作業に便利です。', true, c.id,
       CURRENT_TIMESTAMP - INTERVAL '12 minutes',
       CURRENT_TIMESTAMP - INTERVAL '12 minutes'
FROM categories c
WHERE c.name = 'Feature64検索テスト'
  AND NOT EXISTS (
      SELECT 1 FROM products p
      WHERE p.name = 'ヘッドライト' AND p.category_id = c.id
  );

INSERT INTO products (name, price, stock, description, active, category_id, created_at, updated_at)
SELECT '保温ボトル', 3980, 0, '飲み物の温度を保ちやすいステンレス製保温ボトル。アウトドアや旅行で使いやすいサイズです。', true, c.id,
       CURRENT_TIMESTAMP - INTERVAL '11 minutes',
       CURRENT_TIMESTAMP - INTERVAL '11 minutes'
FROM categories c
WHERE c.name = 'Feature64検索テスト'
  AND NOT EXISTS (
      SELECT 1 FROM products p
      WHERE p.name = '保温ボトル' AND p.category_id = c.id
  );

INSERT INTO products (name, price, stock, description, active, category_id, created_at, updated_at)
SELECT 'アウトドアブランケット', 5480, 8, '屋外での防寒や休憩に使えるアウトドアブランケット。持ち運びやすくキャンプにも便利です。', true, c.id,
       CURRENT_TIMESTAMP - INTERVAL '10 minutes',
       CURRENT_TIMESTAMP - INTERVAL '10 minutes'
FROM categories c
WHERE c.name = 'Feature64検索テスト'
  AND NOT EXISTS (
      SELECT 1 FROM products p
      WHERE p.name = 'アウトドアブランケット' AND p.category_id = c.id
  );

INSERT INTO products (name, price, stock, description, active, category_id, created_at, updated_at)
SELECT '軽量トラベルバッグ', 6980, 12, '旅行の荷物をまとめやすい軽量トラベルバッグ。肩掛けにも対応し持ち運びに便利です。', true, c.id,
       CURRENT_TIMESTAMP - INTERVAL '9 minutes',
       CURRENT_TIMESTAMP - INTERVAL '9 minutes'
FROM categories c
WHERE c.name = 'Feature64検索テスト'
  AND NOT EXISTS (
      SELECT 1 FROM products p
      WHERE p.name = '軽量トラベルバッグ' AND p.category_id = c.id
  );

INSERT INTO products (name, price, stock, description, active, category_id, created_at, updated_at)
SELECT 'トラベルポーチセット', 3980, 21, '衣類や小物を種類別に整理できるトラベルポーチセット。スーツケース内の整理に便利です。', true, c.id,
       CURRENT_TIMESTAMP - INTERVAL '8 minutes',
       CURRENT_TIMESTAMP - INTERVAL '8 minutes'
FROM categories c
WHERE c.name = 'Feature64検索テスト'
  AND NOT EXISTS (
      SELECT 1 FROM products p
      WHERE p.name = 'トラベルポーチセット' AND p.category_id = c.id
  );

INSERT INTO products (name, price, stock, description, active, category_id, created_at, updated_at)
SELECT '折りたたみ旅行バッグ', 4980, 9, '使用しないときはコンパクトに収納できる折りたたみ旅行バッグ。サブバッグとしても便利です。', true, c.id,
       CURRENT_TIMESTAMP - INTERVAL '7 minutes',
       CURRENT_TIMESTAMP - INTERVAL '7 minutes'
FROM categories c
WHERE c.name = 'Feature64検索テスト'
  AND NOT EXISTS (
      SELECT 1 FROM products p
      WHERE p.name = '折りたたみ旅行バッグ' AND p.category_id = c.id
  );

INSERT INTO products (name, price, stock, description, active, category_id, created_at, updated_at)
SELECT 'ネックピロー', 2980, 14, '移動中の首を支えるトラベル用ネックピロー。飛行機や長距離移動で快適に使えます。', true, c.id,
       CURRENT_TIMESTAMP - INTERVAL '6 minutes',
       CURRENT_TIMESTAMP - INTERVAL '6 minutes'
FROM categories c
WHERE c.name = 'Feature64検索テスト'
  AND NOT EXISTS (
      SELECT 1 FROM products p
      WHERE p.name = 'ネックピロー' AND p.category_id = c.id
  );

INSERT INTO products (name, price, stock, description, active, category_id, created_at, updated_at)
SELECT 'トラベルボトルセット', 1980, 25, 'シャンプーや化粧品を小分けして携帯できるトラベルボトルセット。旅行用ポーチ付きです。', true, c.id,
       CURRENT_TIMESTAMP - INTERVAL '5 minutes',
       CURRENT_TIMESTAMP - INTERVAL '5 minutes'
FROM categories c
WHERE c.name = 'Feature64検索テスト'
  AND NOT EXISTS (
      SELECT 1 FROM products p
      WHERE p.name = 'トラベルボトルセット' AND p.category_id = c.id
  );

INSERT INTO products (name, price, stock, description, active, category_id, created_at, updated_at)
SELECT 'パスポートケース', 3480, 7, 'パスポートやカード、搭乗券をまとめて収納できる旅行用ケース。海外旅行の貴重品整理に便利です。', true, c.id,
       CURRENT_TIMESTAMP - INTERVAL '4 minutes',
       CURRENT_TIMESTAMP - INTERVAL '4 minutes'
FROM categories c
WHERE c.name = 'Feature64検索テスト'
  AND NOT EXISTS (
      SELECT 1 FROM products p
      WHERE p.name = 'パスポートケース' AND p.category_id = c.id
  );

INSERT INTO products (name, price, stock, description, active, category_id, created_at, updated_at)
SELECT 'コンパクト折りたたみ傘', 3680, 0, '旅行にも持ち歩きやすいコンパクト折りたたみ傘。軽量で急な雨への備えに適しています。', true, c.id,
       CURRENT_TIMESTAMP - INTERVAL '3 minutes',
       CURRENT_TIMESTAMP - INTERVAL '3 minutes'
FROM categories c
WHERE c.name = 'Feature64検索テスト'
  AND NOT EXISTS (
      SELECT 1 FROM products p
      WHERE p.name = 'コンパクト折りたたみ傘' AND p.category_id = c.id
  );

INSERT INTO products (name, price, stock, description, active, category_id, created_at, updated_at)
SELECT 'トラベルサコッシュ', 4280, 18, 'パスポートやスマートフォンなどを身近に携帯できる薄型トラベルサコッシュです。', true, c.id,
       CURRENT_TIMESTAMP - INTERVAL '2 minutes',
       CURRENT_TIMESTAMP - INTERVAL '2 minutes'
FROM categories c
WHERE c.name = 'Feature64検索テスト'
  AND NOT EXISTS (
      SELECT 1 FROM products p
      WHERE p.name = 'トラベルサコッシュ' AND p.category_id = c.id
  );

INSERT INTO products (name, price, stock, description, active, category_id, created_at, updated_at)
SELECT 'USBトラベルアダプター', 5980, 11, '海外旅行で複数規格のコンセントに対応できるUSB付きトラベルアダプター。充電機器の携帯に便利です。', true, c.id,
       CURRENT_TIMESTAMP - INTERVAL '1 minutes',
       CURRENT_TIMESTAMP - INTERVAL '1 minutes'
FROM categories c
WHERE c.name = 'Feature64検索テスト'
  AND NOT EXISTS (
      SELECT 1 FROM products p
      WHERE p.name = 'USBトラベルアダプター' AND p.category_id = c.id
  );

INSERT INTO products (name, price, stock, description, active, category_id, created_at, updated_at)
SELECT '軽量キャリーケース', 16800, 6, '機内持ち込みを想定した軽量キャリーケース。旅行用品を整理しやすく移動にも便利です。', true, c.id,
       CURRENT_TIMESTAMP - INTERVAL '0 minutes',
       CURRENT_TIMESTAMP - INTERVAL '0 minutes'
FROM categories c
WHERE c.name = 'Feature64検索テスト'
  AND NOT EXISTS (
      SELECT 1 FROM products p
      WHERE p.name = '軽量キャリーケース' AND p.category_id = c.id
  );

COMMIT;
