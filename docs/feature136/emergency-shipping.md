# Feature136 緊急出荷

設計資料 `docs/future/warehouse-shipping-design.md` の 5 章「緊急出荷」と 12 章 9「緊急出荷の権限・監査・例外ルール」を実装する。

## 進捗

| サブ Feature | 内容 | マイグレーション | 状態 |
|---|---|---|---|
| 136-A | 権限・理由・監査記録。管理画面の「発送準備を開始」を緊急出荷として作り直す | V97 | 完了（2026-11-20、ブラウザ確認済み） |
| 136-B | 出荷準備に入ったすべての注文を、アプリと DB の両方で固定する | V98 | 完了（2026-11-21、回帰テスト 209 クラス合格） |
| 136-C | 緊急出荷の一覧・監査画面。14:00 前の緊急出荷の可否を決める | 必要なら V99 | 未着手 |

## 決定事項（2026-11-19 承認）

1. 権限は `admin_accounts.emergency_shipping_allowed`。付与・取消は運用担当が SQL で行い、アプリは書き込まない。変更は `admin_emergency_shipping_permission_histories` にトリガーで記録する（DB 時計・DB ユーザー）。管理画面は表示だけ。
2. 136-A・B では、緊急出荷も今と同じく注文変更期限（14:00）以降だけ。14:00 前の可否は 136-C で決める。
3. V97 より前の管理者による出荷準備（理由の記録なし）は「旧手動準備（理由なし）」と表示し、136-B で固定の対象に含める。
4. 理由は自由記述（必須・500 文字まで）。分類は付けない。

## 136-A の仕組み

### 操作の流れ（`OrderShippingPreparationService.start(orderId, reason, expectedContentRevision)`）

1. 理由（前後の空白を除いて 1〜500 文字）と、画面で確認した注文の版（`content_revision`）を必須にする。
2. 管理者として認証されていること、DB 上で緊急出荷の権限があることを確認する（ログイン時の値ではなく、その時点の DB の値を使う）。
3. Order をロックする。すでに出荷準備に入っていれば、何もせずに「開始済み」を返す（冪等。記録は増えない）。
4. 未解決の注文変更がないこと、決済の条件（カードなら有効な与信が 1 件で、与信期限まで 24 時間超）を確認する。Payment は id の昇順でロックする。
5. 画面を表示した後に注文の版が変わっていれば拒否する。未解決の決済不整合（`payment_discrepancies` が OPEN）があっても拒否する。
6. DB 時計で時刻を読み、変更期限を過ぎていることを確認する。
7. `emergency_shipping_preparations` に記録を書き、そのあと注文の出荷準備 3 列を設定して flush する。

### DB での担保（V97）

- `emergency_shipping_preparations`：1 注文 1 行、追記のみ（UPDATE・DELETE・TRUNCATE は 55000）。
  - INSERT 時に、管理者が有効で権限があり、ユーザー名が一致すること（42501）を確認する。
  - 注文がまだ出荷準備前で、状態が ORDERED または PAID で、変更期限が設定されていることを確認する。
  - 未完了の注文変更 operation、注文変更の受付、PENDING の決済取引、OPEN の決済不整合がないことを確認する（23514）。
  - 注文の写し（状態・版・金額・有効な決済・変更期限）はロック中の行からトリガーが設定する。`created_at` は DB 時計に固定する。
  - コミット時に、注文の出荷準備が記録と一致していることを確認する（遅延トリガー）。
- `orders`：管理者による出荷準備は、対応する記録がなければ拒否する。INSERT の時点で管理者の出荷準備が入った注文は作れない。緊急出荷の出荷準備は、あとから変更・取消できない。
- 権限の履歴は `f136_permission_audit` トリガーだけが書け、追記のみ。

### 136-A の範囲外（意図的に残したもの）

- 決済の条件（有効な与信が 1 件、減額ポリシー）はこれまでどおりアプリで確認する。V97 は競合の防止（未完了の変更・決済処理・不整合）に絞っている。与信期限は V93 の `f135_order_expiry_guard` が担保する。
- 変更期限（14:00 以降）は、136-C で 14:00 前の扱いが決まるまでアプリだけで確認する。
- 記録の `prepared_at`（注文の出荷準備開始時刻と一致させる値）は、DB 時計との前後を DB では比べない。既存テストが、固定時刻の fixture や、アプリの時計を変更期限に固定する方法（受付時刻と変更期限は V81 で変更できない）を使っているため。操作時刻の監査には、トリガーが DB 時計で設定する `created_at` を使う。アプリでは `prepared_at` に DB 時計の値を使う。
- 注文内容の固定は 136-B で行う。

## 136-B の仕組み（2026-11-20 承認）

- V98：V86 の `f131_order_preparation_guard` と `f131_prepared_content_guard` を置き換え、出荷準備の開始時刻が入っているすべての注文（倉庫出力・緊急出荷・V97 より前の管理者による準備）を固定する。固定する項目は V86 と同じ（出荷準備の情報・顧客・金額・版・配送先・有効な決済、CANCELLED への変更、明細と料金の追加・変更・削除。エラーコードは 55000）。既存の行は書き換えない。
- アプリ：`Order.requireContentMutable()` を、内容を変えるメソッド（金額・版・明細・料金・配送先・有効な決済の変更とキャンセル）と、`OrderItem`・`OrderCharge` の変更メソッドから呼ぶ。出荷準備後は `InvalidOrderStatusException` で拒否する。
- 出荷準備後にキャンセルする方法はない（設計資料 4.2 章の「出荷準備の取消禁止」）。出荷準備後に出荷できなくなった注文（与信期限切れ・在庫切れなど）のための「出荷中止」の手順は、今後の Feature の候補とする。
- テスト：出荷準備を扱う 56 クラスのうち、6 クラスを直した。出荷準備後に変更・キャンセルしていた fixture は、変更を出荷準備の前に移すか、DB とエンティティが拒否することを確かめる形にした。多重防御を試す単体テストは、フィールドを直接設定して状態を作る。

## 倉庫向け注文データ CSV との関係

緊急出荷の注文は、14:05 の倉庫向け注文データ出力（Feature131）には出力されない（決定事項：手動で準備した注文は自動出力しない）。出力対象の抽出（出荷準備前の注文だけ）、注文ごとの保留（`MANUALLY_PREPARED`）、V86 の出力行トリガー（自動の出荷準備だけ）の 3 段階で除外される。倉庫へは個別に連絡する。管理画面の注文一覧 CSV（`OrderCsvService`）は倉庫向けではなく、緊急出荷の注文も含む。

## 開発環境での確認記録（2026-11-20）

- Feature125 runtime を再承認した（Windows の再起動で PostgreSQL も再起動したため。generation 417c973d…、version 16）。
- アプリの起動で V97 を適用した。admin001 に権限を付与した（履歴 1 件）。
- 注文 24 を緊急出荷として開始した（理由の記録あり、倉庫出力行なし）。ブラウザ確認はユーザーが OK とした。

## 運用：権限の付与・取消

アプリを止める必要はない。変更は次のログインを待たずに有効になる（操作のたびに DB の値を確認するため）。

```sql
-- 付与
UPDATE admin_accounts SET emergency_shipping_allowed = TRUE WHERE username = '<管理者名>';
-- 取消
UPDATE admin_accounts SET emergency_shipping_allowed = FALSE WHERE username = '<管理者名>';
-- 履歴の確認
SELECT a.username, h.allowed, h.changed_at, h.changed_by_db_user
FROM admin_emergency_shipping_permission_histories h JOIN admin_accounts a ON a.id = h.admin_account_id
ORDER BY h.id;
```

## 確認用 SQL

```sql
SELECT r.order_id, r.admin_username, r.reason, r.prepared_at, r.created_at, r.order_status,
       r.content_revision, r.total_amount
FROM emergency_shipping_preparations r ORDER BY r.id DESC;
```

## テスト

- 管理者による出荷準備の fixture は `support/EmergencyShippingFixtures` を使う（権限のある管理者を作り、記録と出荷準備を同じトランザクションで書く）。DB トリガーは無効にしない。
- V55 や V56 までしかマイグレーションしない独自スキーマのテスト（`JdbcPaymentSafetySnapshotReaderPostgresTest`、`CaptureRequestEvidencePostgresTest`、`ObservationAssessmentPostgresTest`）は V97 の影響を受けない。
