# Feature129-A 不採用（NOT_ADOPTED）確定後に遅延成功した新与信の自動取消

設計資料：[docs/future/warehouse-shipping-design.md](../future/warehouse-shipping-design.md) 7章
前提：[Feature128 A-2 14:04切替締切と14:05最終判定](../feature128/final-decision.md)

## 対象（非owned／顧客自己操作経路のみ）

Feature128 A-2 の14:05最終判定で `order_change_final_decisions.outcome='NOT_ADOPTED'` と確定した注文変更operationのうち、state が `RESERVED`／`AUTHORIZING`／`REQUIRES_ACTION`／`AUTH_UNKNOWN`／`AUTHORIZED` のもの。`MANUAL_REVIEW` は対象外（人間の判断が前提）。一括編集（owned）経路はFeature129-Bで別途扱う。

## 既存の欠落（調査で判明）

- Webhook・照合バッチが呼ぶ `OrderReauthorizationService.synchronize()` と、管理者向け `reconcile()` は、観測結果をGETで記録するだけで `decide`／与信取消の開始（`prepareAbort`）を一切呼ばない。
- 与信取消を開始・完結まで進める唯一の経路は、顧客の「注文変更を取り下げる」操作（`OrderChangeWithdrawalService.withdraw`）。
- そのため、14:05にNOT_ADOPTEDと確定した後、新与信がAUTHORIZING/REQUIRES_ACTION/AUTHORIZEDのまま顧客が二度とページを開かなければ、取消も在庫予約の解放も永久に起きない。
- `OrderPaymentGuard.requireNoOrderChange` が未解決なorder change operationがある限りCapture・出荷・新たな注文変更の開始を全てブロックしているため、不要な与信がCaptureされる危険は無い。問題は「出荷が無期限に止まる」こと。

## 設計：既存のSYSTEM要求パイプラインに1回だけ入り口を提供する

`AbortRequesterType` には元から `SYSTEM` が用意されていた。`OrderChangeNotAdoptedCancellationService` は、NOT_ADOPTED確定済みの対象operationに対して `requestAbort`（`AbortRequesterType.SYSTEM` / 新設の `AbortReasonCode.CHANGE_NOT_ADOPTED`）を1回発行するだけで、新規の取消ロジックは書かない。

ただし、`NewFlowClosureReconciliationService` は「取消POSTが送信済みで結果が判明した後」の**決済終結の記録（簿記）専用**であり、実際の準備→取消POST送信→観測→完了（`prepareTrustedAbort`→`RequestedAbortDispatchControl`→`NewFlowClosureControl`→`RequestedAbortCompletionControl`）は、従来 `OrderChangeWithdrawalService.finishIfProven`（顧客の取り下げ操作）からしか呼ばれていなかった。SYSTEM起点にはこれを呼ぶ顧客がいないため、このオーケストレーション部分を package-private な共有クラス `RequestedAbortProgression.advance(orderId,customerId,operationId)` として切り出し、`OrderChangeWithdrawalService` と新サービスの両方から呼ぶ。

`OrderChangeNotAdoptedCancellationService.cancelDue(limit)` は毎回2種類の候補を処理する：

1. **新規候補**：`order_change_final_decisions.outcome='NOT_ADOPTED'` かつ `abort_requested_at is null` かつ `switch_decided_at is null` かつ対象state → `requestAbort`（SYSTEM）→ 成功したら即 `RequestedAbortProgression.advance` を1回試す。
2. **再試行候補**：`abort_requester_type='SYSTEM'` かつ `abort_reason_code='CHANGE_NOT_ADOPTED'` かつ state が `ABORTED`／`COMPLETED`／`MANUAL_REVIEW` 以外 → 顧客が「もう一度取り下げボタンを押す」ことがSYSTEM起点には無いため、スケジューラ自身が毎tick `advance` を再試行する。

取消POSTの結果が不明（タイムアウト等）でも、取消POSTを再送することは無い（既存の `RequestedAbortDispatchControl.send` の方針どおり）。再試行候補の次回GET（closure観測）が、取消が実際には成立していたかを安全に確認する。

一括編集（owned）operationは `requestAbort` 内の既存ガード（`owned(data)` なら `NOT_ALLOWED`）で自然に除外されるが、候補クエリ自体でも `f125_reauth_is_owned(order_id,id)` で除外し、無駄な試行を避ける。

## 実行者・スケジューラ

`OrderChangeNotAdoptedCancellationScheduler` は既存の照合系スケジューラ（`OrderChangeFinalDecisionScheduler`、`NewFlowClosureScheduler`）と同じ `app.payment.reconciliation.enabled` フラグ・同じ `fixed-delay` を共有する。

## DB変更（V84）

`abort_reason_code` のCHECK制約（`order_change_operations.ck_change_abort_intent`）に新しい値 `CHANGE_NOT_ADOPTED` を追加。この制約はV62で作られた後V63・V67で条件が拡張されているため、V84はV67時点の完全な条件（`content_change_history_id is null`、state一覧に`ABORTING`/`ABORT_UNKNOWN`/`ABORTED`を含む）を基に、新しい値だけを足して再定義する。

Java側では `AbortReasonCode` enum と、`NewFlowClosureObservation.AbortIntent` のコンストラクタ内チェック（DBとは独立した同値の許可リスト）の両方に新しい値を追加する必要がある（追加当初、後者を見落として全テストスイートが壊れた。両方を揃えて変更すること）。

## テスト

`OrderReauthorizationPostgresTest` に追加（既存の `mockWithdrawalTransport` 等の顧客取り下げ用フィクスチャをそのまま再利用）：

- `notAdoptedAuthorizedOperationIsCancelledAutomaticallyWithoutAnyCustomerAction`：AUTHORIZED×NOT_ADOPTED確定後、顧客操作なしで1回の `cancelDue` 呼び出しだけで取消・在庫解放まで完了すること。2回目の呼び出しは完全な無操作（`verifyNoInteractions(gateway)`）。
- `notAdoptedCancellationSurvivesAnUnknownPostResultAndNeverSendsTwice`：取消POSTの応答が失われても、同一run内のGETで成立を検出し、POSTを二度と送らないこと。
- `notAdoptedCancellationLeavesManualReviewAndLateAdoptionAlone`：MANUAL_REVIEW、LATE_ADOPTION（切替決定済み）はどちらも対象外のまま。

`OrderChangeNotAdoptedCancellationAutomationTest`（新規）：スケジューラがサービスにのみ委譲し、サービス側の例外を握りつぶすこと。

## 対象外（Feature129-B / 129-C）

一括編集（owned）経路の自動取消はFeature125のruntime承認・workerのownershipと統合する別設計が必要（現状ownedワーカー自体が本番で誰にも起動されていない）。管理者向けの未解決一覧・滞留可視化も別途。
