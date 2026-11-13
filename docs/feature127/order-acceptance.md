# Feature127 正式受注時刻と変更期限の再基準化

設計資料：[docs/future/warehouse-shipping-design.md](../future/warehouse-shipping-design.md) 2章・3章、12章1〜2

## ルール

- 正式受注時刻 `orders.accepted_at` は、初回与信（`INITIAL_ORDER_AUTHORIZATION`、3DS完了を含む）の成功をサーバーが反映したトランザクション内で、Orderロック→Paymentロック取得後に読んだDB時刻（`clock_timestamp()`、Asia/Tokyo）とする。ブラウザ描画時刻・JVM時刻・注文操作開始時刻（`ordered_at`）は使用しない。
- 変更期限 `change_deadline_at` は正式受注時刻から計算する。14:00より前は当日14:00、14:00以降は翌日14:00。
- 正式受注前（3DS認証中・与信待ち）の注文は `change_deadline_at` がNULL。注文変更・顧客キャンセル・配送先変更・発送準備開始はすべて不可（当日出荷対象にならない）。
- 与信失敗の注文は正式受注しない。
- 正式受注時刻と期限は一度だけ記録し、再計算しない。注文変更の再与信（`ORDER_CHANGE_AUTHORIZATION`）は受注時刻を変えない。

## 経路とトランザクション境界

顧客の `/checkout/payment/continue`、PAY.JP Webhook、照合バッチはすべて `PaymentAuthorizationResultService.apply` に集約される。同一トランザクションで次を行う。

1. Order `FOR UPDATE`
2. `PaymentService.applyAuthorizationResult`（Payment id昇順ロック、AUTHORIZED/SUCCEEDED反映）
3. 結果が `APPLIED` かつ `AUTHORIZED` かつ初回与信目的の場合のみ `OrderAcceptanceService.acceptAfterInitialAuthorization`
   - DB時刻取得 → `accepted_at`・期限設定 → flush → `order_acceptances` 証跡INSERT

`ALREADY_APPLIED`（Webhookと顧客復帰の重複など）は何もしない。同時実行はOrderロックで直列化され、証跡は1件のみ。

Feature125のコミット時刻判定（xid記録→別Txで `pg_xact_commit_timestamp` 検証）は流用しない。runtime承認（既定STOPPED）に通常受注を依存させないため、また受注確定には「期限超過で拒否」の判定がなく、Orderロック下のDB時刻で後続処理と線形化できるため。監査用に `order_acceptances.acceptance_xid` を保存し、事後に `pg_xact_commit_timestamp` と照合できる（業務判定には使わない）。

## DB（V81）

- `orders.acceptance_mode`：`LEGACY`（V81以前の行。`ordered_at`基準の期限を保持し再計算しない）／`AUTHORIZED_ACCEPTANCE`（新規注文）。変更不可。
- `orders.accepted_at` 追加、`change_deadline_at` をNULL許容化。
- CHECK：LEGACYは期限必須・受注時刻なし。新方式は受注時刻と期限が同時にNULL/非NULL。期限は14:00ルールと一致。
- トリガー：
  - 新規INSERT時に受注済みとして作成不可。
  - 初回受注時刻は「現在トランザクションの開始〜現在のDB時刻」の範囲のみ許可（呼び出し側時刻の拒否）。`ORDERED` のみ。
  - 受注時刻・期限は変更不可。
  - `order_acceptances` は追記専用。初回与信SUCCEEDEDとOrderの値に一致する場合のみINSERT可。
  - DEFERRED制約トリガー：受注時刻と証跡は同時にコミットされなければならない。
- 期限の時刻は `app.order.change-deadline-time`（14:00）だが、DB側CHECKとV71は14:00固定。14:00以外に設定すると受注確定は失敗する（安全側）。

## 既存データ

V81時点の注文はすべてLEGACY。与信待ちだった注文も従来の `ordered_at` 基準の期限を維持する（V71の受付証拠が `change_deadline_at` に束縛されているため再計算しない）。

## 対象外（後続Feature）

締切直前再与信の14:00/14:05判定、倉庫出力バッチ、遅延与信取消、緊急出荷、顧客通知、休日・非稼働日。
