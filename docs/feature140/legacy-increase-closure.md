# Feature140 従来の数量増加（Feature124）を閉じる

状態：140-A 実装・テスト完了（2026-11-24）。main 統合（66db97a）、開発 DB は V108、ブラウザ確認済み。基準は main eec40c8（最新マイグレーション V107）。

## 1. 目的

- 従来の数量増加（`/orders/{id}/increase`）で、新しい変更を受け付けないようにする。数量の変更は一括編集（Feature125）だけで行う。
- 過去にこの経路で受け付けた変更の表示・照合・回復は残す。
- 背景：content evidence は顧客が一括編集を始めたときに作られる。そのため、まだ編集していない注文は、新しい注文でも URL の直接入力でこの経路に入れる。この経路は Feature125 runtime の停止にも従わない。

以下、`order_change_operations.execution_version` が null の変更を「legacy の変更」と呼ぶ。

## 2. 決定事項（2026-11-24）

| # | 決定事項 |
|---|---|
| 1 | 案 a：新しい受付を止め、過去の変更の状態表示・照合・回復は残す。画面の撤去は今回行わない。 |
| 2 | 受注は削除しない。開発 DB の legacy の変更は 2 件で、どちらも COMPLETED。 |
| 3 | 環境は開発 DB だけ（本番・検証はない）。 |
| 4 | legacy の変更を作るテストは、Flyway target=107 の DB で行う。DB トリガーは無効にしない。 |
| 5 | RESERVED のまま残った legacy の変更は、新しい与信を始められない。取下げと期限切れの解放はできる。CREATE の記録済み（AUTHORIZING 以降）のものは最後まで進められる。 |
| 6 | `/increase` を直接開いたときは、注文詳細に移して案内する。 |
| 7 | `/orders/{id}/items`（別の旧い変更経路）は対象外。別途調査する。 |

## 3. 分け方

- **140-A**：新しい受付と新しい与信の停止（アプリと V108）。
- **140-B**：legacy の画面・候補を legacy の変更だけに限定する。
  - `/increase/{op}…` で一括編集の変更を開けない（404）。
  - `expireReserved` の候補から一括編集の変更を除く（解放できず、毎回 WARN になる）。
  - `AdminOldCancelRecoveryViewService` の対象から一括編集の変更を除く。

## 4. 140-A：データベース（V108）

`V108__close_legacy_order_increase.sql`。既存の行は変更しない。

| 表 | 契機 | 拒否するもの |
|---|---|---|
| order_change_operations | BEFORE INSERT | `execution_version` が null の行 |
| order_change_operations | BEFORE UPDATE | `execution_version` が null で、`new_payment_id` が null から値に変わるもの（与信の開始） |
| order_change_items | BEFORE INSERT | すべて（書くのは legacy の受付だけ） |
| inventory_reservations | BEFORE INSERT | すべて（同上。一括編集は order_edit_inventory_plans） |

- 関数は `f140_legacy_increase_guard` の 1 つ。違反は ERRCODE 23514。時刻は使わない。SET timezone は付けない。
- 一括編集の INSERT（V74・V75 の関数）は `execution_version=1` なので対象外。legacy から一括編集への書き換えは、既存の `f125_operation_guard` が拒否する。
- legacy の変更の状態遷移・取下げ・予約の RELEASED / CONSUMED への UPDATE は妨げない。
- 未完了の legacy の変更があっても、マイグレーションは失敗させない（回復の経路が残るため）。
- CREATE TRIGGER の表ロックにより、適用前に始まった受付のトランザクションは先に終わる。

## 5. 140-A：アプリ

### 5.1 受付

- `OrderIncreaseController` の GET `/increase`・POST `/increase/preview`・POST `/increase` は、何も読み書きせず `/orders/{id}` へリダイレクトし、flash の `errorMessage` に「数量の変更は、注文詳細の「注文内容を変更する」から行ってください。」を入れる。所有者の確認は注文詳細が行う（他人の注文は 404）。
- POST には引き続き CSRF が必要。セッションの見積もり（QUOTES）と入力フォームのバインドは削除する。
- `OrderIncreaseService` から `accept`・`acceptConfirmed`・`preview`・`input` と、その入出力の型を削除する。`status`・`abort`・`withdrawReserved`・期限切れの解放は残す。
- `OrderChangeStore` から `insert`・`item`・`held` と、使われなくなる `submission`・`hasContentEvidence` を削除する。
- `OrderItemChangeForm` は `/items` でも使うので残す。`ActivePaymentService.initializeForOrderChange` は PaymentBusinessConnectionPostgresTest が決済の結び付けを検証しているので残す。`InventoryService.reserveForOrderChange` は削除し、`releaseForOrderChange` は残す。
- `increase.html` から INPUT・CONFIRM を削除する。

### 5.2 与信の開始

- `OrderChangeBrowserService.start`・`continueChange` は、期限切れの解放のあとに状態を読み直し、RESERVED なら `advance` を呼ばずに終える（PAY.JP を呼ばない）。状態画面へ戻る。
- `OrderReauthorizationTransactions.prepare` は変えない（一括編集と `prepareLocked` を共用しているため）。legacy の `advance` を呼ぶのは `OrderChangeBrowserService` だけで、最後は V108 が拒否する。
- `increase.html` の状態画面：RESERVED では「カード認証を開始・確認する」を出さない。文言は「この変更の受付は終了しました。新しい決済は開始できません。候補を取り下げると、確保した追加在庫を解放します。」

### 5.3 変えないもの

- `increase-payment*.html`（一括編集と共用）、`OrderChangeBrowserService.Card`、OrderChangePaymentPrivacyFilter、`/increase/{op}`・withdraw・payment（GET・return・continue）の URL、注文詳細の「以前の数量変更の受付・結果を確認する」。
- 照合・Webhook・最終判定・不成立の取消・管理者の回復・変更履歴の検証。
- ロック順（Order が先、Payment は id 昇順）。

## 6. 140-A：テスト

### 6.1 legacy の変更を作るテスト

- `src/test` に `LegacyOrderIncreaseFixture` を作り、今の `accept` と同じ行（変更・商品・在庫の移動・予約）を JDBC で作る。実際の Order ロックと在庫の減算も行う。製品コードには残さない。
- 次のクラスは `spring.flyway.target=107` で動かす。閉じる前に受け付けた変更が残っている DB を再現する。V108 は表・列を変えないので、今のアプリのコードがそのまま動く。
  - OrderReauthorizationPostgresTest、OrderChangePaymentCustomerPostgresTest、OrderIncreasePostgresTest、OrderIncreaseCustomerPostgresTest、OrderChangeStartDeadlinePostgresTest、OrderChangeFinalDecisionPostgresTest
  - LegacyOrderChangeEvidenceFixture・LegacyRecoveredOrderChangeHistoryFixture を使う CustomerOrderChangeSavedHistoryWebPostgresTest、CustomerOrderChangeEvidencePostgresTest、CustomerLegacyOrderChangeHistoryVerifierPostgresTest
- WarehouseExportPostgresTest の `anUnresolvedOrderChangeHoldsTheOrder` だけを、target=107 の新しいクラスに移す。残りは最新版で動かす。
- 制約：target=107 のクラスでは、V109 以降の表・列に依存するコードを検証できない。将来このクラスが動かなくなったときは、画面の撤去（案 b）とあわせて見直す。
- target=107 と最新版を同じ DB で混ぜない。drive.sh の計画では 1 行に 1 クラスにする。

### 6.2 削除・変更するテスト

- OrderIncreasePostgresTest・OrderIncreaseCustomerPostgresTest の受付（見積もり・受付・再送・競合・在庫不足など）のテストは、機能がなくなるので削除する。解放・期限切れ・取下げ・状態表示のテストは残す。
- OrderIncreasePolicyTest の要求の digest、OrderIncreaseModelCEvidencePostgresTest の受付は削除し、一括編集が続けて使えることの検証は残す。
- OrderEditAdmissionGuardPostgresTest の `reauthorize` は、残る入口（`OrderReauthorizationTransactions.prepare`）がすでに別のテストで検証されていることを確かめてから外す。なければ置き換える。
- 削除したテストは次のとおり。
  - OrderIncreasePostgresTest：reservesOnlyIncreaseAndKeepsOriginalOrder、replayDoesNotReserveAgainAndChangedRequestConflicts、stockFailureRollsBackAllWrites、unsupportedChangesAndOtherCustomersAreRejected、concurrentReplayHasOneReservation、twoDifferentSubmissionsCannotBothReserve、cancellationWinningOrderLockPreventsCandidate、productsLockAscendingAndLaterShortageRollsBackEarlierReservation、finalAdmissionCheckRejectsDeadlineReachedWhileWaitingForProductLocks、quantityIncreaseCanLowerTotalByCrossingFreeShippingThreshold、shippingPreparationWaitsForCandidateAndThenIsRejected（releaseIsIdempotentAndAllowsNewSubmission は、新しい受付の部分を外して releaseIsIdempotentAndClearsTheUnresolvedChange に変更）
  - OrderIncreaseCustomerPostgresTest：inputIsOwnedAndNeverCallsProvider、confirmedServerQuoteIsIdempotentAndDoesNotChangeOriginalOrder、csrfAndConfirmationOwnershipAreRequired、invalidQuantityIsRejected、foreignOrDuplicateItemsAreRejected、acceptanceRechecksDeadlineAndPreparationAfterPreview、changedServerPricingRequiresNewConfirmation、sparseFormIndexIsRejectedWithoutReservation
  - OrderIncreasePolicyTest：requestIsImmutableAndDigestIgnoresMapIterationOrder
  - OrderIncreaseModelCEvidencePostgresTest：クラスごと（enrolledOrdersRejectBothAcceptEntrancesWithoutAnySavedSideEffect、legacyOrderStillAcceptsAndReplaysOneReservation、rejectionDoesNotBlockTheNewBatchPath）
- 当初の設計との差：OrderIncreaseModelCEvidencePostgresTest は、一括編集の検証も含めて削除した。「legacy の受付が拒否されたあと」という前提がなくなり、一括編集が使えることは最新版の OrderEditReauthorizationIntegrationTest が検証している。

### 6.3 新しいテスト

1. V108（最新版の DB）：legacy の変更、order_change_items、inventory_reservations の INSERT を拒否する。一括編集の RETAIN_AUTH・REAUTHORIZE は作れる。
2. V107 で legacy の変更を作ってから V108 を適用する：
   - 既存の行は変わらない。
   - RESERVED：与信の開始を拒否し、取下げと解放はできる。
   - AUTHORIZING 以降：照合・完了・取下げが最後まで進む。
3. 3 つの受付 URL は、リダイレクトするだけで DB も PAY.JP も触らない。所有者でない・CSRF がない場合も確認する。
4. `start`・`continueChange` は、RESERVED で PAY.JP を呼ばない。
5. 過去の完了した変更の状態画面と変更履歴が表示できる。

### 6.4 そのほか

- 最新版数の 3 テスト（IsolatedTestSmokeTest、OrderShippingPreparationPostgresTest、AdminOrderShippingPreparationSecurityPostgresTest）を 108 にする。
- 回帰：一括編集（再与信・金額の範囲内・登録カード・不成立の取消）、Webhook・照合、倉庫出力、管理画面の保留一覧、注文詳細、プライバシーフィルター、JS テスト。関連する @WebMvcTest も実行する。

### 6.5 結果（2026-11-24）

- 77 クラス・1,993 件：失敗 0、エラー 0、スキップ 0。JS テスト 5 本も成功。
- 初回に失敗した新規テスト 2 クラスは、どちらもテスト側の誤り（ログイン URL の期待値、Flyway の構成）で、直して成功した。
- 6.3 の担当：
  - 1 の拒否と 3 は LegacyIncreaseClosedPostgresTest。1 の一括編集は OrderEdit 系の既存テスト（最新版の DB）。
  - 2 は LegacyIncreaseClosureUpgradePostgresTest。
  - 4 は OrderChangePaymentCustomerPostgresTest（target=107）。
  - 5 の状態の読み取りは LegacyIncreaseClosureUpgradePostgresTest。画面と変更履歴は target=107 のクラス。
- V108 適用後の DB では確かめていないもの：
  - AUTHORIZING 以降の取下げは、受理まで。取消の完了は target=107 で検証している。
  - 完了した変更の画面と変更履歴の HTML。ブラウザで確認する。

## 7. 適用と確認

1. 実装とテスト（承認後）。main に統合する前に結果を報告する。
2. `./start-dev.sh` で V108 を開発 DB に適用する（実行前に確認する）。PostgreSQL は再起動しないので、runtime の再承認は要らない。
3. ブラウザ確認：`/increase` の直接入力の案内、注文 1・3 の状態画面と変更履歴、通常の一括編集の画面。PAY.JP を呼ぶ操作は行う前に確認する。

- ブラウザ確認（2026-11-24）：未ログイン・所有者・他人での `/increase` の直接入力、注文 1・3 の状態画面と変更履歴、注文 31 の一括編集の開始。すべて問題なし。

- 戻すとき：V108 は削除せず、新しいマイグレーションで外す。

## 8. 対象外

- 画面・回復コードの撤去（案 b）、`/orders/{id}/items`、注文 8・17 の一括編集の MANUAL_REVIEW。

## 9. 140-B：legacy の画面・候補を legacy の変更に限定する

状態：完了（2026-11-24）。main 統合（7a08d71）、ブラウザ確認済み。基準は main 66db97a。マイグレーションはなし（V108 のまま）。

### 9.1 現状

- `OrderIncreaseService.status` は注文・所有者・変更 ID だけを確かめる。そのため、顧客は自分の一括編集の変更 ID を使い、legacy の状態画面・取下げ・決済の URL を開ける。`observe` は PAY.JP の照会まで進む。
- `expiredReserved` は execution_version を見ない。候補になった一括編集の変更には予約がなく、解放できない。発生すると、照合のたびに WARN が出る。
- `AdminOldCancelRecoveryViewService` は一括編集の変更も対象にする。実行は `rejectOwnedLegacy` が拒否するので、使えない案内だけが出る。

### 9.2 変更

| 場所 | 変更 |
|---|---|
| `OrderChangeStore.legacyOperation`（新規） | `operation` に `execution_version is null` を加えたもの。`OrderIncreaseService`（status・abort・withdrawReserved・expireReserved）はこちらを使う。`operation` は一括編集と共用の完了処理（OrderReauthorizationTransactions）でも使うので変えない |
| `OrderChangeStore.expiredReserved` | `o.execution_version is null` を条件に加える |
| `AdminOldCancelRecoveryViewService.targets` | 同じ条件を加える |

- 顧客の legacy の URL（状態、取下げの GET・POST、決済の 4 つ）は、どれも最初に `status` を呼ぶ。一括編集の変更では OrderNotFoundException（404）になり、PAY.JP を呼ばない。
- 管理者の回復画面も 404 になり、注文詳細の案内にも出ない。
- 判定に `f125_reauth_is_owned` は使わない。RETAIN_AUTH の変更は owned にならず、一括編集なのに対象に残るため。
- 変えないもの：`completedOperationIds`・`abortedOperationIds`・`unresolved`（一括編集を含める必要がある）、回復の実行側の拒否、ロック順。

### 9.3 テスト

1. 最新版の DB で一括編集の変更（REAUTHORIZE、RETAIN_AUTH）を作る。legacy の URL は、所有者でもすべて 404。PAY.JP を呼ばず、DB も変わらない。
2. 一括編集の変更は期限切れの候補に出ない。legacy の変更は出る（target=107 の既存テスト）。
3. 回復条件を満たす一括編集の変更は、案内にも回復画面にも出ない。legacy の回復は動く（target=107 の既存テスト）。
4. 回帰：legacy の表示・取下げ・期限切れ・回復、一括編集の再与信、照合スケジューラー、管理画面の注文詳細。

### 9.4 適用と確認

- main に統合する前に結果を報告する。
- ブラウザ確認：注文 8・17（MANUAL_REVIEW の一括編集）の変更 ID で legacy の URL を開くと 404。注文 1・3 の legacy の状態画面は表示される。PAY.JP は呼ばない。

### 9.5 結果（2026-11-24）

- 63 クラス・1,410 件：失敗 0、エラー 0、スキップ 0。
- 新しいテストは LegacyChangeScopePostgresTest（最新版の DB、4 件）。修正を外すと 4 件とも失敗することを確かめた。
- 期限切れの候補は、RETAIN_AUTH の RESERVED を記録したトランザクションの中で確かめ、ロールバックした。
- 画面の JS は変えていないので、JS テストは再実行していない。
- ブラウザ確認（2026-11-24）：注文 8・17 の一括編集の変更は、従来の状態画面で 404。注文 1・3 の従来の状態画面は表示される。すべて問題なし。
