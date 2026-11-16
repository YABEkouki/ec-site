# Feature130-A 注文変更による出荷保留の管理者向け一覧

設計資料：[docs/future/warehouse-shipping-design.md](../future/warehouse-shipping-design.md) 4章・6.3章・8章・10章
前提：[Feature128 A-2 最終判定](../feature128/final-decision.md)、[Feature129 不採用確定後の自動取消](../feature129/not-adopted-cancellation.md)

## 目的

14:05の最終判定と129-A/Bの自動取消の後も、出荷準備・売上確定が止まったままの注文を管理者が把握できるようにする。**参照のみ**で、注文・決済の状態は一切変更しない。

設計資料には管理者向け通知の明記がない（9章は顧客通知で、手段は未決定）。このため、130-Aは画面の一覧とダッシュボードの件数表示だけを対象とした。メール通知が必要になれば、既存の決済監査メール通知の仕組みを再利用する別のサブFeatureとする。

## 「保留」の定義

出荷・Captureを止めている2つのゲートと同じ条件を使う（`OrderPaymentGuard.requireNoOrderChange`）。

1. 注文変更operationが `COMPLETED`／`ABORTED` 以外（`OrderChangeStore.unresolved`）
2. 一括編集admissionが `OrderEditAdmissionGuard.blocks` に該当する

ただし、14:05前の通常の進行中処理は保留として扱わない。一覧に載せるのは次の行。

| 区分 | 条件 | 対応 |
|---|---|---|
| 注文変更：手動確認待ち | operation `MANUAL_REVIEW`（最終判定の有無を問わない） | 管理者確認が必要 |
| 遅延成立：注文切替未完了 | 最終判定が `LATE_ADOPTION`、operationは未決着 | 自動処理中 |
| 不成立：不要な新与信の取消待ち | 最終判定が `NOT_ADOPTED`、operationは未決着 | 自動処理中（129-A/B） |
| 最終判定待ち | 未決着、最終判定なし、DB時刻 ≥ 期限＋5分 | 自動処理中（スケジューラ停止の検知） |
| 一括編集受付：確認が必要 | admissionが未終了で `REVIEW_REQUIRED` | 管理者確認が必要 |
| 一括編集受付：自動再試行待ち | admissionが未終了で `RECOVERY_PENDING` | 自動処理中 |
| 一括編集受付：期限後も未終了 | admissionが未終了、DB時刻 ≥ admissionの期限＋5分 | 自動処理中 |
| 一括編集受付：終了状態の不整合 | 終了済みだが `blocks` に該当する | 管理者確認が必要 |

- 期限は `orders.change_deadline_at` を使う（無い場合は operation の `deadline_at`）。
- admissionの `blocks` 判定は、SQL（`AdminOrderHoldViewService.ADMISSION_BLOCKS`）とJavaの二重実装になっている。テストで全264通りの組み合わせについて一致を確認している。
- 起点日時は次のとおり。最終判定ありは `final_at`、`MANUAL_REVIEW`／`REVIEW_REQUIRED`／`RECOVERY_PENDING` は最終更新時刻、期限超過のものは期限＋5分。起点から `app.order-hold.stale-after`（既定30分、環境変数 `ORDER_HOLD_STALE_AFTER`）以上たったものを「滞留」と表示する。
- 1つのSQL文で集計し、時刻はDBの `clock_timestamp()` を使う。ロックは取らない。

## 画面

- `/admin/order-holds`：一覧画面。並び順は「管理者確認が必要」→「滞留」→「経過時間の長い順」。注文詳細へのリンクがある。旧与信取消の再照合が可能な場合は、注文詳細に既存のリンクが表示される。
- `/admin`（ダッシュボード）：「注文変更による出荷保留」の行に、保留中の注文数・要確認数・滞留数を表示する。取得に失敗してもダッシュボード自体は表示する。
- 注文詳細：その注文の保留項目を表示する。
- ヘッダー：「出荷保留一覧」へのリンク。
- 認可は既存の `/admin/**`（ROLE_ADMIN）。GET以外のメソッドは提供しない。

既存の「要対応注文一覧」（`handling_status`）とは統合しない。あちらは人が手で設定する対応状況、こちらはシステムの状態から導出する保留で、意味が異なるため。

## 既知の問題（Feature130-Bで対応済み）

一括編集（owned）経路で、129-Bの照合が不要な新与信を取消すと、operationは `ABORTED` になるが、admissionは `REVIEW_REQUIRED`（reason `REAUTHORIZATION_ABORTED`）のまま残り、出荷・売上確定を止め続けていた。130-Bでテストにより再現し、V85で自動終了するようにした。詳細は [owned-admission-closure.md](owned-admission-closure.md)。

一覧では、まだ終了されていないこのケースに「照合処理が受付を自動で終了します。この表示が続く場合は…」と注記を表示する。紐づく注文変更が `MANUAL_REVIEW` の一括編集受付は「管理者確認が必要」に分類し、理由を注記する（130-Bで修正）。

## テスト

- `OrderReauthorizationPostgresTest`（追加3件）：
  - NOT_ADOPTEDが「最終判定待ち」→「取消待ち」→（自動取消後）一覧から消えるまでの流れ
  - MANUAL_REVIEW・LATE_ADOPTION・滞留判定・並び順
  - SQLとJavaのadmissionブロック判定の一致
- `OrderEditReauthorizationIntegrationTest`（追加2件、共有テストDBモード）：保留中のowned admissionが表示されること、完了したadmissionは表示されないこと
- `AdminOrderHoldControllerTest`（新規）：表示、空の一覧、DB障害時、未ログイン、管理者以外、POST不可
- `AdminControllerTest`・`AdminOrderShippingPreparationViewTest`：ダッシュボードの件数と取得失敗時の表示、注文詳細セクションの実テンプレート描画（エスケープを含む）

マイグレーションなし（最新はV84のまま）。
