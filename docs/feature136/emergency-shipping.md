# Feature136 緊急出荷

設計資料 `docs/future/warehouse-shipping-design.md` の 5 章「緊急出荷」と 12 章 9「緊急出荷の権限・監査・例外ルール」を実装する。

## 進捗

| サブ Feature | 内容 | マイグレーション | 状態 |
|---|---|---|---|
| 136-A | 権限・理由・監査記録。管理画面の「発送準備を開始」を緊急出荷として作り直す | V97 | 実装中 |
| 136-B | 出荷準備に入ったすべての注文を、アプリと DB の両方で固定する | V98（予定） | 未着手 |
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
