# Feature141 従来の商品変更（/orders/{id}/items）を閉じる

状態：141-A は main に統合済み（d62f249、2026-11-24）。V109 を開発 DB に適用し、ブラウザで確認済み。141-B も main に統合済み（0b0b92f、2026-11-25）で、ブラウザで確認済み。Feature141 は完了。141-A の基準は main a098936（V108）。

## 1. 目的

- `/orders/{id}/items`（数量の減少・明細の削除）で、注文を変更できないようにする。商品の変更は一括編集（Feature125）だけで行う。
- 注文内容とカード与信が一致しない注文のキャンセルを、PAY.JP に取消を送る前に拒否する。

背景（調査で、最新版の DB とモック gateway を使って確認した）：

- この経路は変更後の合計を与信額と比べない。商品合計が 5,000 円を下回ると送料 550 円が付き、合計が与信額以上になることがある。未編集の注文（evidence なし）では、DB トリガーも止めない。
- その注文は発送準備・売上確定ができない。キャンセルすると PAY.JP の取消は送信されるが、結果の反映が CONFLICT になり、注文 ORDERED・決済 AUTHORIZED・CANCEL PENDING のまま残る。再操作でも解消しない。
- Feature125 runtime が STOPPED でも変更できる。
- evidence 登録済みの注文では、確定の POST がコミット時に拒否され、500 になる（ロールバックされる）。
- キャンセル確認画面に、この経路へのリンクが残っている。

## 2. 決定事項（2026-11-24）

| # | 決定事項 |
|---|---|
| 1 | 案 A：経路を閉じる。この経路の変更は開発 DB に 0 件なので、回復画面は残さず、サービスと画面を削除する。 |
| 2 | 141-B（取消の送信前の確認）も行う。 |
| 3 | キャンセル確認画面の「注文内容を変更する」は、注文詳細への案内に替える。 |
| 4 | 配送先変更は対象外（金額・在庫・版番号を変えず、evidence の snapshot にも含まれない）。 |

閉じると、runtime の停止中と、有効なカード与信のない注文では、顧客は数量を減らせない（条件を満たせばキャンセルはできる）。

## 3. 分け方

- **141-A**：経路を閉じる（アプリと V109）。
- **141-B**：取消の送信前の確認（アプリだけ、マイグレーションなし）。141-A の統合後に、別のブランチで行う。

## 4. 141-A：データベース（V109）

`V109__close_legacy_order_item_change.sql`。既存の行と既存の関数は変更しない。

| 表 | 契機 | 拒否するもの |
|---|---|---|
| stock_movements | BEFORE INSERT OR UPDATE | `movement_type='ORDER_ITEM_CHANGE'` の INSERT と、その値への UPDATE |
| order_content_change_histories | AFTER INSERT、DEFERRABLE INITIALLY DEFERRED（制約トリガー） | コミット時に、同じ注文の `order_change_operations.content_change_history_id` から参照されていない履歴 |

- 関数は `f141_legacy_item_change_guard` の 1 つ。違反は ERRCODE 23514。時刻は使わない。SET timezone は付けない。
- 正当な 2 つの経路（一括編集、legacy の数量増加の完了）は、同じトランザクションで履歴を operation に結び付けるので対象外。履歴を先に INSERT するため、判定はコミット時に行う。
- 注文・明細の UPDATE を直接しばるトリガーは加えない。evidence なしの注文の金額を与信額と比べる不変条件は、この Feature では加えない（書き込む経路がなくなるため）。
- 過去の `ORDER_ITEM_CHANGE` の行があってもマイグレーションは失敗しない。表示も変えない。

## 5. 141-A：アプリ

### 5.1 入口

- GET `/items`、POST `/items/confirm`・`/items/back`・`/items` は、何も読み書きせず `/orders/{id}` へリダイレクトし、flash の `errorMessage` に「注文内容の変更は、注文詳細の「注文内容を変更する」から行ってください。」を入れる。所有者の確認は注文詳細が行う（他人の注文は 404）。POST には引き続き CSRF が必要。フォームはバインドしない。
- 注文詳細の「注文内容を変更する」は、期限内だけ表示される（今と同じ）。期限後は、注文詳細の受付終了の案内が出る。

### 5.2 キャンセル確認画面

- 「注文内容を変更する」のリンクを削除し、操作ボタンの上に「商品や数量を変更する場合は、注文詳細の「注文内容を変更する」から行ってください。」を表示する。
- 注文詳細へのリンクは、すでにある「注文詳細に戻る」を使う（同じ行き先のボタンを 2 つ並べない）。

### 5.3 削除するもの

- `OrderService.changeItemsForUser`・`previewItemChangeForUser`・`canChangeItemsByUser` と、そこだけで使う private メソッド。
- `OrderItemChangeForm`、`OrderItemChangePreview`、`item-change.html`、`item-change-confirm.html`。
- `InventoryService.restoreForOrderItemChange`、`OrderPaymentGuard.validateItemChange`、`Order.canChangeItemsByUser`。
- 注文詳細の model 属性 `canChangeItemsByUser`（画面では使われていない）。

`Order`・`OrderItem` の金額・数量の変更メソッド、`incrementContentRevision`、変更履歴のエンティティは、legacy の数量増加の完了などで使うので残す。削除の前に、それぞれの利用箇所を確認し、ほかで使うものは残す。

### 5.4 変えないもの

- `StockMovement.createOrderItemChange`（legacy の在庫移動の生成で使う。直接の呼び出しはなくなるので private にする）、`StockMovementType.ORDER_ITEM_CHANGE` と在庫履歴の表示。
- `LegacyOrderChangeVerifier`（過去の減少の変更の検証・表示）。
- 一括編集、配送先変更、キャンセル、ロック順。

## 6. 141-A：テスト

### 6.1 既存テストの移行

`src/test` に `LegacyItemDecreaseFixture` を作る。用途は 2 つで、どちらも Order ロックの下で行う。製品コードには残さない。

- **書き込み一式**：今の `changeItemsForUser` と同じ行（注文・明細・送料・在庫・在庫移動・変更履歴）を作る。V108 以前の DB だけで使える。
- **注文内容だけ**：明細・送料・金額・版番号だけを変える。「閉じる前に減額され、evidence のない注文」の売上確定などを、最新版の DB で検証するために使う。変更履歴と在庫移動は作らない。

| クラス | 移行 |
|---|---|
| CustomerOrderChangeSavedHistory（Fixture・Web）、CustomerOrderChangeEvidence、CustomerLegacyOrderChangeHistoryVerifier、OrderIncreasePostgresTest（すべて target=107 のまま） | 書き込み一式に置き換える。OrderIncreasePostgresTest の `validateItemChange` は `requireNoOrderChange` に置き換える |
| OrderToCaptureJourney、OrderPaymentGuard、PaymentResultApplication、PaymentSynchronization、OrderShippingPreparation（最新版の DB のまま） | 減額後の売上確定・ロック待ちの検証は「注文内容だけ」に置き換える。従来の減少そのものの検証（拒否・在庫の戻しなど）は削除する |
| CustomerOrderNotificationOutbox | ITEMS_CHANGED の内容の検証は、書き込み一式を使う target=108 の新しいクラスに移す。一括編集の ITEMS_CHANGED は OrderEditShipmentIntegrationTest が最新版の DB で検証している |
| OrderModificationGuard、OrderEditAdmissionGuard | 従来の減少の行だけを外す。配送先・キャンセル・発送準備の行は残す |
| OrderServiceTest、OrderControllerTest、OrderModificationPolicyTest、OrderFormalAcceptanceTest | 削除した機能のテストを外し、案内だけのテストに替える |

- 期限・発送準備・受付中の拒否は、残る経路（配送先変更・キャンセル・一括編集）のテストがあることを確かめてから外す。なければ追加する。
- 削除したテストは、実装後にこの文書へ一覧で残す。
- target=107／108 と最新版を同じ DB で混ぜない。計画は 1 行に 1 クラス。

### 6.2 新しいテスト

1. 4 つの URL（最新版の DB・実画面）：evidence なし・登録済み・受付中の注文で、注文詳細へのリダイレクトだけ。DB は変わらず、PAY.JP を呼ばない。未ログイン、CSRF なし（403）も確認する。他人の注文は、リダイレクト先で 404。
2. キャンセル確認画面に `/items` へのリンクがなく、案内が表示される。
3. V109：
   - `ORDER_ITEM_CHANGE` の INSERT と UPDATE を拒否する。
   - operation のない履歴、別の注文の operation に結び付いた履歴を、コミット時に拒否する。
   - 一括編集（RETAIN_AUTH・REAUTHORIZE）は完了する。
4. V108 で従来の減少を作ってから V109 を適用する：既存の行は変わらず、変更履歴を表示できる。legacy の数量増加（REAUTHORIZE 済み・未完了）も、V109 の適用後に完了まで進む。
5. 新しいテストは、修正（トリガー・コントローラー）を外すと失敗することを確かめる。

### 6.3 そのほか

- 最新版数の 3 テスト（IsolatedTestSmokeTest、OrderShippingPreparationPostgresTest、AdminOrderShippingPreparationSecurityPostgresTest）を 109 にする。
- 回帰の計画は、変更したクラス・表（stock_movements、order_content_change_histories）を使うテストを grep で洗い出して作る。Webhook・照合、倉庫出力、顧客メール、変更履歴、@WebMvcTest を含める。

## 7. 141-B：取消の送信前の確認

### 7.1 現状

`PaymentService.applyCancellationResult` は、`PaymentPurposeResolver.matches`（注文内容と与信の整合を含む）が成り立たないと CONFLICT になる。取消の開始（`startCancellation`）はこれを確認しないので、成り立たない注文でも PAY.JP に取消を送る。

### 7.2 変更

- `startCancellation` の**新しい取消**で、CANCEL の行を保存したあと、同じトランザクションの中で、反映と同じ `matches` を確認する。成り立たなければ例外にして、ロールバックする。CANCEL の行は残らず、PAY.JP を呼ばない。
  - 判定を新しく書かず、反映と同じ判定・同じ入力を使うので、送信前と反映で食い違わない。
  - 顧客・管理者の取消の両方に効く（どちらも `startCancellation` を通る）。
- 例外は InvalidOrderStatusException。文言は「注文内容とカード決済の内容を確認できないため、キャンセルを受け付けられません。注文番号「n」を添えてお問い合わせください。」。管理者の画面では、すでに「決済情報要確認」になり、キャンセルボタンは出ない（変えない）。
- 変更期限の確認のほうを先に行う（期限後の文言は今と同じ）。
- 確認のあと送信までは CANCEL が PENDING なので、注文変更・売上確定はできず、判定は変わらない。

### 7.3 変えないもの

- すでにある PENDING の取消の再確認（PAY.JP は照会だけ）。
- 与信期限切れの取消。PAY.JP に取消を送らず、開始と反映が 1 つのトランザクションなので、今も食い違いが残らない。
- 取消結果の反映、Webhook、照合、ロック順、冪等キー。

### 7.4 制約

- すでに不整合になった注文は、141-B でも自動では回復しない。売上確定・キャンセルとも拒否され、管理者の個別対応になる。開発 DB には、与信超過の注文も、PENDING・FAILED の取消もない（2026-11-24）。
- 今まで「反映されないが PAY.JP では取り消される」だった不整合な注文は、「PAY.JP でも取り消されない」に変わる。与信は有効期限まで残る。

### 7.5 テスト

1. 最新版の DB で「注文内容だけ」の不整合（与信超過、同額で版だけ違う）を作り、顧客・管理者の取消を拒否する。PAY.JP を呼ばず、DB は変わらない。
2. 減額された注文、一括編集後の注文（RETAIN_AUTH・REAUTHORIZE）、未変更の注文は、今と同じく取り消せる。
3. 既存の PENDING の取消は、今と同じく照会して反映する。与信期限切れの取消も変わらない。
4. evidence が壊れている登録済みの注文は、送信前に拒否する。
5. 修正を外すと 1 と 4 が失敗することを確かめる。
6. 既存の単体テスト（PaymentServiceTest など）は、確認に必要なモックを加える。回帰は取消・Webhook・照合・与信期限・一括編集・発送準備・顧客メール。

## 8. 適用と確認

1. サブ機能ごとに、実装・テスト・結果の報告を行い、確認を得てから main に統合する。
2. `./start-dev.sh` で V109 を開発 DB に適用する（実行前に確認する）。PostgreSQL は再起動しないので、runtime の再承認は要らない。
3. ブラウザ確認（141-A）：`/items` の直接入力の案内、キャンセル確認画面、注文詳細、通常の一括編集の画面、変更履歴。期限内の注文が必要なものは、用意のしかたを事前に相談する。
4. 141-B は、PAY.JP を呼ばないテストで確認できるので、ブラウザ確認は通常の画面表示だけにする。

戻すとき：V109 は削除せず、新しいマイグレーションで外す。

## 9. 対象外

- 配送先変更、未使用の `cancelOrderForUser`、不整合になった注文の回復機能、Feature140 の案 b（legacy の数量増加の画面の撤去）。

## 10. 141-A の実装結果（2026-11-24）

### 10.1 設計との差

- 6.2 の 3「別の注文の operation に結び付いた履歴の拒否」のテストは作っていない。既存の複合 FK（履歴と注文の組）により、その状態を作れない。関数の条件には `order_id` を含めてある。
- V109 の stock_movements のトリガーは、INSERT 用と UPDATE 用（`f141_legacy_item_change_update_guard`）の 2 つに分けた。UPDATE は `movement_type` がその値に変わるときだけ拒否し、過去の行のほかの列の更新は妨げない。
- 6.2 の 4 の「legacy の数量増加が V109 の適用後に完了する」は、既存の LegacyIncreaseClosureUpgradePostgresTest（target=107 から最新版へ）が検証する。新しいテストは追加していない。
- `OrderEditAdmissionServicePostgresTest` の `admissionCommitSerializesAllExistingOperationGates` は、6.1 の表から漏れていた。共有の `invoke` から items を外したため、回帰で失敗して判明した。値一覧から items を外した。
- `Order.removeItem` は残した。

### 10.2 テスト結果

- 新しいテスト 3 クラス・10 件：PASS（LegacyItemsClosedPostgresTest 8、LegacyItemsClosureUpgradePostgresTest 1、LegacyItemDecreaseNotificationPostgresTest 1）。
- 回帰：計画は 204 件。うち `CaptureConnectionTest` は注釈でテストではないため、実際は 203 クラス。201 クラスは 1 回で PASS。失敗した 2 クラスは、再実行して PASS。
  - OrderEditAdmissionServicePostgresTest：上記の移行漏れ。修正して 29 件 PASS。
  - OrderEditFailureClosureFoundationPostgresTest：実行中にホストの記録が 18:09:54〜19:50:25 の間途切れており（スリープとみられる）、1 件が失敗した。変更せずに再実行して 24 件 PASS。
- 修正を外した確認：スクラッチのコピーで、V109 の 3 つのトリガー、コントローラーの案内、キャンセル確認画面の変更を 1 つずつ外し、どれも LegacyItemsClosedPostgresTest の対応するテストが失敗することを確かめた。

### 10.3 削除したテスト

| クラス | テスト |
|---|---|
| OrderControllerTest | itemChangeInputDisplaysFormWhenChangeIsAllowed、itemChangeInputRedirectsWhenChangeIsNotAllowed、itemChangeConfirmDisplaysConfirmationWhenChangeIsValid、itemChangeConfirmReturnsToInputWhenNothingChanged、itemChangeBackReturnsToInputWithSubmittedForm、changeItemsUpdatesOwnedOrderAndRedirectsToDetail、changeItemsDisplaysErrorWhenContentRevisionIsStale、changeItemsDoesNotCallServiceWhenFormHasValidationErrors、itemChangeConfirmReturnsToInputWhenFormHasValidationErrors |
| OrderServiceTest | unchangedItemsAreRejectedWhenDatabaseDeadlineHasClosed、`changeItemsForUser` の DecreasesQuantityAndRestoresStock・RemovesItemAndRestoresAllItemStock・DoesNothingWhenQuantitiesAreUnchanged・RejectsRemovingAllItems・RejectsQuantityIncrease・RejectsStaleContentRevision・RejectsUnknownOrderItemId・RejectsDuplicateOrderItemId・RejectsExactlyAtDeadline・RejectsPaidOrder・RejectsOrderOwnedByAnotherUser |
| OrderPaymentGuardPostgresTest | pendingOperationRejectsItemChangeWithoutAnySideEffect、paymentStartCommittedWhileChangeWaitsMakesChangeReject、expiredModificationPeriodStillRejectsWithoutMutation |
| OrderModificationGuard、OrderShippingPreparation、OrderEditAdmissionGuard、OrderEditAdmissionService の各 PostgresTest | パラメーター化テストの ITEMS・items の行だけ |

改名（検証の対象を、残る機能に替えた）：

- normalDecreaseAndRemovalRestoreStockAndAllowDecreasedCapture → decreasedContentWithoutEvidenceAllowsDecreasedCapture
- itemChangeCommitsBeforePreparationAndCaptureUsesNewSnapshot → decreaseCommittedBeforePreparationIsSeenAfterTheOrderLockWait
- newOrderCanBeReducedPreparedShippedAndCapturedThroughRequery → newOrderReducedBeforeFeature141CanBePreparedShippedAndCapturedThroughRequery
- acceptanceItemAndAddressChangesAndShipmentEachRecordOneNotification → acceptanceAddressChangeAndShipmentEachRecordOneNotification

### 10.4 ブラウザ確認（2026-11-24、開発 DB、V109）

- A（期限後の注文 30・32、他人の注文 23）：`/items` の直接入力の案内、変更履歴、他人の注文の 404、期限後のキャンセル画面の案内。期待どおり。
- B（PAY.JP テストモードで新しく作った注文 34、期限 2026-11-25 14:00）：`/items` の案内と「注文内容を変更する」の併存、キャンセル確認画面の案内、一括編集の画面。期待どおり。
- 確認後の DB：注文 34 は ORDERED・AUTHORIZED・5,980 円・版 0 で、変更操作・変更履歴はない。`ORDER_ITEM_CHANGE` は 0 件。起動中の ERROR はない。
- 確認の前に作られた注文 33 は、決済が未完了（AUTHORIZE PENDING）のまま残っている。141-A の変更には関係しない。

## 11. 141-B の実装結果（2026-11-25）

### 11.1 実装と設計との差

- `PaymentService.startCancellation` の新しい取消で、CANCEL の行を保存したあと、同じトランザクションで `PaymentPurposeResolver.matches` を確認する。反映（`target`）と同じ private メソッドに判定と入力をまとめた。反映の判定は変えていない。
- 7.2 との差はない。設計時に挙げていなかった点として、過去に FAILED の取消がある注文は `matches` が不成立になる。今も反映で CONFLICT になる状態で、本番コードに取消を FAILED にする処理はない。
- 7.5 の 4（evidence が壊れている登録済みの注文）は、evidence の表を壊せないため、`OrderPaymentEvidenceGuard.inspect` の結果を BLOCKED に差し替えて検証した。
- 7.5 の 2 の一括編集後の取消（RETAIN_AUTH・REAUTHORIZE）は、既存の OrderEditShipmentIntegrationTest、7.5 の 3 の与信期限切れの取消は、既存の WarehouseExportPostgresTest が検証する。期限切れの取消を確認の対象から外す条件そのものを外す試験はしていない。

### 11.2 テスト結果

- 新しいテスト：CancellationPrecheckPostgresTest 8 件、PaymentServiceTest に 1 件追加・1 件更新。
- 回帰：152 クラス・3,297 件がすべて PASS（新しいテストを含む）。
- 修正を外した確認：スクラッチのコピーで送信前の確認を外すと、CancellationPrecheckPostgresTest の拒否 3 件と PaymentServiceTest の追加 1 件が失敗する。

### 11.3 ブラウザ確認（2026-11-25、開発 DB、V109）

ユーザーが画面を操作し、結果を DB と照合した。8 章の 4 では通常の画面表示だけの予定だったが、確認を得て、PAY.JP テストモードの取消も 1 回行った。

- 画面：注文 34 の注文詳細とキャンセル確認画面が、期待どおりに表示された。
- 取消：注文 34（未変更、evidence epoch あり）を顧客画面からキャンセルし、「注文をキャンセルしました。」と表示された。取消後にキャンセル確認画面を開くと、注文詳細に戻り、キャンセルできない旨が表示された。
- DB：注文 34 は CANCELLED（09:47:41）、決済 48 は CANCELLED。CANCEL（取引 73）は 1 件で SUCCEEDED、5,980 円、USER、ORDER_CANCELLATION。商品 6 の在庫は 14 から 15 に戻った。決済 48 の payment_discrepancies は 0 件で、アプリのログに ERROR はない。
- 拒否される場合の画面は、ブラウザでは確認していない（CancellationPrecheckPostgresTest で検証）。
- 注文 33 は、AUTHORIZE PENDING のまま残っている（この確認では操作していない）。
