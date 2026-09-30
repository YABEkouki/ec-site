-- ECサイト 標準開発データ（前半）
-- 前提: Flyway V1-V42、sample_categories.sql、sample_products.sql 適用済み
-- 開発環境専用 / 共通パスワード: password
BEGIN;

INSERT INTO admin_accounts (username,password,enabled,created_at,updated_at,previous_login_at,last_login_at) VALUES
('admin01','$2a$10$iw4sUKXXU2j4OWYKX1EO5empKbfr9K2rbrF9b53/VfaAFN72.QdZy',TRUE,'2026-01-10 09:00','2026-09-28 09:00','2026-09-27 09:10','2026-09-28 09:05'),
('admin02','$2a$10$iw4sUKXXU2j4OWYKX1EO5empKbfr9K2rbrF9b53/VfaAFN72.QdZy',TRUE,'2026-02-01 09:00','2026-09-27 13:00','2026-09-26 13:10','2026-09-27 13:02'),
('admin03','$2a$10$iw4sUKXXU2j4OWYKX1EO5empKbfr9K2rbrF9b53/VfaAFN72.QdZy',TRUE,'2026-03-01 09:00','2026-09-26 16:00',NULL,'2026-09-26 16:00');

INSERT INTO users (username,password,enabled,created_at,updated_at,previous_login_at,last_login_at,email,email_verified_at) VALUES
('user01','$2a$10$iw4sUKXXU2j4OWYKX1EO5empKbfr9K2rbrF9b53/VfaAFN72.QdZy',TRUE,'2026-01-15 10:00','2026-09-28 08:30','2026-09-25 20:10','2026-09-28 08:30','user01@example.com','2026-01-15 10:30'),
('user02','$2a$10$iw4sUKXXU2j4OWYKX1EO5empKbfr9K2rbrF9b53/VfaAFN72.QdZy',TRUE,'2026-02-01 11:00','2026-09-27 19:00','2026-09-26 18:30','2026-09-27 19:00','user02@example.com','2026-02-01 11:20'),
('user03','$2a$10$iw4sUKXXU2j4OWYKX1EO5empKbfr9K2rbrF9b53/VfaAFN72.QdZy',TRUE,'2026-03-05 12:00','2026-09-20 12:00',NULL,'2026-09-20 12:00','user03@example.com',NULL),
('user04','$2a$10$iw4sUKXXU2j4OWYKX1EO5empKbfr9K2rbrF9b53/VfaAFN72.QdZy',TRUE,'2026-03-20 09:30','2026-09-18 21:00','2026-09-10 20:00','2026-09-18 21:00',NULL,NULL),
('user05','$2a$10$iw4sUKXXU2j4OWYKX1EO5empKbfr9K2rbrF9b53/VfaAFN72.QdZy',FALSE,'2026-04-01 14:00','2026-09-15 10:00','2026-09-12 09:00','2026-09-14 09:00','user05@example.com','2026-04-01 14:15'),
('user06','$2a$10$iw4sUKXXU2j4OWYKX1EO5empKbfr9K2rbrF9b53/VfaAFN72.QdZy',TRUE,'2026-04-18 08:00','2026-09-28 07:30','2026-09-24 07:30','2026-09-28 07:30','user06@example.com','2026-04-18 08:10'),
('user07','$2a$10$iw4sUKXXU2j4OWYKX1EO5empKbfr9K2rbrF9b53/VfaAFN72.QdZy',TRUE,'2026-05-03 13:00','2026-09-26 15:00','2026-09-21 14:30','2026-09-26 15:00','user07@example.com','2026-05-03 13:10'),
('user08','$2a$10$iw4sUKXXU2j4OWYKX1EO5empKbfr9K2rbrF9b53/VfaAFN72.QdZy',TRUE,'2026-05-20 16:00','2026-09-27 17:00','2026-09-23 17:30','2026-09-27 17:00','user08@example.com','2026-05-20 16:20'),
('user09','$2a$10$iw4sUKXXU2j4OWYKX1EO5empKbfr9K2rbrF9b53/VfaAFN72.QdZy',TRUE,'2026-06-10 10:00','2026-09-10 10:00',NULL,'2026-09-10 10:00','user09@example.com','2026-06-10 10:10'),
('user10','$2a$10$iw4sUKXXU2j4OWYKX1EO5empKbfr9K2rbrF9b53/VfaAFN72.QdZy',TRUE,'2026-06-25 09:00','2026-09-28 11:00','2026-09-27 11:00','2026-09-28 11:00','user10@example.com','2026-06-25 09:10'),
('user11','$2a$10$iw4sUKXXU2j4OWYKX1EO5empKbfr9K2rbrF9b53/VfaAFN72.QdZy',TRUE,'2026-07-01 09:00','2026-09-20 09:00',NULL,'2026-09-20 09:00','user11@example.com','2026-07-01 09:10'),
('user12','$2a$10$iw4sUKXXU2j4OWYKX1EO5empKbfr9K2rbrF9b53/VfaAFN72.QdZy',TRUE,'2026-07-05 10:00','2026-09-21 10:00',NULL,'2026-09-21 10:00','user12@example.com','2026-07-05 10:10'),
('user13','$2a$10$iw4sUKXXU2j4OWYKX1EO5empKbfr9K2rbrF9b53/VfaAFN72.QdZy',TRUE,'2026-07-10 11:00','2026-09-22 11:00',NULL,'2026-09-22 11:00','user13@example.com','2026-07-10 11:10'),
('user14','$2a$10$iw4sUKXXU2j4OWYKX1EO5empKbfr9K2rbrF9b53/VfaAFN72.QdZy',TRUE,'2026-07-15 12:00','2026-09-23 12:00',NULL,'2026-09-23 12:00','user14@example.com','2026-07-15 12:10'),
('user15','$2a$10$iw4sUKXXU2j4OWYKX1EO5empKbfr9K2rbrF9b53/VfaAFN72.QdZy',TRUE,'2026-07-20 13:00','2026-09-24 13:00',NULL,'2026-09-24 13:00','user15@example.com','2026-07-20 13:10'),
('user16','$2a$10$iw4sUKXXU2j4OWYKX1EO5empKbfr9K2rbrF9b53/VfaAFN72.QdZy',TRUE,'2026-08-01 09:00','2026-09-25 09:00',NULL,'2026-09-25 09:00','user16@example.com','2026-08-01 09:10'),
('user17','$2a$10$iw4sUKXXU2j4OWYKX1EO5empKbfr9K2rbrF9b53/VfaAFN72.QdZy',TRUE,'2026-08-05 10:00','2026-09-26 10:00',NULL,'2026-09-26 10:00','user17@example.com','2026-08-05 10:10'),
('user18','$2a$10$iw4sUKXXU2j4OWYKX1EO5empKbfr9K2rbrF9b53/VfaAFN72.QdZy',TRUE,'2026-08-10 11:00','2026-09-27 11:00',NULL,'2026-09-27 11:00','user18@example.com','2026-08-10 11:10'),
('user19','$2a$10$iw4sUKXXU2j4OWYKX1EO5empKbfr9K2rbrF9b53/VfaAFN72.QdZy',TRUE,'2026-08-15 12:00','2026-09-28 12:00',NULL,'2026-09-28 12:00','user19@example.com','2026-08-15 12:10'),
('user20','$2a$10$iw4sUKXXU2j4OWYKX1EO5empKbfr9K2rbrF9b53/VfaAFN72.QdZy',TRUE,'2026-08-20 13:00','2026-09-28 13:00',NULL,'2026-09-28 13:00','user20@example.com','2026-08-20 13:10'),
('user21','$2a$10$iw4sUKXXU2j4OWYKX1EO5empKbfr9K2rbrF9b53/VfaAFN72.QdZy',TRUE,'2026-09-01 09:00','2026-09-29 09:00',NULL,'2026-09-29 09:00','user21@example.com','2026-09-01 09:10');

INSERT INTO user_profiles (user_id,name,postal_code,prefecture,city,address_line,phone,created_at,updated_at)
SELECT u.id,v.name,v.postal,v.pref,v.city,v.addr,v.phone,v.ca,v.ua FROM (VALUES
('user01','山田 太郎','060-0001','北海道','札幌市中央区','北一条西1-1-1','011-000-0001','2026-01-15 10:05'::timestamp,'2026-01-15 10:05'::timestamp),
('user02','佐藤 花子','980-0811','宮城県','仙台市青葉区','一番町1-2-3','022-000-0002','2026-02-01 11:05'::timestamp,'2026-02-01 11:05'::timestamp),
('user03','鈴木 一郎','100-0005','東京都','千代田区','丸の内1-1-1','03-0000-0003','2026-03-05 12:05'::timestamp,'2026-03-05 12:05'::timestamp),
('user04','高橋 美咲','220-0012','神奈川県','横浜市西区','みなとみらい2-2-1','045-000-0004','2026-03-20 09:35'::timestamp,'2026-03-20 09:35'::timestamp),
('user05','田中 健','920-0961','石川県','金沢市','香林坊1-1-1','076-000-0005','2026-04-01 14:05'::timestamp,'2026-04-01 14:05'::timestamp),
('user06','伊藤 葵','460-0008','愛知県','名古屋市中区','栄3-3-3','052-000-0006','2026-04-18 08:05'::timestamp,'2026-04-18 08:05'::timestamp),
('user07','渡辺 翔','530-0001','大阪府','大阪市北区','梅田1-1-1','06-0000-0007','2026-05-03 13:05'::timestamp,'2026-05-03 13:05'::timestamp),
('user08','中村 結衣','730-0011','広島県','広島市中区','基町1-1','082-000-0008','2026-05-20 16:05'::timestamp,'2026-05-20 16:05'::timestamp),
('user09','小林 蓮','810-0001','福岡県','福岡市中央区','天神1-1-1','092-000-0009','2026-06-10 10:05'::timestamp,'2026-06-10 10:05'::timestamp),
('user10','加藤 陽子','150-0002','東京都','渋谷区','渋谷2-2-2','03-0000-0010','2026-06-25 09:05'::timestamp,'2026-06-25 09:05'::timestamp),
('user11','吉田 大輔','950-0086','新潟県','新潟市中央区','花園1-1-1','025-000-0011','2026-07-01 09:05'::timestamp,'2026-07-01 09:05'::timestamp),
('user12','山本 彩','420-0851','静岡県','静岡市葵区','黒金町1-1','054-000-0012','2026-07-05 10:05'::timestamp,'2026-07-05 10:05'::timestamp),
('user13','松本 拓海','380-0823','長野県','長野市','南千歳1-1-1','026-000-0013','2026-07-10 11:05'::timestamp,'2026-07-10 11:05'::timestamp),
('user14','井上 由美','600-8216','京都府','京都市下京区','東塩小路町1-1','075-000-0014','2026-07-15 12:05'::timestamp,'2026-07-15 12:05'::timestamp),
('user15','木村 健太','650-0021','兵庫県','神戸市中央区','三宮町1-1-1','078-000-0015','2026-07-20 13:05'::timestamp,'2026-07-20 13:05'::timestamp),
('user16','林 美月','700-0901','岡山県','岡山市北区','本町1-1','086-000-0016','2026-08-01 09:05'::timestamp,'2026-08-01 09:05'::timestamp),
('user17','清水 翼','760-0011','香川県','高松市','浜ノ町1-1','087-000-0017','2026-08-05 10:05'::timestamp,'2026-08-05 10:05'::timestamp),
('user18','山崎 愛','790-0001','愛媛県','松山市','一番町1-1-1','089-000-0018','2026-08-10 11:05'::timestamp,'2026-08-10 11:05'::timestamp),
('user19','森 大和','860-0808','熊本県','熊本市中央区','手取本町1-1','096-000-0019','2026-08-15 12:05'::timestamp,'2026-08-15 12:05'::timestamp),
('user20','池田 奈々','890-0053','鹿児島県','鹿児島市','中央町1-1','099-000-0020','2026-08-20 13:05'::timestamp,'2026-08-20 13:05'::timestamp),
('user21','橋本 悠斗','900-0015','沖縄県','那覇市','久茂地1-1-1','098-000-0021','2026-09-01 09:05'::timestamp,'2026-09-01 09:05'::timestamp)
) v(username,name,postal,pref,city,addr,phone,ca,ua) JOIN users u ON u.username=v.username;

INSERT INTO shipping_addresses (user_id,name,recipient_name,postal_code,prefecture,city,address_line,phone,is_default,created_at,updated_at)
SELECT u.id,v.label,v.recipient,v.postal,v.pref,v.city,v.addr,v.phone,v.def,v.ca,v.ca FROM (VALUES
('user01','自宅','山田 太郎','060-0001','北海道','札幌市中央区','北一条西1-1-1','011-000-0001',TRUE,'2026-01-15 10:10'::timestamp),
('user02','自宅','佐藤 花子','980-0811','宮城県','仙台市青葉区','一番町1-2-3','022-000-0002',TRUE,'2026-02-01 11:10'::timestamp),
('user03','自宅','鈴木 一郎','100-0005','東京都','千代田区','丸の内1-1-1','03-0000-0003',TRUE,'2026-03-05 12:10'::timestamp),
('user04','自宅','高橋 美咲','220-0012','神奈川県','横浜市西区','みなとみらい2-2-1','045-000-0004',TRUE,'2026-03-20 09:40'::timestamp),
('user05','自宅','田中 健','920-0961','石川県','金沢市','香林坊1-1-1','076-000-0005',TRUE,'2026-04-01 14:10'::timestamp),
('user06','自宅','伊藤 葵','460-0008','愛知県','名古屋市中区','栄3-3-3','052-000-0006',TRUE,'2026-04-18 08:10'::timestamp),
('user06','勤務先','伊藤 葵','450-0002','愛知県','名古屋市中村区','名駅4-4-4 サンプルビル5F','052-000-0066',FALSE,'2026-04-18 08:15'::timestamp),
('user07','自宅','渡辺 翔','530-0001','大阪府','大阪市北区','梅田1-1-1','06-0000-0007',TRUE,'2026-05-03 13:10'::timestamp),
('user08','自宅','中村 結衣','730-0011','広島県','広島市中区','基町1-1','082-000-0008',TRUE,'2026-05-20 16:10'::timestamp),
('user09','自宅','小林 蓮','810-0001','福岡県','福岡市中央区','天神1-1-1','092-000-0009',TRUE,'2026-06-10 10:10'::timestamp),
('user10','自宅','加藤 陽子','150-0002','東京都','渋谷区','渋谷2-2-2','03-0000-0010',TRUE,'2026-06-25 09:10'::timestamp),
('user11','自宅','吉田 大輔','950-0086','新潟県','新潟市中央区','花園1-1-1','025-000-0011',TRUE,'2026-07-01 09:10'::timestamp),
('user12','自宅','山本 彩','420-0851','静岡県','静岡市葵区','黒金町1-1','054-000-0012',TRUE,'2026-07-05 10:10'::timestamp),
('user13','自宅','松本 拓海','380-0823','長野県','長野市','南千歳1-1-1','026-000-0013',TRUE,'2026-07-10 11:10'::timestamp),
('user14','自宅','井上 由美','600-8216','京都府','京都市下京区','東塩小路町1-1','075-000-0014',TRUE,'2026-07-15 12:10'::timestamp),
('user15','自宅','木村 健太','650-0021','兵庫県','神戸市中央区','三宮町1-1-1','078-000-0015',TRUE,'2026-07-20 13:10'::timestamp),
('user16','自宅','林 美月','700-0901','岡山県','岡山市北区','本町1-1','086-000-0016',TRUE,'2026-08-01 09:10'::timestamp),
('user17','自宅','清水 翼','760-0011','香川県','高松市','浜ノ町1-1','087-000-0017',TRUE,'2026-08-05 10:10'::timestamp),
('user18','自宅','山崎 愛','790-0001','愛媛県','松山市','一番町1-1-1','089-000-0018',TRUE,'2026-08-10 11:10'::timestamp),
('user19','自宅','森 大和','860-0808','熊本県','熊本市中央区','手取本町1-1','096-000-0019',TRUE,'2026-08-15 12:10'::timestamp),
('user20','自宅','池田 奈々','890-0053','鹿児島県','鹿児島市','中央町1-1','099-000-0020',TRUE,'2026-08-20 13:10'::timestamp),
('user21','自宅','橋本 悠斗','900-0015','沖縄県','那覇市','久茂地1-1-1','098-000-0021',TRUE,'2026-09-01 09:10'::timestamp)
) v(username,label,recipient,postal,pref,city,addr,phone,def,ca) JOIN users u ON u.username=v.username;

-- 5. お気に入り・レビュー・閲覧履歴
INSERT INTO favorites(user_id,product_id,created_at)
SELECT u.id,p.id,v.at FROM (VALUES
('user01','サンプル 電気ケトル 1.0L','2026-09-20 10:00'::timestamp),
('user01','サンプル レギュラーコーヒー豆 200g','2026-09-21 10:00'::timestamp),
('user06','サンプル書籍 Spring Boot入門','2026-09-22 10:00'::timestamp),
('user07','サンプル ドリップコーヒー 5袋','2026-09-23 10:00'::timestamp),
('user08','サンプル クッキー','2026-09-24 10:00'::timestamp)
) v(username,pname,at) JOIN users u ON u.username=v.username JOIN products p ON p.name=v.pname;

INSERT INTO reviews(product_id,user_id,rating,comment,created_at,updated_at)
SELECT p.id,u.id,v.rating,v.comment,v.at,v.at FROM (VALUES
('user01','サンプル 電気ケトル 1.0L',5,'使いやすく、すぐにお湯が沸きます。','2026-09-10 10:00'::timestamp),
('user02','サンプル書籍 Java入門',4,'基礎を復習するのにちょうどよい内容でした。','2026-09-11 10:00'::timestamp),
('user07','サンプル レギュラーコーヒー豆 200g',5,'香りがよく飲みやすいコーヒーでした。','2026-09-12 10:00'::timestamp),
('user08','サンプル クッキー',4,'コーヒーと一緒に楽しめました。','2026-09-13 10:00'::timestamp)
) v(username,pname,rating,comment,at) JOIN users u ON u.username=v.username JOIN products p ON p.name=v.pname;

INSERT INTO product_view_histories(user_id,product_id,last_viewed_at)
SELECT u.id,p.id,v.at FROM (VALUES
('user01','サンプル 電気ケトル 1.0L','2026-09-29 08:00'::timestamp),
('user02','サンプル書籍 Java入門','2026-09-28 19:00'::timestamp),
('user06','サンプル書籍 Spring Boot入門','2026-09-28 20:00'::timestamp),
('user07','サンプル ドリップコーヒー 5袋','2026-09-28 21:00'::timestamp),
('user08','サンプル クッキー','2026-09-28 22:00'::timestamp)
) v(username,pname,at) JOIN users u ON u.username=v.username JOIN products p ON p.name=v.pname;

-- 6. 注文
INSERT INTO orders(id,user_id,total_amount,status,ordered_at,shipping_name,shipping_postal_code,shipping_prefecture,shipping_city,shipping_address_line,shipping_phone,handling_status,assigned_admin_account_id,change_deadline_at,item_subtotal,charge_total,tax_amount)
SELECT 1001,u.id,5530,'ORDERED','2026-09-29 10:00',a.recipient_name,a.postal_code,a.prefecture,a.city,a.address_line,a.phone,'NONE',NULL,'2026-09-29 14:00:00',4980,550,502
FROM users u JOIN shipping_addresses a ON a.user_id=u.id AND a.is_default=TRUE WHERE u.username='user01';
INSERT INTO orders(id,user_id,total_amount,status,ordered_at,shipping_name,shipping_postal_code,shipping_prefecture,shipping_city,shipping_address_line,shipping_phone,handling_status,assigned_admin_account_id,change_deadline_at,item_subtotal,charge_total,tax_amount)
SELECT 1002,u.id,1030,'ORDERED','2026-09-27 15:00',a.recipient_name,a.postal_code,a.prefecture,a.city,a.address_line,a.phone,'NONE',NULL,'2026-09-28 14:00:00',480,550,93
FROM users u JOIN shipping_addresses a ON a.user_id=u.id AND a.is_default=TRUE WHERE u.username='user01';
INSERT INTO orders(id,user_id,total_amount,status,ordered_at,shipping_name,shipping_postal_code,shipping_prefecture,shipping_city,shipping_address_line,shipping_phone,handling_status,assigned_admin_account_id,change_deadline_at,item_subtotal,charge_total,tax_amount)
SELECT 1003,u.id,5980,'SHIPPED','2026-09-20 09:00',a.recipient_name,a.postal_code,a.prefecture,a.city,a.address_line,a.phone,'RESOLVED',(SELECT id FROM admin_accounts WHERE username='admin01'),'2026-09-20 14:00:00',5980,0,543
FROM users u JOIN shipping_addresses a ON a.user_id=u.id AND a.is_default=TRUE WHERE u.username='user02';
INSERT INTO orders(id,user_id,total_amount,status,ordered_at,shipping_name,shipping_postal_code,shipping_prefecture,shipping_city,shipping_address_line,shipping_phone,handling_status,assigned_admin_account_id,change_deadline_at,item_subtotal,charge_total,tax_amount)
SELECT 1004,u.id,6000,'PAID','2026-09-21 11:00',a.recipient_name,a.postal_code,a.prefecture,a.city,a.address_line,a.phone,'RESOLVED',(SELECT id FROM admin_accounts WHERE username='admin02'),'2026-09-21 14:00:00',6000,0,545
FROM users u JOIN shipping_addresses a ON a.user_id=u.id AND a.is_default=TRUE WHERE u.username='user02';
INSERT INTO orders(id,user_id,total_amount,status,ordered_at,shipping_name,shipping_postal_code,shipping_prefecture,shipping_city,shipping_address_line,shipping_phone,handling_status,assigned_admin_account_id,change_deadline_at,item_subtotal,charge_total,tax_amount)
SELECT 1005,u.id,4530,'CANCELLED','2026-09-22 13:00',a.recipient_name,a.postal_code,a.prefecture,a.city,a.address_line,a.phone,'RESOLVED',(SELECT id FROM admin_accounts WHERE username='admin01'),'2026-09-22 14:00:00',3980,550,411
FROM users u JOIN shipping_addresses a ON a.user_id=u.id AND a.is_default=TRUE WHERE u.username='user02';
INSERT INTO orders(id,user_id,total_amount,status,ordered_at,shipping_name,shipping_postal_code,shipping_prefecture,shipping_city,shipping_address_line,shipping_phone,handling_status,assigned_admin_account_id,change_deadline_at,item_subtotal,charge_total,tax_amount)
SELECT 1006,u.id,2030,'ORDERED','2026-09-23 10:00',a.recipient_name,a.postal_code,a.prefecture,a.city,a.address_line,a.phone,'NEEDS_ACTION',NULL,'2026-09-23 14:00:00',1480,550,184
FROM users u JOIN shipping_addresses a ON a.user_id=u.id AND a.is_default=TRUE WHERE u.username='user03';
INSERT INTO orders(id,user_id,total_amount,status,ordered_at,shipping_name,shipping_postal_code,shipping_prefecture,shipping_city,shipping_address_line,shipping_phone,handling_status,assigned_admin_account_id,change_deadline_at,item_subtotal,charge_total,tax_amount)
SELECT 1007,u.id,1710,'ORDERED','2026-09-24 16:00',a.recipient_name,a.postal_code,a.prefecture,a.city,a.address_line,a.phone,'NEEDS_ACTION',(SELECT id FROM admin_accounts WHERE username='admin01'),'2026-09-25 14:00:00',1160,550,155
FROM users u JOIN shipping_addresses a ON a.user_id=u.id AND a.is_default=TRUE WHERE u.username='user04';
INSERT INTO orders(id,user_id,total_amount,status,ordered_at,shipping_name,shipping_postal_code,shipping_prefecture,shipping_city,shipping_address_line,shipping_phone,handling_status,assigned_admin_account_id,change_deadline_at,item_subtotal,charge_total,tax_amount)
SELECT 1008,u.id,3750,'ORDERED','2026-09-25 10:00',a.recipient_name,a.postal_code,a.prefecture,a.city,a.address_line,a.phone,'IN_PROGRESS',(SELECT id FROM admin_accounts WHERE username='admin02'),'2026-09-25 14:00:00',3200,550,340
FROM users u JOIN shipping_addresses a ON a.user_id=u.id AND a.is_default=TRUE WHERE u.username='user06';
INSERT INTO orders(id,user_id,total_amount,status,ordered_at,shipping_name,shipping_postal_code,shipping_prefecture,shipping_city,shipping_address_line,shipping_phone,handling_status,assigned_admin_account_id,change_deadline_at,item_subtotal,charge_total,tax_amount)
SELECT 1009,u.id,1750,'ORDERED','2026-09-26 10:00',a.recipient_name,a.postal_code,a.prefecture,a.city,a.address_line,a.phone,'NONE',NULL,'2026-09-26 14:00:00',1200,550,138
FROM users u JOIN shipping_addresses a ON a.user_id=u.id AND a.is_default=TRUE WHERE u.username='user07';
INSERT INTO orders(id,user_id,total_amount,status,ordered_at,shipping_name,shipping_postal_code,shipping_prefecture,shipping_city,shipping_address_line,shipping_phone,handling_status,assigned_admin_account_id,change_deadline_at,item_subtotal,charge_total,tax_amount)
SELECT 1010,u.id,2590,'ORDERED','2026-09-26 15:00',a.recipient_name,a.postal_code,a.prefecture,a.city,a.address_line,a.phone,'NONE',NULL,'2026-09-27 14:00:00',2040,550,201
FROM users u JOIN shipping_addresses a ON a.user_id=u.id AND a.is_default=TRUE WHERE u.username='user07';
INSERT INTO orders(id,user_id,total_amount,status,ordered_at,shipping_name,shipping_postal_code,shipping_prefecture,shipping_city,shipping_address_line,shipping_phone,handling_status,assigned_admin_account_id,change_deadline_at,item_subtotal,charge_total,tax_amount)
SELECT 1011,u.id,4030,'ORDERED','2026-09-27 09:00',a.recipient_name,a.postal_code,a.prefecture,a.city,a.address_line,a.phone,'NONE',NULL,'2026-09-27 14:00:00',3480,550,354
FROM users u JOIN shipping_addresses a ON a.user_id=u.id AND a.is_default=TRUE WHERE u.username='user08';
INSERT INTO orders(id,user_id,total_amount,status,ordered_at,shipping_name,shipping_postal_code,shipping_prefecture,shipping_city,shipping_address_line,shipping_phone,handling_status,assigned_admin_account_id,change_deadline_at,item_subtotal,charge_total,tax_amount)
SELECT 1012,u.id,6340,'ORDERED','2026-09-27 12:00',a.recipient_name,a.postal_code,a.prefecture,a.city,a.address_line,a.phone,'NONE',NULL,'2026-09-27 14:00:00',6340,0,569
FROM users u JOIN shipping_addresses a ON a.user_id=u.id AND a.is_default=TRUE WHERE u.username='user08';
INSERT INTO orders(id,user_id,total_amount,status,ordered_at,shipping_name,shipping_postal_code,shipping_prefecture,shipping_city,shipping_address_line,shipping_phone,handling_status,assigned_admin_account_id,change_deadline_at,item_subtotal,charge_total,tax_amount)
SELECT 1013,u.id,7960,'SHIPPED','2026-08-05 10:00',a.recipient_name,a.postal_code,a.prefecture,a.city,a.address_line,a.phone,'RESOLVED',(SELECT id FROM admin_accounts WHERE username='admin01'),'2026-08-05 14:00:00',7960,0,723
FROM users u JOIN shipping_addresses a ON a.user_id=u.id AND a.is_default=TRUE WHERE u.username='user10';
INSERT INTO orders(id,user_id,total_amount,status,ordered_at,shipping_name,shipping_postal_code,shipping_prefecture,shipping_city,shipping_address_line,shipping_phone,handling_status,assigned_admin_account_id,change_deadline_at,item_subtotal,charge_total,tax_amount)
SELECT 1014,u.id,5460,'SHIPPED','2026-08-12 10:00',a.recipient_name,a.postal_code,a.prefecture,a.city,a.address_line,a.phone,'RESOLVED',(SELECT id FROM admin_accounts WHERE username='admin02'),'2026-08-12 14:00:00',5460,0,496
FROM users u JOIN shipping_addresses a ON a.user_id=u.id AND a.is_default=TRUE WHERE u.username='user10';
INSERT INTO orders(id,user_id,total_amount,status,ordered_at,shipping_name,shipping_postal_code,shipping_prefecture,shipping_city,shipping_address_line,shipping_phone,handling_status,assigned_admin_account_id,change_deadline_at,item_subtotal,charge_total,tax_amount)
SELECT 1015,u.id,5600,'SHIPPED','2026-08-20 10:00',a.recipient_name,a.postal_code,a.prefecture,a.city,a.address_line,a.phone,'RESOLVED',(SELECT id FROM admin_accounts WHERE username='admin03'),'2026-08-20 14:00:00',5600,0,509
FROM users u JOIN shipping_addresses a ON a.user_id=u.id AND a.is_default=TRUE WHERE u.username='user02';
INSERT INTO orders(id,user_id,total_amount,status,ordered_at,shipping_name,shipping_postal_code,shipping_prefecture,shipping_city,shipping_address_line,shipping_phone,handling_status,assigned_admin_account_id,change_deadline_at,item_subtotal,charge_total,tax_amount)
SELECT 1016,u.id,5800,'SHIPPED','2026-07-03 10:00',a.recipient_name,a.postal_code,a.prefecture,a.city,a.address_line,a.phone,'RESOLVED',(SELECT id FROM admin_accounts WHERE username='admin01'),'2026-07-03 14:00:00',5800,0,527
FROM users u JOIN shipping_addresses a ON a.user_id=u.id AND a.is_default=TRUE WHERE u.username='user01';
INSERT INTO orders(id,user_id,total_amount,status,ordered_at,shipping_name,shipping_postal_code,shipping_prefecture,shipping_city,shipping_address_line,shipping_phone,handling_status,assigned_admin_account_id,change_deadline_at,item_subtotal,charge_total,tax_amount)
SELECT 1017,u.id,5920,'PAID','2026-07-15 10:00',a.recipient_name,a.postal_code,a.prefecture,a.city,a.address_line,a.phone,'RESOLVED',(SELECT id FROM admin_accounts WHERE username='admin02'),'2026-07-15 14:00:00',5920,0,538
FROM users u JOIN shipping_addresses a ON a.user_id=u.id AND a.is_default=TRUE WHERE u.username='user06';
INSERT INTO orders(id,user_id,total_amount,status,ordered_at,shipping_name,shipping_postal_code,shipping_prefecture,shipping_city,shipping_address_line,shipping_phone,handling_status,assigned_admin_account_id,change_deadline_at,item_subtotal,charge_total,tax_amount)
SELECT 1018,u.id,1990,'CANCELLED','2026-07-28 10:00',a.recipient_name,a.postal_code,a.prefecture,a.city,a.address_line,a.phone,'RESOLVED',(SELECT id FROM admin_accounts WHERE username='admin01'),'2026-07-28 14:00:00',1440,550,156
FROM users u JOIN shipping_addresses a ON a.user_id=u.id AND a.is_default=TRUE WHERE u.username='user07';
INSERT INTO orders(id,user_id,total_amount,status,ordered_at,shipping_name,shipping_postal_code,shipping_prefecture,shipping_city,shipping_address_line,shipping_phone,handling_status,assigned_admin_account_id,change_deadline_at,item_subtotal,charge_total,tax_amount)
SELECT 1019,u.id,5920,'SHIPPED','2026-06-10 10:00',a.recipient_name,a.postal_code,a.prefecture,a.city,a.address_line,a.phone,'RESOLVED',(SELECT id FROM admin_accounts WHERE username='admin03'),'2026-06-10 14:00:00',5920,0,491
FROM users u JOIN shipping_addresses a ON a.user_id=u.id AND a.is_default=TRUE WHERE u.username='user08';
INSERT INTO orders(id,user_id,total_amount,status,ordered_at,shipping_name,shipping_postal_code,shipping_prefecture,shipping_city,shipping_address_line,shipping_phone,handling_status,assigned_admin_account_id,change_deadline_at,item_subtotal,charge_total,tax_amount)
SELECT 1020,u.id,5980,'SHIPPED','2026-06-25 10:00',a.recipient_name,a.postal_code,a.prefecture,a.city,a.address_line,a.phone,'RESOLVED',(SELECT id FROM admin_accounts WHERE username='admin01'),'2026-06-25 14:00:00',5980,0,543
FROM users u JOIN shipping_addresses a ON a.user_id=u.id AND a.is_default=TRUE WHERE u.username='user10';
INSERT INTO orders(id,user_id,total_amount,status,ordered_at,shipping_name,shipping_postal_code,shipping_prefecture,shipping_city,shipping_address_line,shipping_phone,handling_status,assigned_admin_account_id,change_deadline_at,item_subtotal,charge_total,tax_amount)
SELECT 1021,u.id,5440,'SHIPPED','2026-05-08 10:00',a.recipient_name,a.postal_code,a.prefecture,a.city,a.address_line,a.phone,'RESOLVED',(SELECT id FROM admin_accounts WHERE username='admin02'),'2026-05-08 14:00:00',5440,0,494
FROM users u JOIN shipping_addresses a ON a.user_id=u.id AND a.is_default=TRUE WHERE u.username='user02';
INSERT INTO orders(id,user_id,total_amount,status,ordered_at,shipping_name,shipping_postal_code,shipping_prefecture,shipping_city,shipping_address_line,shipping_phone,handling_status,assigned_admin_account_id,change_deadline_at,item_subtotal,charge_total,tax_amount)
SELECT 1022,u.id,2070,'CANCELLED','2026-05-22 10:00',a.recipient_name,a.postal_code,a.prefecture,a.city,a.address_line,a.phone,'RESOLVED',(SELECT id FROM admin_accounts WHERE username='admin01'),'2026-05-22 14:00:00',1520,550,188
FROM users u JOIN shipping_addresses a ON a.user_id=u.id AND a.is_default=TRUE WHERE u.username='user01';
INSERT INTO orders(id,user_id,total_amount,status,ordered_at,shipping_name,shipping_postal_code,shipping_prefecture,shipping_city,shipping_address_line,shipping_phone,handling_status,assigned_admin_account_id,change_deadline_at,item_subtotal,charge_total,tax_amount)
SELECT 1023,u.id,3350,'SHIPPED','2026-04-25 10:00',a.recipient_name,a.postal_code,a.prefecture,a.city,a.address_line,a.phone,'RESOLVED',(SELECT id FROM admin_accounts WHERE username='admin03'),'2026-04-25 14:00:00',2800,550,304
FROM users u JOIN shipping_addresses a ON a.user_id=u.id AND a.is_default=TRUE WHERE u.username='user06';
INSERT INTO orders(id,user_id,total_amount,status,ordered_at,shipping_name,shipping_postal_code,shipping_prefecture,shipping_city,shipping_address_line,shipping_phone,handling_status,assigned_admin_account_id,change_deadline_at,item_subtotal,charge_total,tax_amount)
SELECT 1024,u.id,6000,'SHIPPED','2026-04-28 10:00',a.recipient_name,a.postal_code,a.prefecture,a.city,a.address_line,a.phone,'RESOLVED',(SELECT id FROM admin_accounts WHERE username='admin02'),'2026-04-28 14:00:00',6000,0,444
FROM users u JOIN shipping_addresses a ON a.user_id=u.id AND a.is_default=TRUE WHERE u.username='user08';
INSERT INTO orders(id,user_id,total_amount,status,ordered_at,shipping_name,shipping_postal_code,shipping_prefecture,shipping_city,shipping_address_line,shipping_phone,handling_status,assigned_admin_account_id,change_deadline_at,item_subtotal,charge_total,tax_amount)
SELECT 1025,u.id,5540,'SHIPPED','2026-03-30 10:00',a.recipient_name,a.postal_code,a.prefecture,a.city,a.address_line,a.phone,'RESOLVED',(SELECT id FROM admin_accounts WHERE username='admin01'),'2026-03-30 14:00:00',5540,0,476
FROM users u JOIN shipping_addresses a ON a.user_id=u.id AND a.is_default=TRUE WHERE u.username='user10';
INSERT INTO order_items(order_id,product_id,product_name,price,quantity,subtotal,category_id,category_name,tax_category_id,tax_category_code,tax_category_name,tax_rate)
SELECT v.oid,p.id,p.name,p.price,v.qty,p.price*v.qty,c.id,c.name,t.id,t.code,t.name,t.tax_rate
FROM (VALUES
(1001,'サンプル 電気ケトル 1.0L',1),
(1002,'サンプル 油性ボールペン 3色',1),
(1003,'サンプル 2枚焼きトースター',1),
(1004,'サンプル書籍 Java入門',1),
(1004,'サンプル書籍 Spring Boot入門',1),
(1005,'サンプル ワイヤレスマウス',1),
(1006,'サンプル USB-Cケーブル 2m',1),
(1007,'サンプル A5リングノート',2),
(1008,'サンプル書籍 Spring Boot入門',1),
(1009,'サンプル レギュラーコーヒー豆 200g',1),
(1010,'サンプル ドリップコーヒー 5袋',2),
(1010,'サンプル パスタ 500g',1),
(1011,'サンプル クッキー',1),
(1011,'サンプル書籍 Java入門',1),
(1012,'サンプル ミネラルウォーター 500ml',2),
(1012,'サンプル 2枚焼きトースター',1),
(1013,'サンプル ワイヤレスマウス',2),
(1014,'サンプル 電気ケトル 1.0L',1),
(1014,'サンプル 油性ボールペン 3色',1),
(1015,'サンプル書籍 Java入門',2),
(1016,'サンプル A5リングノート',10),
(1017,'サンプル USB-Cケーブル 2m',4),
(1018,'サンプル パスタ 500g',3),
(1019,'サンプル クッキー',4),
(1019,'サンプル書籍 Spring Boot入門',1),
(1020,'サンプル 2枚焼きトースター',1),
(1021,'サンプル シャープペン 0.5mm',8),
(1022,'サンプル 方眼メモ帳',4),
(1023,'サンプル書籍 Java入門',1),
(1024,'サンプル レギュラーコーヒー豆 200g',5),
(1025,'サンプル ワイヤレスマウス',1),
(1025,'サンプル ドリップコーヒー 5袋',2)) v(oid,pname,qty)
JOIN products p ON p.name=v.pname JOIN categories c ON c.id=p.category_id JOIN tax_categories t ON t.id=p.tax_category_id;

INSERT INTO order_charges(order_id,charge_type,name,amount,tax_category_id,tax_category_code,tax_category_name,tax_rate,display_order,created_at)
SELECT o.id,'SHIPPING','送料・梱包料',o.charge_total,t.id,t.code,t.name,t.tax_rate,100,o.ordered_at
FROM orders o CROSS JOIN tax_categories t WHERE o.id BETWEEN 1001 AND 1025 AND t.code='STANDARD';

-- 7. 在庫履歴
-- products.stock は最終在庫として sample_products.sql で設定済み。
-- 各商品の初期在庫を「現在在庫 + 有効注文数量」とみなし、
-- 注文時の在庫減少、キャンセル時の在庫復元を履歴として再現する。

WITH ordered_items AS (
    SELECT
        oi.order_id,
        oi.product_id,
        oi.product_name,
        oi.quantity,
        o.status,
        o.ordered_at,
        p.stock AS final_stock,
        SUM(
            CASE
                WHEN o.status <> 'CANCELLED' THEN oi.quantity
                ELSE 0
            END
        ) OVER (PARTITION BY oi.product_id) AS effective_quantity,
        SUM(oi.quantity) OVER (
            PARTITION BY oi.product_id
            ORDER BY o.ordered_at, o.id
            ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
        ) AS previous_placed_quantity,
        SUM(
            CASE
                WHEN o.status = 'CANCELLED' THEN oi.quantity
                ELSE 0
            END
        ) OVER (
            PARTITION BY oi.product_id
            ORDER BY o.ordered_at, o.id
            ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
        ) AS previous_cancelled_quantity
    FROM order_items oi
    JOIN orders o ON o.id = oi.order_id
    JOIN products p ON p.id = oi.product_id
    WHERE o.id BETWEEN 1001 AND 1025
)
INSERT INTO stock_movements (
    product_id,
    product_name,
    movement_type,
    quantity,
    stock_before,
    stock_after,
    changed_by_type,
    changed_by_account_id,
    changed_by_username,
    order_id,
    reason,
    changed_at
)
SELECT
    product_id,
    product_name,
    'ORDER_PLACEMENT',
    -quantity,
    final_stock
        + effective_quantity
        - COALESCE(previous_placed_quantity, 0)
        + COALESCE(previous_cancelled_quantity, 0),
    final_stock
        + effective_quantity
        - COALESCE(previous_placed_quantity, 0)
        + COALESCE(previous_cancelled_quantity, 0)
        - quantity,
    'SYSTEM',
    NULL,
    'SYSTEM',
    order_id,
    NULL,
    ordered_at
FROM ordered_items;

WITH cancelled_items AS (
    SELECT
        oi.order_id,
        oi.product_id,
        oi.product_name,
        oi.quantity,
        o.ordered_at,
        p.stock AS final_stock,

        SUM(
            CASE
                WHEN o2.status <> 'CANCELLED' THEN oi2.quantity
                ELSE 0
            END
        ) AS effective_quantity,

        SUM(
            CASE
                WHEN o2.ordered_at <= o.ordered_at
                THEN oi2.quantity
                ELSE 0
            END
        ) AS placed_through_this_order,

        SUM(
            CASE
                WHEN o2.status = 'CANCELLED'
                     AND o2.ordered_at < o.ordered_at
                THEN oi2.quantity
                ELSE 0
            END
        ) AS previously_cancelled_quantity

    FROM order_items oi
    JOIN orders o ON o.id = oi.order_id
    JOIN products p ON p.id = oi.product_id

    JOIN order_items oi2
      ON oi2.product_id = oi.product_id
    JOIN orders o2
      ON o2.id = oi2.order_id
     AND o2.id BETWEEN 1001 AND 1025

    WHERE o.id BETWEEN 1001 AND 1025
      AND o.status = 'CANCELLED'

    GROUP BY
        oi.order_id,
        oi.product_id,
        oi.product_name,
        oi.quantity,
        o.ordered_at,
        p.stock
)
INSERT INTO stock_movements (
    product_id,
    product_name,
    movement_type,
    quantity,
    stock_before,
    stock_after,
    changed_by_type,
    changed_by_account_id,
    changed_by_username,
    order_id,
    reason,
    changed_at
)
SELECT
    product_id,
    product_name,
    'ORDER_CANCELLATION',
    quantity,

    final_stock
        + effective_quantity
        - placed_through_this_order
        + previously_cancelled_quantity,

    final_stock
        + effective_quantity
        - placed_through_this_order
        + previously_cancelled_quantity
        + quantity,

    'SYSTEM',
    NULL,
    'SYSTEM',
    order_id,
    NULL,
    ordered_at + INTERVAL '1 day'
FROM cancelled_items;

-- 8. 各種注文履歴
INSERT INTO order_status_histories(order_id,from_status,to_status,changed_by_type,changed_by_account_id,changed_by_username,changed_at,internal_note)
SELECT o.id,NULL,'ORDERED','USER',o.user_id,u.username,o.ordered_at,NULL FROM orders o JOIN users u ON u.id=o.user_id WHERE o.id BETWEEN 1001 AND 1025;

INSERT INTO order_status_histories(order_id,from_status,to_status,changed_by_type,changed_by_account_id,changed_by_username,changed_at,internal_note)
SELECT o.id,'ORDERED',CASE WHEN o.status='SHIPPED' THEN 'PAID' ELSE o.status END,'ADMIN',a.id,a.username,o.ordered_at+INTERVAL '1 day','開発用標準履歴'
FROM orders o JOIN admin_accounts a ON a.id=o.assigned_admin_account_id WHERE o.id BETWEEN 1001 AND 1025 AND o.status IN('PAID','SHIPPED','CANCELLED');

INSERT INTO order_status_histories(order_id,from_status,to_status,changed_by_type,changed_by_account_id,changed_by_username,changed_at,internal_note)
SELECT o.id,'PAID','SHIPPED','ADMIN',a.id,a.username,o.ordered_at+INTERVAL '2 days','発送完了'
FROM orders o JOIN admin_accounts a ON a.id=o.assigned_admin_account_id WHERE o.id BETWEEN 1001 AND 1025 AND o.status='SHIPPED';

INSERT INTO order_handling_status_histories(order_id,from_status,to_status,changed_by_account_id,changed_by_username,changed_at)
SELECT o.id,'NONE',o.handling_status,a.id,a.username,o.ordered_at+INTERVAL '2 hours'
FROM orders o JOIN admin_accounts a ON a.id=COALESCE(o.assigned_admin_account_id,(SELECT id FROM admin_accounts WHERE username='admin01'))
WHERE o.id BETWEEN 1001 AND 1025 AND o.handling_status<>'NONE';

INSERT INTO order_assignee_histories(order_id,from_admin_account_id,from_admin_username,to_admin_account_id,to_admin_username,changed_by_account_id,changed_by_username,change_event_id,changed_at)
SELECT o.id,NULL,NULL,a.id,a.username,a.id,a.username,('00000000-0000-0000-0000-'||LPAD(o.id::text,12,'0'))::uuid,o.ordered_at+INTERVAL '1 hour'
FROM orders o JOIN admin_accounts a ON a.id=o.assigned_admin_account_id WHERE o.id BETWEEN 1001 AND 1025;

INSERT INTO order_notes(order_id,note,created_by_account_id,created_by_username,created_at)
SELECT 1008,'配送先変更依頼を確認。',a.id,a.username,'2026-09-25 13:00' FROM admin_accounts a WHERE a.username='admin02';

INSERT INTO order_shipping_address_histories(order_id,changed_by_type,changed_by_account_id,changed_by_username,
old_shipping_name,old_shipping_postal_code,old_shipping_prefecture,old_shipping_city,old_shipping_address_line,old_shipping_phone,
new_shipping_name,new_shipping_postal_code,new_shipping_prefecture,new_shipping_city,new_shipping_address_line,new_shipping_phone,changed_at)
SELECT o.id,'USER',u.id,u.username,o.shipping_name,o.shipping_postal_code,o.shipping_prefecture,o.shipping_city,o.shipping_address_line,o.shipping_phone,
a.recipient_name,a.postal_code,a.prefecture,a.city,a.address_line,a.phone,'2026-09-25 12:00'
FROM orders o JOIN users u ON u.id=o.user_id JOIN shipping_addresses a ON a.user_id=u.id AND a.name='勤務先' WHERE o.id=1008;

UPDATE orders o SET shipping_name=a.recipient_name,shipping_postal_code=a.postal_code,shipping_prefecture=a.prefecture,shipping_city=a.city,shipping_address_line=a.address_line,shipping_phone=a.phone
FROM shipping_addresses a WHERE o.id=1008 AND a.user_id=o.user_id AND a.name='勤務先';

-- 9. アカウント状態変更履歴
INSERT INTO user_enabled_histories(user_id,from_enabled,to_enabled,changed_by_account_id,changed_by_username,changed_at)
SELECT u.id,TRUE,FALSE,a.id,a.username,'2026-09-15 10:00' FROM users u CROSS JOIN admin_accounts a WHERE u.username='user05' AND a.username='admin01';
INSERT INTO user_enabled_histories(user_id,from_enabled,to_enabled,changed_by_account_id,changed_by_username,changed_at)
SELECT u.id,TRUE,FALSE,a.id,a.username,'2026-08-01 10:00' FROM users u CROSS JOIN admin_accounts a WHERE u.username='user10' AND a.username='admin02';
INSERT INTO user_enabled_histories(user_id,from_enabled,to_enabled,changed_by_account_id,changed_by_username,changed_at)
SELECT u.id,FALSE,TRUE,a.id,a.username,'2026-08-03 10:00' FROM users u CROSS JOIN admin_accounts a WHERE u.username='user10' AND a.username='admin01';

-- 10. お知らせ
INSERT INTO announcements(type,importance,title,content,published,published_at,created_at,updated_at) VALUES
('GENERAL','NORMAL','ECサイトへようこそ','開発環境用のお知らせです。',TRUE,'2026-04-01 09:00','2026-04-01 08:00','2026-04-01 08:00'),
('PRODUCT','NORMAL','新商品を追加しました','サンプル商品を追加しました。',TRUE,'2026-05-01 09:00','2026-05-01 08:00','2026-05-01 08:00'),
('SHIPPING','IMPORTANT','配送に関するお知らせ','配送状況により到着まで時間がかかる場合があります。',TRUE,'2026-06-01 09:00','2026-06-01 08:00','2026-06-01 08:00'),
('MAINTENANCE','IMPORTANT','メンテナンスのお知らせ','システムメンテナンスを予定しています。',TRUE,'2026-07-01 09:00','2026-07-01 08:00','2026-07-01 08:00'),
('GENERAL','NORMAL','夏季のお知らせ','夏季期間中の営業についてのお知らせです。',TRUE,'2026-08-01 09:00','2026-08-01 08:00','2026-08-01 08:00'),
('PRODUCT','NORMAL','食品・飲料カテゴリを追加しました','軽減税率対象のサンプル商品を追加しました。',TRUE,'2026-09-01 09:00','2026-09-01 08:00','2026-09-01 08:00'),
('SHIPPING','URGENT','配送遅延のお知らせ','一部地域で配送遅延が発生しています。',TRUE,'2026-09-20 09:00','2026-09-20 08:00','2026-09-20 08:00'),
('GENERAL','NORMAL','会員機能更新のお知らせ','マイページ機能を更新しました。',TRUE,'2026-09-25 09:00','2026-09-25 08:00','2026-09-25 08:00'),
('MAINTENANCE','NORMAL','メンテナンス予告','開発確認用の未公開お知らせです。',FALSE,NULL,'2026-09-28 08:00','2026-09-28 08:00'),
('PRODUCT','IMPORTANT','価格表示について','商品価格は税込価格で表示しています。',TRUE,'2026-09-29 09:00','2026-09-29 08:00','2026-09-29 08:00');

SELECT setval(pg_get_serial_sequence('orders','id'),(SELECT MAX(id) FROM orders),TRUE);
COMMIT;
