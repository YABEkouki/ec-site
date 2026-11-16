# Feature129 不採用（NOT_ADOPTED）確定後に遅延成功した新与信の自動取消

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

## 対象外（Feature129-C）

管理者向けの未解決一覧・滞留可視化は別Feature。

---

# Feature129-B 一括編集（owned）経路の同等対応

## 対象

Feature128 A-2 の14:05最終判定で `order_change_final_decisions.outcome='NOT_ADOPTED'` と確定した**一括編集（order-edit／owned）**の注文変更operationのうち、state が `ABORTED`／`COMPLETED`／`MANUAL_REVIEW` 以外のもの。

## 129-Aとの違い：新しい取消パイプラインも新マイグレーションも不要

`OrderReauthorizationTransactions.requestAbort` はowned操作を`owned(data)`チェックで`NOT_ALLOWED`として拒否するため、129-Aの「SYSTEM `requestAbort`」方式はownedには使えない。しかし調査の結果、ownedには**すでに同等の仕組みが揃っている**ことが分かった：

- `decideLocked`（`decide`/`decideForOrderEdit`どちらから呼ばれても同一ロジック）は、`expired(data)||switchClosed(data)`（owned/非ownedで共通）なら新与信の取消（inline `prepareAbort`）へ進む。`switchClosed`は`finalDecided(operationId)`が真なら常にtrueなので、14:05の最終判定さえ記録されていれば、`decideForOrderEdit`を呼ぶだけで自動的に取消に進む。
- `OrderEditExecutionService.reconcile(admissionId)` は、既存のadmission（`RECOVERY_PENDING`／`REVIEW_REQUIRED`からの再開、リース失効の検知・フェンシング、バックオフ待ち）を内包したうえで最終的に`execute()`（→`advanceForOrderEdit`→`decideForOrderEdit`）を呼ぶ、**毎tick呼んでも安全な**自己完結した再試行エントリポイントとして既に存在する。
- Feature125のruntime承認（既定STOPPED）はDB関数側（`f125_execution_*`系、SQLSTATE 55000）で強制されており、Java側 `OrderEditExecutionCoordinator.write()` がこれを`Outcome.RUNTIME_UNAVAILABLE`→`Status.BLOCKED`に正規化して握りつぶす。つまり**runtimeがSTOPPEDの間、`reconcile()`を呼んでも何も起きず安全**。運用者がRUNNINGを承認した瞬間から自動的に機能し始める。129-Aと同じ`app.payment.reconciliation.enabled`フラグを共有してよい。
- 在庫予約の解放はowned/非ownedで経路が分かれている（`OrderReauthorizationTransactions.release`が`owned(data)`なら`OrderEditReauthorizationBridge.release`、それ以外なら`inventory.releaseForOrderChange`）。これは既存の分岐で、Feature129-Bのために新設するものではない。
- Capture防止（`OrderPaymentGuard.requireNoOrderChange`）はowned/非ownedを区別せず効く。

これにより、`OrderEditNotAdoptedReconciliationService.reconcileDue(limit)` は新規候補・再試行候補を分けず、対象admissionに対して**`OrderEditExecutionService.reconcile(admissionId)`を呼ぶだけ**のシンプルな実装になる。

候補クエリ（`OrderEditApplicationTransactions.reauthorizationOperation`と同じJOINパターン）：
```sql
select a.id from order_edit_admissions a
join order_change_operations o on o.order_id=a.order_id and o.submission_key=a.submission_key
join order_change_final_decisions d on d.operation_id=o.id
where a.route='REAUTHORIZE' and o.execution_version=1 and o.execution_route='REAUTHORIZE'
  and d.outcome='NOT_ADOPTED' and o.state not in ('ABORTED','COMPLETED','MANUAL_REVIEW')
order by d.final_at,a.order_id limit ?
```

## 実行者・スケジューラ

`OrderEditNotAdoptedReconciliationScheduler` は129-A・Feature128の各スケジューラと同じ `app.payment.reconciliation.enabled` フラグ・`fixed-delay` を共有する。

## DB変更

なし。既存の`decideForOrderEdit`／`OrderEditExecutionService.reconcile`をそのまま呼ぶだけ。

## テストの既知の制約（重要）

owned経路の注文は、formal acceptance以降の業務状態（`orders.change_deadline_at`を含む）が **evidence（証拠）として不変** であり、`f125_reauth_assert_current`（owned reauth操作のガード）と`JdbcOrderPaymentEvidenceReader`（evidence読み取り自体）の両方が、admission受理後に`orders`行を直接書き換えると「Unexpected business or financial change outside owned reauthorization」／「Incomplete or inconsistent saved evidence」で拒否する。

このため、129-Aのテスト（`atDeadlinePlus`相当で締切を事後に過去へ書き換える）と同じ手法はownedには使えない。かつ、既存のテストfixture（`OrderEditAdmissionServicePostgresTest.fixture(boolean past)`）は締切を**±2日**の二値でしか用意できず、「締切に近い将来」を指定する手段が無い。`past=true`（締切が最初から過去）を使うと、Feature128 A-1の締切ガードにより**CREATE送信自体が即座にMANUAL_REVIEW化**されてしまい、今回検証したい「AUTHORIZING状態のまま締切が後から来る」シナリオを再現できない。

実時間で締切＋5分（`FINAL_DECISION_MINUTES`は変更不可の定数）を待つ方法は理論上可能だが、2日先の締切を使う限り現実的でない。このため、**owned経路について「締切が実際に経過してNOT_ADOPTED確定→自動取消」までを通す統合テストは今回追加していない**。代わりに以下で検証している：

- `OrderEditNotAdoptedReconciliationAutomationTest`（新規）：スケジューラがサービスにのみ委譲し、サービス側の例外を握りつぶすこと。
- `OrderEditReauthorizationIntegrationTest.reconciliationLeavesOrdinaryInFlightAdmissionsAlone`（新規）：まだ最終判定が存在しない通常の進行中admission（129-Bの候補クエリの主要な「除外すべき」ケース）に対して`reconcileDue`が何もしないこと。
- 候補クエリ自体は`reauthorizationOperation`と同じJOINパターンであることをコードレビューで確認済み。
- `decideForOrderEdit`のswitchClosed処理、`OrderEditExecutionService.reconcile`の再開・バックオフ機構は、本Feature129-Bの実装前から`OrderEditReauthorizationIntegrationTest`の既存20件で広く検証されている（新規に書いたコードではない）。

将来、この統合テストを追加する場合は、`OrderEditAdmissionServicePostgresTest.fixture`に締切オフセットを指定できる変種を追加する（既存20件以上のテストに影響する変更のため、別Featureとして慎重に行う）か、実時間で5分以上待つテストを許容するかの判断が必要。
