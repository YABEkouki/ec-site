-- Feature64 browser test sample data
-- 30 products are intentionally assigned to ONE category for pagination testing.
-- This is sample/manual test data, NOT a Flyway migration.
-- PostgreSQL
BEGIN;
DELETE FROM product_search_keywords
WHERE product_id IN (
    SELECT p.id
    FROM products p
    JOIN categories c ON c.id = p.category_id
    WHERE c.name = 'Feature64検索テスト'
);

INSERT INTO product_search_keywords (product_id, keyword)
SELECT p.id, v.keyword
FROM products p
JOIN categories c ON c.id = p.category_id
CROSS JOIN (VALUES
        ('キャンプ'),
        ('チェア'),
        ('軽量'),
        ('折りたたみ'),
        ('ソロ')
) AS v(keyword)
WHERE c.name = 'Feature64検索テスト'
  AND p.name = '軽量キャンプチェア';

INSERT INTO product_search_keywords (product_id, keyword)
SELECT p.id, v.keyword
FROM products p
JOIN categories c ON c.id = p.category_id
CROSS JOIN (VALUES
        ('キャンプ'),
        ('焚き火'),
        ('コンパクト'),
        ('調理'),
        ('ソロ')
) AS v(keyword)
WHERE c.name = 'Feature64検索テスト'
  AND p.name = 'コンパクト焚き火台';

INSERT INTO product_search_keywords (product_id, keyword)
SELECT p.id, v.keyword
FROM products p
JOIN categories c ON c.id = p.category_id
CROSS JOIN (VALUES
        ('キャンプ'),
        ('テーブル'),
        ('軽量'),
        ('アウトドア'),
        ('折りたたみ')
) AS v(keyword)
WHERE c.name = 'Feature64検索テスト'
  AND p.name = 'キャンプテーブル';

INSERT INTO product_search_keywords (product_id, keyword)
SELECT p.id, v.keyword
FROM products p
JOIN categories c ON c.id = p.category_id
CROSS JOIN (VALUES
        ('キャンプ'),
        ('ランタン'),
        ('LED'),
        ('照明'),
        ('夜')
) AS v(keyword)
WHERE c.name = 'Feature64検索テスト'
  AND p.name = 'LEDキャンプランタン';

INSERT INTO product_search_keywords (product_id, keyword)
SELECT p.id, v.keyword
FROM products p
JOIN categories c ON c.id = p.category_id
CROSS JOIN (VALUES
        ('キャンプ'),
        ('ケトル'),
        ('コーヒー'),
        ('湯沸かし'),
        ('調理')
) AS v(keyword)
WHERE c.name = 'Feature64検索テスト'
  AND p.name = 'キャンプ用ケトル';

INSERT INTO product_search_keywords (product_id, keyword)
SELECT p.id, v.keyword
FROM products p
JOIN categories c ON c.id = p.category_id
CROSS JOIN (VALUES
        ('キャンプ'),
        ('テント'),
        ('ソロ'),
        ('軽量'),
        ('宿泊')
) AS v(keyword)
WHERE c.name = 'Feature64検索テスト'
  AND p.name = 'ソロテント';

INSERT INTO product_search_keywords (product_id, keyword)
SELECT p.id, v.keyword
FROM products p
JOIN categories c ON c.id = p.category_id
CROSS JOIN (VALUES
        ('キャンプ'),
        ('マット'),
        ('軽量'),
        ('寝具'),
        ('断熱')
) AS v(keyword)
WHERE c.name = 'Feature64検索テスト'
  AND p.name = 'キャンプマット';

INSERT INTO product_search_keywords (product_id, keyword)
SELECT p.id, v.keyword
FROM products p
JOIN categories c ON c.id = p.category_id
CROSS JOIN (VALUES
        ('アウトドア'),
        ('コーヒー'),
        ('ミル'),
        ('手動'),
        ('キャンプ')
) AS v(keyword)
WHERE c.name = 'Feature64検索テスト'
  AND p.name = 'アウトドアコーヒーミル';

INSERT INTO product_search_keywords (product_id, keyword)
SELECT p.id, v.keyword
FROM products p
JOIN categories c ON c.id = p.category_id
CROSS JOIN (VALUES
        ('キャンプ'),
        ('クッカー'),
        ('調理'),
        ('鍋'),
        ('ソロ')
) AS v(keyword)
WHERE c.name = 'Feature64検索テスト'
  AND p.name = 'キャンプクッカーセット';

INSERT INTO product_search_keywords (product_id, keyword)
SELECT p.id, v.keyword
FROM products p
JOIN categories c ON c.id = p.category_id
CROSS JOIN (VALUES
        ('キャンプ'),
        ('ウォータータンク'),
        ('水'),
        ('折りたたみ'),
        ('アウトドア')
) AS v(keyword)
WHERE c.name = 'Feature64検索テスト'
  AND p.name = '折りたたみウォータータンク';

INSERT INTO product_search_keywords (product_id, keyword)
SELECT p.id, v.keyword
FROM products p
JOIN categories c ON c.id = p.category_id
CROSS JOIN (VALUES
        ('軽量'),
        ('バックパック'),
        ('旅行'),
        ('アウトドア'),
        ('収納')
) AS v(keyword)
WHERE c.name = 'Feature64検索テスト'
  AND p.name = '軽量バックパック';

INSERT INTO product_search_keywords (product_id, keyword)
SELECT p.id, v.keyword
FROM products p
JOIN categories c ON c.id = p.category_id
CROSS JOIN (VALUES
        ('登山'),
        ('トレッキング'),
        ('ポール'),
        ('軽量'),
        ('ハイキング')
) AS v(keyword)
WHERE c.name = 'Feature64検索テスト'
  AND p.name = 'トレッキングポール';

INSERT INTO product_search_keywords (product_id, keyword)
SELECT p.id, v.keyword
FROM products p
JOIN categories c ON c.id = p.category_id
CROSS JOIN (VALUES
        ('アウトドア'),
        ('ジャケット'),
        ('軽量'),
        ('キャンプ'),
        ('ハイキング')
) AS v(keyword)
WHERE c.name = 'Feature64検索テスト'
  AND p.name = 'アウトドアジャケット';

INSERT INTO product_search_keywords (product_id, keyword)
SELECT p.id, v.keyword
FROM products p
JOIN categories c ON c.id = p.category_id
CROSS JOIN (VALUES
        ('防水'),
        ('スタッフバッグ'),
        ('収納'),
        ('旅行'),
        ('キャンプ')
) AS v(keyword)
WHERE c.name = 'Feature64検索テスト'
  AND p.name = '防水スタッフバッグ';

INSERT INTO product_search_keywords (product_id, keyword)
SELECT p.id, v.keyword
FROM products p
JOIN categories c ON c.id = p.category_id
CROSS JOIN (VALUES
        ('軽量'),
        ('レイン'),
        ('ポンチョ'),
        ('雨'),
        ('防水')
) AS v(keyword)
WHERE c.name = 'Feature64検索テスト'
  AND p.name = '軽量レインポンチョ';

INSERT INTO product_search_keywords (product_id, keyword)
SELECT p.id, v.keyword
FROM products p
JOIN categories c ON c.id = p.category_id
CROSS JOIN (VALUES
        ('アウトドア'),
        ('グローブ'),
        ('キャンプ'),
        ('手袋'),
        ('作業')
) AS v(keyword)
WHERE c.name = 'Feature64検索テスト'
  AND p.name = 'アウトドアグローブ';

INSERT INTO product_search_keywords (product_id, keyword)
SELECT p.id, v.keyword
FROM products p
JOIN categories c ON c.id = p.category_id
CROSS JOIN (VALUES
        ('折りたたみ'),
        ('スツール'),
        ('軽量'),
        ('キャンプ'),
        ('椅子')
) AS v(keyword)
WHERE c.name = 'Feature64検索テスト'
  AND p.name = '折りたたみスツール';

INSERT INTO product_search_keywords (product_id, keyword)
SELECT p.id, v.keyword
FROM products p
JOIN categories c ON c.id = p.category_id
CROSS JOIN (VALUES
        ('ヘッドライト'),
        ('LED'),
        ('照明'),
        ('キャンプ'),
        ('夜')
) AS v(keyword)
WHERE c.name = 'Feature64検索テスト'
  AND p.name = 'ヘッドライト';

INSERT INTO product_search_keywords (product_id, keyword)
SELECT p.id, v.keyword
FROM products p
JOIN categories c ON c.id = p.category_id
CROSS JOIN (VALUES
        ('保温'),
        ('ボトル'),
        ('ステンレス'),
        ('旅行'),
        ('アウトドア')
) AS v(keyword)
WHERE c.name = 'Feature64検索テスト'
  AND p.name = '保温ボトル';

INSERT INTO product_search_keywords (product_id, keyword)
SELECT p.id, v.keyword
FROM products p
JOIN categories c ON c.id = p.category_id
CROSS JOIN (VALUES
        ('アウトドア'),
        ('ブランケット'),
        ('防寒'),
        ('キャンプ'),
        ('毛布')
) AS v(keyword)
WHERE c.name = 'Feature64検索テスト'
  AND p.name = 'アウトドアブランケット';

INSERT INTO product_search_keywords (product_id, keyword)
SELECT p.id, v.keyword
FROM products p
JOIN categories c ON c.id = p.category_id
CROSS JOIN (VALUES
        ('軽量'),
        ('トラベル'),
        ('バッグ'),
        ('旅行'),
        ('収納')
) AS v(keyword)
WHERE c.name = 'Feature64検索テスト'
  AND p.name = '軽量トラベルバッグ';

INSERT INTO product_search_keywords (product_id, keyword)
SELECT p.id, v.keyword
FROM products p
JOIN categories c ON c.id = p.category_id
CROSS JOIN (VALUES
        ('トラベル'),
        ('ポーチ'),
        ('旅行'),
        ('収納'),
        ('整理')
) AS v(keyword)
WHERE c.name = 'Feature64検索テスト'
  AND p.name = 'トラベルポーチセット';

INSERT INTO product_search_keywords (product_id, keyword)
SELECT p.id, v.keyword
FROM products p
JOIN categories c ON c.id = p.category_id
CROSS JOIN (VALUES
        ('旅行'),
        ('バッグ'),
        ('折りたたみ'),
        ('軽量'),
        ('サブバッグ')
) AS v(keyword)
WHERE c.name = 'Feature64検索テスト'
  AND p.name = '折りたたみ旅行バッグ';

INSERT INTO product_search_keywords (product_id, keyword)
SELECT p.id, v.keyword
FROM products p
JOIN categories c ON c.id = p.category_id
CROSS JOIN (VALUES
        ('旅行'),
        ('ネックピロー'),
        ('飛行機'),
        ('快適'),
        ('首')
) AS v(keyword)
WHERE c.name = 'Feature64検索テスト'
  AND p.name = 'ネックピロー';

INSERT INTO product_search_keywords (product_id, keyword)
SELECT p.id, v.keyword
FROM products p
JOIN categories c ON c.id = p.category_id
CROSS JOIN (VALUES
        ('旅行'),
        ('ボトル'),
        ('化粧品'),
        ('小分け'),
        ('トラベル')
) AS v(keyword)
WHERE c.name = 'Feature64検索テスト'
  AND p.name = 'トラベルボトルセット';

INSERT INTO product_search_keywords (product_id, keyword)
SELECT p.id, v.keyword
FROM products p
JOIN categories c ON c.id = p.category_id
CROSS JOIN (VALUES
        ('旅行'),
        ('パスポート'),
        ('ケース'),
        ('海外'),
        ('収納')
) AS v(keyword)
WHERE c.name = 'Feature64検索テスト'
  AND p.name = 'パスポートケース';

INSERT INTO product_search_keywords (product_id, keyword)
SELECT p.id, v.keyword
FROM products p
JOIN categories c ON c.id = p.category_id
CROSS JOIN (VALUES
        ('旅行'),
        ('傘'),
        ('折りたたみ'),
        ('軽量'),
        ('雨')
) AS v(keyword)
WHERE c.name = 'Feature64検索テスト'
  AND p.name = 'コンパクト折りたたみ傘';

INSERT INTO product_search_keywords (product_id, keyword)
SELECT p.id, v.keyword
FROM products p
JOIN categories c ON c.id = p.category_id
CROSS JOIN (VALUES
        ('旅行'),
        ('サコッシュ'),
        ('軽量'),
        ('パスポート'),
        ('収納')
) AS v(keyword)
WHERE c.name = 'Feature64検索テスト'
  AND p.name = 'トラベルサコッシュ';

INSERT INTO product_search_keywords (product_id, keyword)
SELECT p.id, v.keyword
FROM products p
JOIN categories c ON c.id = p.category_id
CROSS JOIN (VALUES
        ('旅行'),
        ('USB'),
        ('アダプター'),
        ('海外'),
        ('充電')
) AS v(keyword)
WHERE c.name = 'Feature64検索テスト'
  AND p.name = 'USBトラベルアダプター';

INSERT INTO product_search_keywords (product_id, keyword)
SELECT p.id, v.keyword
FROM products p
JOIN categories c ON c.id = p.category_id
CROSS JOIN (VALUES
        ('旅行'),
        ('キャリーケース'),
        ('軽量'),
        ('スーツケース'),
        ('機内持ち込み')
) AS v(keyword)
WHERE c.name = 'Feature64検索テスト'
  AND p.name = '軽量キャリーケース';

COMMIT;
