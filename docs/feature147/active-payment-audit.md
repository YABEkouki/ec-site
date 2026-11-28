# Feature147 有効な決済の扱いの点検（候補 G）

状態：4 章は 1〜4 とも案どおり承認（2026-11-28）。147-A 完了（6 章）

## 1. 目的

Feature146-C の不具合は、テストの注文データの形が実際とずれていたために見逃した。テストの注文は有効な決済（`orders.active_payment_id`）が空で、実際の注文には値が入っている。次の 2 点を点検する。

1. 取消済みの注文が、取り消した決済を指したまま残ることの影響（146 の設計書 14.4）
2. ほかのテストの注文データにも、同じずれがないか

## 2. 調査結果（main acfd0e5）

### 2.1 実際のデータの形

- チェックアウト（`PaymentService` の与信の開始）は、決済を作るとすぐ有効な決済にする。
- V55 で列を加えたとき、既存の注文には値を入れていない。値が空の、生きている注文は、V55 より前の古い注文だけ。
- 開発環境の 37 件
  - 生きている注文（ORDERED 17 件、SHIPPED 8 件）は、すべて値が入っている。
  - 値が空なのは、通常の取消で空にした CANCELLED の 11 件だけ。
  - 値が入ったままの CANCELLED は、出荷中止した注文 37 の 1 件だけ。

### 2.2 有効な決済を読むコード（1 の点検）

アプリのコードで有効な決済を読む箇所は、すべて読んだ。

- 生きている注文だけを扱う箇所：取消済みの注文には届かない。
  - 対象を ORDERED か、与信済み・売上確定済みの決済に絞っている：決済の操作の開始、売上確定、再与信・注文変更、変更の証拠、与信の期限の表示、未払いの締め、緊急出荷、倉庫向け出力。
  - 対象を、取消済みでも失敗でもない決済に絞っている：決済の一意の確認。
- 取消済みの注文も読む箇所：`PaymentPurposeResolver`（結果の再適用の判定）。
  - 値が空なら、通常の取消の形（`terminalCancellationReplay`）から、取り消した決済を割り出す。
  - 値が入っていれば、その値を使う。出荷中止した注文はこちらを通る。
  - 出荷中止のあとに、同じ取消の結果がもう一度届いたとき（Webhook の再送、照合）の扱いは、テストで確かめていない。
- 画面のテンプレートは、有効な決済を読んでいない。
- 注文 37 は、出荷中止の完了後に照合のずれ（`payment_discrepancies`）が記録されていない。

結論：問題になる読み手は見つからなかった。確かめていないのは、出荷中止のあとの再適用だけ。

### 2.3 テストの注文データ（2 の点検）

- 決済を作るテストのクラスは 65。そのうち 47 クラスは、有効な決済に値を入れていない（古い注文の形）。
  - 47 のうち 28 クラスは DB を使うテスト（Postgres）。19 クラスは、エンティティやモックを使う単体テスト。
- アプリと DB には、値が空かどうかで動きが分かれる箇所がある。
  - 取消の反映：146-C で直した箇所。値が空なら発送準備済みの確認を通らない。
  - 有効な決済を差し替えるとき（`Order.bindActivePayment`）
  - 売上確定の対象の決済（`CaptureShipmentPolicy`）
  - 結果の再適用の判定（`PaymentPurposeResolver`）
  - 決済の一意の確認（`PaymentService.validateSingleActivePayment`）
  - 有効な決済の確定（`ActivePaymentService`）：値が入っていれば、決まった確認を通る。空なら、決済の安全確認から割り出す。
  - 緊急出荷の出荷の照合（`WarehouseExportShipmentGuard`）
  - 出荷中止の対象（`ShippingStopService`）
  - DB の V86・V114・V115
- 28 クラスの多くは、実際の新しい注文の形（値が入っている）では、この分かれ道を通っていない。146-C と同じ見逃しが、ほかにもありうる。

## 3. 案

### 147-A：試しに形を変えて、失敗を洗い出す（コミットしない）

- 28 クラスの DB を使うテストで、テストの注文データを作る部分だけを変えたコピーを作る。チェックアウトと同じく、有効な決済に値を入れる。
- コピーで各クラスを流し、失敗したテストを集める。
- 失敗を 2 つに分ける。
  - テストの前提だけが古い（146-C で直した 2 件と同じ）
  - アプリの不具合（146-C と同じ種類）
- 結果を報告し、147-B で直す範囲を決めていただく。本体のコードとテストは、この段階では変えない。

### 147-B：直す（147-A の結果を見てから決める）

- 不具合があれば直す。不具合ごとに、どう直すかを先に相談する。
- 28 クラスのテストの注文データを、値が入った形に変える。古い注文の形を意図して確かめているテストは、古い形のまま残し、その旨を書く。対象は Feature124 の古い増額（`LegacyIncrease*`、`OrderIncrease*`）と、古い変更（`WarehouseExportLegacyChange*`）。
- 出荷中止のあとに、同じ取消の結果がもう一度届いたときのテストを加える（Webhook の同期、再確認）。期待：反映済みとして扱われ、何も変わらず、照合のずれも記録されない。
- 単体テストの 19 クラスは、分かれ道に関わるもの（`CaptureShipmentPolicyTest`、`PaymentServiceTest` など）だけ、値が入った場合を加える。

## 4. 決めていただくこと

| # | 項目 | 案 |
|---|---|---|
| 1 | 進め方 | 147-A（洗い出し、コミットなし）→ 報告 → 147-B |
| 2 | 147-A の対象 | DB を使う 28 クラス。単体テストの 19 クラスは 147-B で必要なものだけ |
| 3 | 古い注文の形のテスト | 古い増額・古い変更のテストは古い形のまま残す。ほかは値が入った形にする |
| 4 | 出荷中止のあとの再適用のテスト | 147-B で加える |

## 5. 確認

- 147-A：本体は変えず、コピーで流す。PAY.JP への送信やアプリの再起動はない。
- 147-B：変えたクラスと、grep で選んだ回帰テスト。守りを外した確認は、不具合を直した箇所について行う。

## 6. 147-A の結果（2026-11-28）

### 6.1 やり方

- 本体の写しの中だけで、`Payment` に保存直後の処理（`@PostPersist`）を加えた。チェックアウトと同じく、決済を注文の有効な決済にする。
  - 紐づけるのは、注文が ORDERED で、発送準備前、売上確定の決済がなく、まだ有効な決済がないときだけ。
  - 失敗・取消済みの決済には紐づけない。実際には、失敗した決済は有効な決済から外れるため。
  - 紐づけるたびにログに 1 行出し、クラスごとに、紐づけが実際に起きたことを数えた。
- DB の トリガーで紐づける方法は使わなかった。注文のエンティティが毎回すべての列を書き戻すので、あとから空に戻されるため。
- 28 クラスのうち、次の 8 クラスは対象外にした。
  - 有効な決済の列がない古い版数で動くもの（3 クラス）：`CaptureRequestPostgresTest`、`OrderShippingMetadataPostgresTest`、`PaymentDiscrepancyOptimisticLockPostgresTest`
  - 古い形を意図して確かめるもの（5 クラス、4 章の 3）：`LegacyIncreaseClosedPostgresTest`、`OrderIncreasePostgresTest`、`OrderIncreaseCustomerPostgresTest`、`OrderChangePaymentCustomerPostgresTest`、`WarehouseExportLegacyChangePostgresTest`

### 6.2 結果

- 20 クラスすべてが PASS した（2 回目、写しの手直し後）。アプリの不具合は見つからなかった。
- 1 回目に失敗した 3 クラスと、紐づけが起きなかった 3 クラスの原因
  - `AdminOrderShippingPreparationSecurityPostgresTest`（エラー 2 件）：テストの後片付け。注文が指している決済を先に消そうとして、外部キー（`fk_orders_active_payment_owner`）に拒否された。テストの前提が古いだけ。
  - `PaymentReconciliationFairnessPostgresTest`（失敗 10 件・エラー 5 件）：同じ後片付けの問題。前のテストのデータが残り、あとのテストが壊れた連鎖。テストの前提が古いだけ。
  - `PaymentShippingPreparationValidationPostgresTest`（失敗 2 件）：写しの処理が、失敗した決済に先に紐づけた。写しの作り方の問題。
  - `CaptureShipmentFlowPostgresTest`、`CaptureShipmentPolicyPostgresTest`：紐づけが起きなかった。発送準備を始めてから決済を作る順で、テストの注文データを作っていた（実際は決済が先）。順を入れ替えると紐づき、PASS した。
  - `ManualOrderShipmentPostgresTest`：紐づけが起きなかった。注文はほぼ入金済み（PAID）で、与信中のカードの注文がない。対象がないので、変える必要はない。
- 紐づけが起きた回数は、クラスごとに 4〜1392 回。
- 2 回目の手直し：紐づけの条件（失敗・取消済みの決済を除く）、2 クラスの後片付け、2 クラスの順の入れ替え。

### 6.3 147-B で直す範囲（承認済み）

- テストの注文データ：19 クラスで、チェックアウトと同じく有効な決済を持つ形にする。写しの処理ではなく、各クラスのテストの注文データを作る部分で明示的に紐づける。
  - `ManualOrderShipmentPostgresTest` は対象外。
  - `CaptureShipmentFlowPostgresTest` と `CaptureShipmentPolicyPostgresTest` は、決済を作ってから発送準備を始める順にする。
  - `AdminOrderShippingPreparationSecurityPostgresTest` と `PaymentReconciliationFairnessPostgresTest` は、後片付けで先に有効な決済を外す。
- 古い形を意図するクラスで、すでにそう書いていないものには、その旨のコメントを加える。
- 出荷中止のあとに、同じ取消の結果がもう一度届いたときのテストを加える（4 章の 4）。
- 単体テストの 19 クラス：値が空かどうかで動きが分かれる処理（`CaptureShipmentPolicy`、`PaymentService.validateSingleActivePayment`、`PaymentPurposeResolver`）について、値が入った場合のテストがあるかを確かめ、ないものだけ加える。


## 7. 147-B の結果（2026-11-28）

6.3 の範囲を承認いただき、`feature/147-b-active-payment-fixtures` で実装した。本体のコードは変えていない（テストだけ）。

### 7.1 変えたこと

- テストの注文データ（19 クラス）：決済を保存した直後に `order.bindActivePayment(payment)` を呼び、チェックアウトと同じく有効な決済を持つ形にした。
  - `CaptureShipmentFlowPostgresTest`、`CaptureShipmentPolicyPostgresTest`：決済を作って紐づけてから、発送準備を始める順にした。
  - `AdminOrderShippingPreparationSecurityPostgresTest`、`PaymentReconciliationFairnessPostgresTest`：後片付けで、決済を消す前に有効な決済を外すようにした。
  - `PaymentShippingPreparationValidationPostgresTest`：失敗・取消済みの決済ではなく、有効な与信に紐づけた。
  - `PaymentOperationStartPostgresTest`：共通の注文データだけを変えた。テストの中で、わざと複数の決済を作る異常なデータは、そのままにした。
- 古い形を意図する 5 クラス：決済を作る箇所に、その旨のコメントを加えた。どのクラスも、古い増額が `ActivePaymentService.initializeForOrderChange` で有効な決済を割り出す経路を通っている。
- 出荷中止のあとの再適用のテスト（`WarehouseExportPostgresTest.theSameCancellationResultAfterAStopIsAlreadyApplied`）：
  - 対象は、有効な決済が入った注文と、古い形（空）の注文の 2 つ。
  - 結果が分からないまま出荷中止を始め、そのときの操作の写しを取っておく。そのあと Webhook で中止を完了させる。
  - 同じ結果を 4 つの入口から届ける。
    - 古い写しでの同期（照合処理と並んだ場合）：ALREADY_APPLIED
    - 結果の直接の反映：ALREADY_APPLIED
    - Webhook がもう一度届く：SKIPPED
    - 管理画面の再確認：ALREADY_CANCELLED
  - 注文・決済・決済の操作・出荷中止・状態の履歴・在庫の動き・お客様への通知は、どれも変わらない。照合のずれも記録されない。
- 単体テスト（3 か所とも、値が入った場合のテストがなかったので加えた）
  - `CaptureShipmentPolicyTest`：値が入った注文で、NEW・CONTINUE・ALREADY_APPLIED が正しく判定される。別の決済を指していれば INVALID_PAYMENT になる。
  - `PaymentServiceTest`：値が入った注文は、取消を始められる。別の決済を指していれば「有効な決済が一意でない」で止まり、何も保存しない。
  - `PaymentPurposeResolverTest`：取消済みの注文が有効な決済を指したまま（146-C の出荷中止のあとの形）でも、決まった取消は再適用できる。別の決済を指していれば再適用しない。

### 7.2 テスト

- 変えたクラス：28 クラス、701 件すべて PASS。
- 守りを外した確認（写しの中で本体を変えて流す）：5 通りすべてで、テストが失敗して検出された。
  - 取消済みで有効な決済を持つ注文の再適用を拒む：`theSameCancellationResultAfterAStopIsAlreadyApplied` が失敗。
  - 再適用の判定で、別の決済を指す場合の確認を外す：`PaymentPurposeResolverTest` の追加テストが失敗。
  - 古い形の注文の再適用の扱いを外す：既存の `PaymentPurposeResolverTest` のテストが失敗。今回加えた DB のテストの古い形の部分は、これでは失敗しなかった。理由は確かめていない（後ろの再適用の判定だけで同じ結果になっていると見られる）。
  - 売上確定の判定で、別の決済を指す場合の確認を外す：`CaptureShipmentPolicyTest` の追加テスト（3 件）が失敗。
  - 決済の一意の確認で、別の決済を指す場合を外す：`PaymentServiceTest` の追加テストが失敗。
- 回帰テスト：有効な決済・再適用の判定・売上確定の判定・出荷中止に関わるテストを grep で選んだ 40 クラス（今回変えたクラスを除く）、1310 件すべて PASS（スキップなし）。
  - 1 回目は、共有のテスト用 DB で流すべき 11 クラスを、専用のコンテナで流す計画に入れていたため、起動前に止められた。同じまとまりにいた 1 クラスも止まった。この 12 クラスを正しい方式で流し直して PASS した。
- 本体を変えていないので、アプリの再起動やブラウザでの確認は要らない。
