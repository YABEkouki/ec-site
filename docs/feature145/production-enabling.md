# Feature145 本番で有効にするための手順書と状態の画面

状態：設計案（承認待ち）。基準は main 7786db3（最新マイグレーション V113）。

## 1. 目的

- これまでの Feature では、外部に影響する処理を既定で止めてきた。影響する先は PAY.JP への POST、メール、倉庫へのファイルである。どれを、どの順で、どの設定で有効にするかを 1 つの手順書にまとめる。
- 管理者が画面で、各処理が今有効か、最後にいつ動いたか、たまっている件数はいくつかを確かめられるようにする。

## 2. 調査結果（2026-11-27、main 7786db3）

### 2.1 既定で止めている処理（`application.properties`）

| # | 処理 | 有効にする設定（環境変数） | 外部への影響 | 実績の記録 |
|---|---|---|---|---|
| 1 | お客様への注文メール（133） | `CUSTOMER_NOTIFICATION_ENABLED` | メール | `customer_order_notifications`、`_attempts` |
| 2 | 決済照合の通知メール（運用担当あて） | `PAYMENT_AUDIT_NOTIFICATION_ENABLED`、`_RECIPIENTS` | メール | `payment_audit_notifications` ほか |
| 3 | 与信期限の更新（135-B3） | `AUTHORIZATION_EXPIRY_REFRESH_ENABLED` | PAY.JP GET | `payment_authorization_expiries` |
| 4 | 未払い注文の終了（142-B） | `UNPAID_ORDER_CLOSURE_ENABLED`、`_AFTER` | PAY.JP POST（取消） | `unpaid_order_closures`、`_sends` |
| 5 | 終わらなかった注文変更の終了（143-B） | `ORDER_CHANGE_CLOSURE_ENABLED`、`_AFTER` | PAY.JP POST（取消） | `order_change_flow_closures`、`_sends` |
| 6 | 14:05 の倉庫向け出力と自動の出荷準備（131） | `WAREHOUSE_EXPORT_ENABLED`、`_START_DATE` | 注文の凍結 | `warehouse_export_batches`、`_lines`、`_holds` |
| 7 | 倉庫ファイルの書き出し（137-B） | `WAREHOUSE_FILE_ENABLED`、`_OUTPUT_DIR`、`_WORK_DIR` | ファイル | `warehouse_file_outputs`、`_retries` |
| 8 | 出荷済み CSV の取り込みと出荷（132） | `WAREHOUSE_SHIPMENT_IMPORT_ENABLED` | PAY.JP POST（売上確定） | `warehouse_shipment_imports`、`_lines` |

### 2.2 既定で動いている処理

- 決済の照合（`app.payment.reconciliation.enabled=true`、1 分ごと）。この設定には、次の Feature の後片付けも含まれる。
  - 129-B：不採用の取消と終了
  - 128：最終判定
  - 新しい Flow の後片付け
  - 142 と 143 の送信の回復
- 決済照合の監査（`app.payment.discrepancy-audit.enabled=true`、5 分ごと）。実績は `payment_discrepancy_audit_runs` にあり、管理画面で見られる。
- 手動再送のスケジュール（`manual-resend-scheduler-enabled=true`）。

### 2.3 処理のほかに本番で必要な設定

- PAY.JP
  - 本番のキー：`PAYJP_SECRET_KEY`、`PAYJP_PUBLIC_KEY`
  - Webhook：`PAYJP_WEBHOOK_TOKEN`
  - 売上確定の接続：`PAYJP_CAPTURECONNECTION_*`。`MODE=LIVE` にし、接続先とキーの対応を確かめる。
  - 保存カード：`PAYJP_CUSTOMER_ID_PREFIX`。環境ごとに別の値にする。空にすると保存カードは使えない。
- メール：`spring.mail.*`（開発は mailpit）。差出人 `app.mail.from`。
- URL：`app.base-url`。メールのリンクに使う。
- 監査記録の HMAC キー：`PAYMENT_AUDIT_HANDLING_HMAC_KEY`、`PAYMENT_AUDIT_MANUAL_RESEND_HMAC_KEY`
- Feature125（一括編集）の runtime
  - SQL（`scripts/order-edit-runtime.sql`）で承認する。手順は `docs/feature125/operational-runtime.md`。
  - DB を再起動すると、承認は無効になる。止まっている間は、一括編集と、その後片付けの一部が動かない。
- PostgreSQL：`track_commit_timestamp`、`fsync`、`synchronous_commit` を on にする。
- 開発用の設定のまま：`spring.jpa.show-sql=true`、`spring.thymeleaf.cache=false`。本番では変える候補。

### 2.4 今の不足

- 手順が、各 Feature の設計書と `application.properties` のコメントに分かれていて、まとまっていない。
- 有効か無効かは、ログか設定を見ないとわからない。止まったことに気づく手段は、決済照合の監査にしかない。
- Feature125 の runtime の状態は、SQL でしか見られない。

## 3. 分割

| | 内容 | 変更 |
|---|---|---|
| 145-A | 手順書 `docs/operations/production-enabling.md` | 文書だけ |
| 145-B | 管理画面「運用の状態」（読み取りだけ） | Java と画面。マイグレーションなし |

## 4. 145-A 手順書

1. 前提の確認（2.3）。値そのものは書かない。どこで設定し、どう確かめるかを書く。
2. 有効にする順番（案）
   1. 決済照合の通知メール（2）。異常に気づけるようにする。
   2. お客様への注文メール（1）。注意：有効にすると、24 時間以内にたまった記録が送られる。
   3. 与信期限の更新（3）。GET だけ。
   4. 未払い注文の終了（4）と、終わらなかった注文変更の終了（5）。どちらも PAY.JP に取消を送る。
   5. 14:05 の出力（6）。`START_DATE` で、過去の注文を対象から外す。
   6. 倉庫ファイル（7）。
   7. 出荷済み CSV の取り込み（8）。売上の確定を送る。
3. 処理ごとの記載。次の項目を書く。
   - 何をするか
   - 外部への影響
   - 前提と依存。例：7 の前に 6、8 の前に 6・7、4・5 の前に Feature125 の runtime
   - 設定と既定値
   - 有効にしたあとに見るところ（145-B の画面、表、ログ）
   - 止め方（設定を false にして再起動）
   - 止めたときに残るもの
4. 再起動・DB の再起動・障害のときの確認。DB の再起動のあとは、Feature125 の runtime を承認し直す。
5. 開発環境のときの注意（mailpit、PAY.JP テストモード、`start-dev.sh` は `.env` を読む）。

## 5. 145-B 運用の状態の画面

- URL：`/admin/operations`。管理者のメニューに「運用の状態」を加える。表示は読み取りだけ。ここから有効と無効は切り替えない。
- 処理ごとの行（2.1 の 8 つと 2.2 の 2 つ）に、次を表示する。
  - 有効か無効か（設定の値）
  - 主な設定：間隔、待ち時間、開始日など。キー・パスワード・宛先は出さず、宛先は件数だけにする。
  - 最後に動いた時刻：実績の表で最も新しい記録の時刻。
  - 未処理の件数：送信待ち、再試行待ち、確認が必要なもの。
- Feature125 の runtime：保存された状態、使えるかどうか、世代、版数。SQL の `status` と同じ読み取りで、書き込みはしない。
- PAY.JP の接続：モード（TEST か LIVE）と API の接続先。キーは出さない。
- 注意の表示（例）
  - 倉庫向け出力が有効なのに、開始日が空
  - 倉庫ファイルが無効なのに、未出力の記録がある
  - 通知が有効なのに、宛先が 0 件
  - runtime が止まっているのに、処理待ちの一括編集がある
  - 未払い注文の終了か注文変更の終了が有効なのに、PAY.JP がテストモード（本番の設定の誤りの可能性）
- テスト：設定の組み合わせごとの表示、秘密の値が出ないこと、権限（未ログインと一般ユーザーは拒否）。

## 6. 対象外

- 画面からの有効・無効の切り替え。再起動と設定の管理は、運用の手順で行う。
- 外部の監視（死活監視、ログの収集）。手順書には、必要なことだけを書く。
- 休業日カレンダー、出荷中止（別の候補）。

## 7. 決めていただくこと

| # | 項目 | 案 |
|---|---|---|
| 1 | 分割 | 145-A（手順書）→ 145-B（画面）の順 |
| 2 | 手順書の場所 | `docs/operations/production-enabling.md`（新しいフォルダ） |
| 3 | 有効にする順番 | 4 章 2 の順 |
| 4 | 画面 | `/admin/operations`。読み取りだけで、切り替えはしない。管理者全員が見られる |
| 5 | 秘密の扱い | キー・パスワード・宛先は画面にも手順書にも出さない。宛先は件数だけ |
| 6 | 本番の候補の設定 | `show-sql` と `thymeleaf.cache` は手順書で「本番では変える」と書くだけにする（コードは変えない） |

## 8. 確認

- 145-A は文書だけ。ご確認いただいてから統合する。
- 145-B は、ブラウザでの確認のため、アプリの再起動が必要（PAY.JP の GET が発生する）。事前に確認する。
