# Feature128 A-2 注文変更の14:04切替締切と14:05最終判定

設計資料：[docs/future/warehouse-shipping-design.md](../future/warehouse-shipping-design.md) 6.3章、10章、12章4〜5
前提：[A-1 14:00 再与信開始ガード](start-deadline-guard.md)

## 時刻（すべて `orders.change_deadline_at` から導出。DB時刻で判定）

| 時刻 | 意味 |
|---|---|
| 期限（14:00） | 再与信の開始締切（A-1） |
| 期限＋4分（14:04） | 旧与信取消（注文切替）の決定締切 |
| 期限＋5分（14:05） | 最終判定 |

`grace_until`（期限＋10分）、`automatic_until`、既存行、Feature125の証跡は変更・再計算しない。14:04/14:05 は既存の grace より前なので、`LegacyReauthorizationVerifier`（切替決定 ≤ grace_until）とも矛盾しない。

## 判定ルール

- 変更が**成立**するのは、operation が `COMPLETED` になった場合だけ。
- 14:04以降は旧与信取消を決定しない。新与信が確認されても注文は切り替えず、不要になった新与信の取消（既存の `prepareAbort` → NEW_ABORT）に進む。アプリ（`decideLocked`）とDBトリガー（V83、行更新時＋コミット時）で二重に強制する。
- 14:05時点で未決着（COMPLETED/ABORTED 以外）の operation には、追記専用の最終判定を一度だけ記録する。

| 14:05時点の状態 | outcome | 出荷の扱い | reason_code | その後 |
|---|---|---|---|---|
| RESERVED（従来経路、金融処理なし） | NOT_ADOPTED | ORIGINAL | NO_REAUTHORIZATION_STARTED | 同一Txで在庫予約を解放しABORTED（A-1の締切証跡も記録） |
| RESERVED（一括編集経路など、即時解放できない） | NOT_ADOPTED | HOLD | RESERVATION_UNRELEASED | 一括編集の既存の失効・クローズ処理 |
| AUTHORIZING / REQUIRES_ACTION | NOT_ADOPTED | HOLD | AUTHORIZATION_INCOMPLETE | 後から成功しても切替せず新与信を取消 |
| AUTHORIZED（切替未決定） | NOT_ADOPTED | HOLD | SWITCH_NOT_DECIDED | 同上 |
| その他（AUTH_UNKNOWN、MANUAL_REVIEW、ABORTING 等） | NOT_ADOPTED | HOLD | PAYMENT_STATE_UNRESOLVED | 照合・管理者対応 |
| 切替決定済み（OLD_CANCEL_*、COMMIT_PENDING 等） | LATE_ADOPTION | HOLD | SWITCH_INCOMPLETE | **前進のみ**。Webhook・照合・顧客操作・管理者の旧与信取消復旧で完了させる |

最終判定は14:05の状態のスナップショット。HOLD の注文がその後 ABORTED（金融処理も決着）や COMPLETED になったかは operation の現在状態で判断する（倉庫出力は後続Feature）。

## 運用方針（合意済み）

- 遅延成立（LATE_ADOPTION）は旧与信が取消済みの可能性があるため元注文に戻さない。自動解消を待ち、翌日の倉庫出力時に再判定する。完了していれば変更後の内容で出荷、未完了なら保留を続ける。
- システムで解消できないもの（取消拒否、照会不能の継続、新与信が無効、切替のDB処理失敗など）は管理者が手動で解消する。
- 14:05を過ぎて未決着の既存 operation も、境界を設けず判定対象にする（開発DBで該当2件を確認済み）。

## 実行者・トランザクション・ロック順

- `OrderChangeFinalDecisionScheduler`：照合バッチと同じ有効化フラグ・間隔（`app.payment.reconciliation.enabled` / `fixed-delay`、既定1分）。
- `OrderChangeFinalDecisionService.finalizeDue`：候補を取得し、1件ずつ独立した短いトランザクション（`OrderReauthorizationTransactions.finalizeAtDeadline`）で判定する。外部APIは呼ばない。失敗した候補は次回に再試行する。
- ロック順は既存の `lock()` と同じ：（一括編集なら canonical lock →）Order `FOR UPDATE` → operation `FOR UPDATE` → Payment id昇順。時刻はロック後のDB時刻。
- 冪等性：主キーが operation_id。結果は DECIDED / ALREADY_DECIDED / NOT_DUE / SETTLED。
- 競合：Webhook・照合・顧客の3DS復帰による切替決定は同じ Order ロックで直列化され、14:04の締切と「判定あり」の両方で拒否される。スケジューラが14:05ちょうどに動かなくても結果は変わらない。14:00以降は開始できず（A-1）、14:04以降は切替できないため、14:05以降に成立し得るのは切替決定済み（遅延成立）だけである。

## DB（V83）

- `order_change_final_decisions`：追記専用。INSERTトリガーで期限の一致、DB時刻 ≥ 期限＋5分、operation の状態・version・切替時刻の一致、未決着であることを検証し、`final_at`・`decided_at` をDB側で設定する。ORIGINAL は金融処理がない RESERVED のみ許可。
- `f128_switch_decision_cutoff`（BEFORE）／`f128_switch_decision_cutoff_commit`（DEFERRED）：`switch_decided_at` の設定は DB時刻 < 期限＋4分 かつ 判定なしの場合のみ。SQLSTATE `EC128`。

## 顧客表示

- NOT_ADOPTED（決着済み）：変更不成立、変更前の内容のまま。
- NOT_ADOPTED（決済状態の確認待ち）：変更不成立、決済状態の確認後に発送準備。
- LATE_ADOPTION（未完了）：決済状態の確認が完了次第、変更後の内容で発送準備。

## 対象外（後続Feature）

- 14:05以降に遅延成功した新与信の取消を自動で進めること（設計資料7章）。現状は顧客・worker の次回処理で `prepareAbort` に進む。Webhook・照合は結果の記録のみ。
- 倉庫出力・出荷保留の解除（4章・8章）、管理者向けの保留一覧と通知、顧客通知（9章）、休日・非稼働日。
- スケジューラが ORIGINAL で解放できるのは従来経路のみ。一括編集経路の RESERVED は HOLD として記録し、既存の失効処理に委ねる。
