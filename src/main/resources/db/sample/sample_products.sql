-- 開発用サンプル商品
-- Flyway管理対象外
-- 再実行可能
--
-- 前提:
--   sample_categories.sql を先に実行していること
--
-- 既存の同名サンプル商品は更新し、
-- 存在しない商品だけ追加する。
-- 商品IDは再実行しても維持される。

-- =========================================================
-- 既存サンプル商品の更新
-- =========================================================

UPDATE products AS p
SET
    price = v.price,
    stock = v.stock,
    description = v.description,
    active = v.active,
    category_id = c.id,
    image_path = v.image_path,
    updated_at = CURRENT_TIMESTAMP
FROM (
    VALUES

    -- パソコン・周辺機器
    ('サンプル ノートPC Air 13',             89800,  12, '軽量で持ち運びやすい13インチのノートパソコンです。',                 TRUE,  'パソコン・周辺機器',       'sample/pc-notebook-01.webp'),
    ('サンプル デスクトップPC Standard',    119800,   5, '日常作業から学習まで幅広く使えるデスクトップパソコンです。',           TRUE,  'パソコン・周辺機器',       'sample/pc-desktop-01.webp'),
    ('サンプル 24インチモニター',             24800,  18, 'フルHD表示に対応した24インチ液晶モニターです。',                     TRUE,  'パソコン・周辺機器',       'sample/pc-monitor-01.webp'),
    ('サンプル メカニカルキーボード',          9800,  25, '快適な打鍵感のUSBメカニカルキーボードです。',                       TRUE,  'パソコン・周辺機器',       'sample/pc-keyboard-01.webp'),
    ('サンプル ワイヤレスマウス',              3980,  40, '静音クリックに対応したワイヤレスマウスです。',                       TRUE,  'パソコン・周辺機器',       'sample/pc-mouse-01.webp'),
    ('サンプル USB-Cハブ 7ポート',             5980,  16, 'HDMIやUSBなどを増設できるUSB-C接続ハブです。',                       TRUE,  'パソコン・周辺機器',       'sample/pc-usb-hub-01.webp'),
    ('サンプル フルHD Webカメラ',              7480,   8, 'オンライン会議に適したフルHD対応Webカメラです。',                    TRUE,  'パソコン・周辺機器',       'sample/pc-webcam-01.webp'),
    ('サンプル 外付けSSD 1TB',                12800,   0, '高速データ転送に対応したポータブルSSDです。',                         TRUE,  'パソコン・周辺機器',       'sample/pc-ssd-01.webp'),
    ('サンプル Wi-Fi 6ルーター',               10800,   7, 'Wi-Fi 6に対応した家庭向け無線LANルーターです。',                     TRUE,  'パソコン・周辺機器',       'sample/pc-router-01.webp'),
    ('サンプル アルミPCスタンド',               4280,  20, 'ノートPCの高さと角度を調整できるアルミ製スタンドです。',              FALSE, 'パソコン・周辺機器',       'sample/pc-stand-01.webp'),

    -- スマートフォン・タブレット
    ('サンプル スマートフォン Lite 128GB',    49800,  10, '日常利用に十分な性能を備えた128GBスマートフォンです。',              TRUE,  'スマートフォン・タブレット', 'sample/mobile-smartphone-01.webp'),
    ('サンプル 10インチタブレット',            32800,   6, '動画視聴や電子書籍に使いやすい10インチタブレットです。',              TRUE,  'スマートフォン・タブレット', 'sample/mobile-tablet-01.webp'),
    ('サンプル モバイルバッテリー 10000mAh',    3980,  35, '外出先でスマートフォンを充電できる大容量モバイルバッテリーです。',      TRUE,  'スマートフォン・タブレット', 'sample/mobile-battery-01.webp'),
    ('サンプル USB-C急速充電器 45W',           4480,  22, 'USB Power Delivery対応のコンパクトな急速充電器です。',               TRUE,  'スマートフォン・タブレット', 'sample/mobile-charger-01.webp'),
    ('サンプル USB-Cケーブル 2m',              1480,  50, '充電とデータ通信に対応した2mのUSB-Cケーブルです。',                  TRUE,  'スマートフォン・タブレット', 'sample/mobile-cable-01.webp'),
    ('サンプル 折りたたみスマホスタンド',        1980,  28, '角度調整ができる折りたたみ式スマートフォンスタンドです。',            TRUE,  'スマートフォン・タブレット', 'sample/mobile-stand-01.webp'),
    ('サンプル ワイヤレス充電器',               3480,   0, '対応スマートフォンを置くだけで充電できるワイヤレス充電器です。',        TRUE,  'スマートフォン・タブレット', 'sample/mobile-wireless-charger-01.webp'),
    ('サンプル 完全ワイヤレスイヤホン',          7980,  14, '持ち運びに便利な充電ケース付きワイヤレスイヤホンです。',              TRUE,  'スマートフォン・タブレット', 'sample/mobile-earphones-01.webp'),
    ('サンプル タブレット用タッチペン',          2980,  17, 'メモや簡単なイラスト入力に使えるタッチペンです。',                   TRUE,  'スマートフォン・タブレット', 'sample/mobile-stylus-01.webp'),
    ('サンプル 10インチタブレットケース',        2680,   9, 'スタンド機能を備えた10インチタブレット用ケースです。',               FALSE, 'スマートフォン・タブレット', 'sample/mobile-tablet-case-01.webp'),

    -- 家電
    ('サンプル 電気ケトル 1.0L',               4980,  15, '必要な量のお湯を手軽に沸かせる1.0L電気ケトルです。',                 TRUE,  '家電',                   'sample/home-kettle-01.webp'),
    ('サンプル 2枚焼きトースター',              5980,  11, '食パンを2枚同時に焼けるシンプルなトースターです。',                   TRUE,  '家電',                   'sample/home-toaster-01.webp'),
    ('サンプル IH炊飯器 5.5合',               19800,   5, '毎日の炊飯に使いやすい5.5合対応IH炊飯器です。',                     TRUE,  '家電',                   'sample/home-rice-cooker-01.webp'),
    ('サンプル コードレス掃除機',              24800,   7, '軽量で取り回しやすいコードレススティック掃除機です。',                TRUE,  '家電',                   'sample/home-vacuum-01.webp'),
    ('サンプル 空気清浄機',                    29800,   4, 'リビングや寝室で使えるスタンダードな空気清浄機です。',                TRUE,  '家電',                   'sample/home-air-purifier-01.webp'),
    ('サンプル 超音波加湿器',                   6480,  13, '静音性に配慮したコンパクトな超音波式加湿器です。',                    TRUE,  '家電',                   'sample/home-humidifier-01.webp'),
    ('サンプル DCリビング扇風機',              12800,   0, '細かな風量調整に対応したDCモーター搭載扇風機です。',                  TRUE,  '家電',                   'sample/home-fan-01.webp'),
    ('サンプル マイナスイオンドライヤー',        8980,  18, '速乾性と使いやすさを重視したヘアドライヤーです。',                    TRUE,  '家電',                   'sample/home-dryer-01.webp'),
    ('サンプル スチームアイロン',               5980,   8, '衣類のしわ伸ばしに使えるスチーム対応アイロンです。',                  TRUE,  '家電',                   'sample/home-iron-01.webp'),
    ('サンプル ドリップコーヒーメーカー',        7980,   6, '自宅で手軽にドリップコーヒーを楽しめるコーヒーメーカーです。',          FALSE, '家電',                   'sample/home-coffee-maker-01.webp'),

    -- 文房具
    ('サンプル 油性ボールペン 3色',              480,  60, '黒・赤・青の3色を1本で使える油性ボールペンです。',                    TRUE,  '文房具',                 'sample/stationery-ballpoint-01.webp'),
    ('サンプル シャープペン 0.5mm',              680,  45, '書きやすさを重視した0.5mm芯対応シャープペンです。',                  TRUE,  '文房具',                 'sample/stationery-pencil-01.webp'),
    ('サンプル A5リングノート',                  580,  70, '持ち運びに便利なA5サイズのリングノートです。',                       TRUE,  '文房具',                 'sample/stationery-notebook-01.webp'),
    ('サンプル 方眼メモ帳',                      380,  55, 'アイデア整理や簡単な図を書くのに便利な方眼メモ帳です。',                TRUE,  '文房具',                 'sample/stationery-memo-01.webp'),
    ('サンプル コンパクトホッチキス',             780,  30, 'デスクでも収納しやすいコンパクトサイズのホッチキスです。',            TRUE,  '文房具',                 'sample/stationery-stapler-01.webp'),
    ('サンプル ステンレスはさみ',                 980,  26, '紙や包装材のカットに使えるステンレス製はさみです。',                  TRUE,  '文房具',                 'sample/stationery-scissors-01.webp'),
    ('サンプル アルミ定規 30cm',                 680,   0, '耐久性のあるアルミ製30cm定規です。',                                TRUE,  '文房具',                 'sample/stationery-ruler-01.webp'),
    ('サンプル カラー付箋セット',                 580,  42, '複数色を使い分けられる付箋セットです。',                             TRUE,  '文房具',                 'sample/stationery-sticky-notes-01.webp'),
    ('サンプル A4クリアファイル 10枚',            680,  38, '書類整理に便利なA4サイズのクリアファイルセットです。',                 TRUE,  '文房具',                 'sample/stationery-file-01.webp'),
    ('サンプル ファスナーペンケース',             1280,  19, '筆記具をまとめて収納できるシンプルなペンケースです。',                FALSE, '文房具',                 'sample/stationery-pencil-case-01.webp'),

    -- 書籍
    ('サンプル書籍 Java入門',                  2800,  20, 'Javaの基本文法からオブジェクト指向まで学べる入門書です。',             TRUE,  '書籍',                   'sample/book-java-01.webp'),
    ('サンプル書籍 Spring Boot入門',           3200,  14, 'Spring BootによるWebアプリケーション開発を学べる入門書です。',       TRUE,  '書籍',                   'sample/book-spring-boot-01.webp'),
    ('サンプル書籍 SQL入門',                   2600,  18, 'SQLの基本から検索・集計・結合まで学べる入門書です。',                 TRUE,  '書籍',                   'sample/book-sql-01.webp'),
    ('サンプル書籍 Git入門',                   2400,  16, 'Gitによるバージョン管理の基本操作を学べる入門書です。',               TRUE,  '書籍',                   'sample/book-git-01.webp'),
    ('サンプル書籍 Docker入門',                3000,   9, 'Dockerコンテナの基本と開発環境構築を学べる入門書です。',              TRUE,  '書籍',                   'sample/book-docker-01.webp'),
    ('サンプル書籍 Linux入門',                 2600,   0, 'Linuxの基本コマンドとシステム操作を学べる入門書です。',                TRUE,  '書籍',                   'sample/book-linux-01.webp'),
    ('サンプル書籍 HTML・CSS入門',             2500,  22, 'Webページ制作に必要なHTMLとCSSの基礎を学べる入門書です。',           TRUE,  '書籍',                   'sample/book-html-css-01.webp'),
    ('サンプル書籍 JavaScript入門',            2800,  12, 'JavaScriptによるWebプログラミングの基礎を学べる入門書です。',         TRUE,  '書籍',                   'sample/book-javascript-01.webp'),
    ('サンプル書籍 Webアプリ設計入門',          3400,   7, 'Webアプリケーションの設計基礎を体系的に学べる入門書です。',            TRUE,  '書籍',                   'sample/book-web-design-01.webp'),
    ('サンプル書籍 テスト自動化入門',            3200,   5, 'ユニットテストや自動テストの考え方を学べる入門書です。',               FALSE, '書籍',                   'sample/book-testing-01.webp')

) AS v (
    name,
    price,
    stock,
    description,
    active,
    category_name,
    image_path
)
JOIN categories AS c
    ON c.name = v.category_name
WHERE p.name = v.name;


-- =========================================================
-- 存在しないサンプル商品の追加
-- =========================================================

INSERT INTO products (
    name,
    price,
    stock,
    description,
    active,
    category_id,
    image_path
)
SELECT
    v.name,
    v.price,
    v.stock,
    v.description,
    v.active,
    c.id,
    v.image_path
FROM (
    VALUES

    -- パソコン・周辺機器
    ('サンプル ノートPC Air 13',             89800,  12, '軽量で持ち運びやすい13インチのノートパソコンです。',                 TRUE,  'パソコン・周辺機器',       'sample/pc-notebook-01.webp'),
    ('サンプル デスクトップPC Standard',    119800,   5, '日常作業から学習まで幅広く使えるデスクトップパソコンです。',           TRUE,  'パソコン・周辺機器',       'sample/pc-desktop-01.webp'),
    ('サンプル 24インチモニター',             24800,  18, 'フルHD表示に対応した24インチ液晶モニターです。',                     TRUE,  'パソコン・周辺機器',       'sample/pc-monitor-01.webp'),
    ('サンプル メカニカルキーボード',          9800,  25, '快適な打鍵感のUSBメカニカルキーボードです。',                       TRUE,  'パソコン・周辺機器',       'sample/pc-keyboard-01.webp'),
    ('サンプル ワイヤレスマウス',              3980,  40, '静音クリックに対応したワイヤレスマウスです。',                       TRUE,  'パソコン・周辺機器',       'sample/pc-mouse-01.webp'),
    ('サンプル USB-Cハブ 7ポート',             5980,  16, 'HDMIやUSBなどを増設できるUSB-C接続ハブです。',                       TRUE,  'パソコン・周辺機器',       'sample/pc-usb-hub-01.webp'),
    ('サンプル フルHD Webカメラ',              7480,   8, 'オンライン会議に適したフルHD対応Webカメラです。',                    TRUE,  'パソコン・周辺機器',       'sample/pc-webcam-01.webp'),
    ('サンプル 外付けSSD 1TB',                12800,   0, '高速データ転送に対応したポータブルSSDです。',                         TRUE,  'パソコン・周辺機器',       'sample/pc-ssd-01.webp'),
    ('サンプル Wi-Fi 6ルーター',               10800,   7, 'Wi-Fi 6に対応した家庭向け無線LANルーターです。',                     TRUE,  'パソコン・周辺機器',       'sample/pc-router-01.webp'),
    ('サンプル アルミPCスタンド',               4280,  20, 'ノートPCの高さと角度を調整できるアルミ製スタンドです。',              FALSE, 'パソコン・周辺機器',       'sample/pc-stand-01.webp'),

    -- スマートフォン・タブレット
    ('サンプル スマートフォン Lite 128GB',    49800,  10, '日常利用に十分な性能を備えた128GBスマートフォンです。',              TRUE,  'スマートフォン・タブレット', 'sample/mobile-smartphone-01.webp'),
    ('サンプル 10インチタブレット',            32800,   6, '動画視聴や電子書籍に使いやすい10インチタブレットです。',              TRUE,  'スマートフォン・タブレット', 'sample/mobile-tablet-01.webp'),
    ('サンプル モバイルバッテリー 10000mAh',    3980,  35, '外出先でスマートフォンを充電できる大容量モバイルバッテリーです。',      TRUE,  'スマートフォン・タブレット', 'sample/mobile-battery-01.webp'),
    ('サンプル USB-C急速充電器 45W',           4480,  22, 'USB Power Delivery対応のコンパクトな急速充電器です。',               TRUE,  'スマートフォン・タブレット', 'sample/mobile-charger-01.webp'),
    ('サンプル USB-Cケーブル 2m',              1480,  50, '充電とデータ通信に対応した2mのUSB-Cケーブルです。',                  TRUE,  'スマートフォン・タブレット', 'sample/mobile-cable-01.webp'),
    ('サンプル 折りたたみスマホスタンド',        1980,  28, '角度調整ができる折りたたみ式スマートフォンスタンドです。',            TRUE,  'スマートフォン・タブレット', 'sample/mobile-stand-01.webp'),
    ('サンプル ワイヤレス充電器',               3480,   0, '対応スマートフォンを置くだけで充電できるワイヤレス充電器です。',        TRUE,  'スマートフォン・タブレット', 'sample/mobile-wireless-charger-01.webp'),
    ('サンプル 完全ワイヤレスイヤホン',          7980,  14, '持ち運びに便利な充電ケース付きワイヤレスイヤホンです。',              TRUE,  'スマートフォン・タブレット', 'sample/mobile-earphones-01.webp'),
    ('サンプル タブレット用タッチペン',          2980,  17, 'メモや簡単なイラスト入力に使えるタッチペンです。',                   TRUE,  'スマートフォン・タブレット', 'sample/mobile-stylus-01.webp'),
    ('サンプル 10インチタブレットケース',        2680,   9, 'スタンド機能を備えた10インチタブレット用ケースです。',               FALSE, 'スマートフォン・タブレット', 'sample/mobile-tablet-case-01.webp'),

    -- 家電
    ('サンプル 電気ケトル 1.0L',               4980,  15, '必要な量のお湯を手軽に沸かせる1.0L電気ケトルです。',                 TRUE,  '家電',                   'sample/home-kettle-01.webp'),
    ('サンプル 2枚焼きトースター',              5980,  11, '食パンを2枚同時に焼けるシンプルなトースターです。',                   TRUE,  '家電',                   'sample/home-toaster-01.webp'),
    ('サンプル IH炊飯器 5.5合',               19800,   5, '毎日の炊飯に使いやすい5.5合対応IH炊飯器です。',                     TRUE,  '家電',                   'sample/home-rice-cooker-01.webp'),
    ('サンプル コードレス掃除機',              24800,   7, '軽量で取り回しやすいコードレススティック掃除機です。',                TRUE,  '家電',                   'sample/home-vacuum-01.webp'),
    ('サンプル 空気清浄機',                    29800,   4, 'リビングや寝室で使えるスタンダードな空気清浄機です。',                TRUE,  '家電',                   'sample/home-air-purifier-01.webp'),
    ('サンプル 超音波加湿器',                   6480,  13, '静音性に配慮したコンパクトな超音波式加湿器です。',                    TRUE,  '家電',                   'sample/home-humidifier-01.webp'),
    ('サンプル DCリビング扇風機',              12800,   0, '細かな風量調整に対応したDCモーター搭載扇風機です。',                  TRUE,  '家電',                   'sample/home-fan-01.webp'),
    ('サンプル マイナスイオンドライヤー',        8980,  18, '速乾性と使いやすさを重視したヘアドライヤーです。',                    TRUE,  '家電',                   'sample/home-dryer-01.webp'),
    ('サンプル スチームアイロン',               5980,   8, '衣類のしわ伸ばしに使えるスチーム対応アイロンです。',                  TRUE,  '家電',                   'sample/home-iron-01.webp'),
    ('サンプル ドリップコーヒーメーカー',        7980,   6, '自宅で手軽にドリップコーヒーを楽しめるコーヒーメーカーです。',          FALSE, '家電',                   'sample/home-coffee-maker-01.webp'),

    -- 文房具
    ('サンプル 油性ボールペン 3色',              480,  60, '黒・赤・青の3色を1本で使える油性ボールペンです。',                    TRUE,  '文房具',                 'sample/stationery-ballpoint-01.webp'),
    ('サンプル シャープペン 0.5mm',              680,  45, '書きやすさを重視した0.5mm芯対応シャープペンです。',                  TRUE,  '文房具',                 'sample/stationery-pencil-01.webp'),
    ('サンプル A5リングノート',                  580,  70, '持ち運びに便利なA5サイズのリングノートです。',                       TRUE,  '文房具',                 'sample/stationery-notebook-01.webp'),
    ('サンプル 方眼メモ帳',                      380,  55, 'アイデア整理や簡単な図を書くのに便利な方眼メモ帳です。',                TRUE,  '文房具',                 'sample/stationery-memo-01.webp'),
    ('サンプル コンパクトホッチキス',             780,  30, 'デスクでも収納しやすいコンパクトサイズのホッチキスです。',            TRUE,  '文房具',                 'sample/stationery-stapler-01.webp'),
    ('サンプル ステンレスはさみ',                 980,  26, '紙や包装材のカットに使えるステンレス製はさみです。',                  TRUE,  '文房具',                 'sample/stationery-scissors-01.webp'),
    ('サンプル アルミ定規 30cm',                 680,   0, '耐久性のあるアルミ製30cm定規です。',                                TRUE,  '文房具',                 'sample/stationery-ruler-01.webp'),
    ('サンプル カラー付箋セット',                 580,  42, '複数色を使い分けられる付箋セットです。',                             TRUE,  '文房具',                 'sample/stationery-sticky-notes-01.webp'),
    ('サンプル A4クリアファイル 10枚',            680,  38, '書類整理に便利なA4サイズのクリアファイルセットです。',                 TRUE,  '文房具',                 'sample/stationery-file-01.webp'),
    ('サンプル ファスナーペンケース',             1280,  19, '筆記具をまとめて収納できるシンプルなペンケースです。',                FALSE, '文房具',                 'sample/stationery-pencil-case-01.webp'),

    -- 書籍
    ('サンプル書籍 Java入門',                  2800,  20, 'Javaの基本文法からオブジェクト指向まで学べる入門書です。',             TRUE,  '書籍',                   'sample/book-java-01.webp'),
    ('サンプル書籍 Spring Boot入門',           3200,  14, 'Spring BootによるWebアプリケーション開発を学べる入門書です。',       TRUE,  '書籍',                   'sample/book-spring-boot-01.webp'),
    ('サンプル書籍 SQL入門',                   2600,  18, 'SQLの基本から検索・集計・結合まで学べる入門書です。',                 TRUE,  '書籍',                   'sample/book-sql-01.webp'),
    ('サンプル書籍 Git入門',                   2400,  16, 'Gitによるバージョン管理の基本操作を学べる入門書です。',               TRUE,  '書籍',                   'sample/book-git-01.webp'),
    ('サンプル書籍 Docker入門',                3000,   9, 'Dockerコンテナの基本と開発環境構築を学べる入門書です。',              TRUE,  '書籍',                   'sample/book-docker-01.webp'),
    ('サンプル書籍 Linux入門',                 2600,   0, 'Linuxの基本コマンドとシステム操作を学べる入門書です。',                TRUE,  '書籍',                   'sample/book-linux-01.webp'),
    ('サンプル書籍 HTML・CSS入門',             2500,  22, 'Webページ制作に必要なHTMLとCSSの基礎を学べる入門書です。',           TRUE,  '書籍',                   'sample/book-html-css-01.webp'),
    ('サンプル書籍 JavaScript入門',            2800,  12, 'JavaScriptによるWebプログラミングの基礎を学べる入門書です。',         TRUE,  '書籍',                   'sample/book-javascript-01.webp'),
    ('サンプル書籍 Webアプリ設計入門',          3400,   7, 'Webアプリケーションの設計基礎を体系的に学べる入門書です。',            TRUE,  '書籍',                   'sample/book-web-design-01.webp'),
    ('サンプル書籍 テスト自動化入門',            3200,   5, 'ユニットテストや自動テストの考え方を学べる入門書です。',               FALSE, '書籍',                   'sample/book-testing-01.webp')

) AS v (
    name,
    price,
    stock,
    description,
    active,
    category_name,
    image_path
)
JOIN categories AS c
    ON c.name = v.category_name
WHERE NOT EXISTS (
    SELECT 1
    FROM products AS p
    WHERE p.name = v.name
);
