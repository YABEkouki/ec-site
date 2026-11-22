# Feature138 出荷実績 CSV の取込で緊急出荷の注文を扱う

状態：完了（2026-11-22）。138-A（V103、main 30eca60）・138-B（V104）を main に統合。138-B はブラウザで確認した（6 章）。基準は main 5311492（最新マイグレーション V102）。

## 1. 目的

Feature137 から、緊急出荷の注文（Feature136）も 14:05 の倉庫向け出力（Feature131）と同じ形式の 2 ファイルで倉庫へ渡している（バッチID は `E` + 緊急出荷記録の id）。倉庫から返る出荷実績 CSV（Feature132）に E のバッチID の行が入るので、その行を緊急出荷記録と照合し、14:05 の注文と同じように取込から出荷・売上確定（Capture）の開始まで自動で行う。

## 2. 決定事項（2026-11-22）

| # | 決定事項 |
|---|---|
| 1 | E の行は `E<id>` の緊急出荷記録があり、記録の注文が CSV の注文番号と一致するときだけ受け付ける |
| 2 | 出力記録（V102 warehouse_file_outputs）が WRITTEN でない（PENDING / FAILED）ときは拒否する（FILE_NOT_WRITTEN） |
| 3 | 出力記録がない V102 より前の緊急出荷は、V100 の CSV ダウンロード記録があるときだけ受け付ける |
| 4 | V89 の CHECK を広げ、管理者による準備（緊急出荷）の注文もシステムとして出荷できるようにする。DB トリガーで、その準備と一致する緊急出荷記録を必須にする（記録のない旧手動準備は不可） |
| 5 | V90 の取込行に緊急出荷記録への外部キーを追加する。拒否以外の行は、出力バッチと緊急出荷記録のどちらか一方だけを参照する |
| 6 | 緊急出荷の注文は、記録の content_revision・total_amount と一致して出荷する。Payment は、記録が ORDERED なら Capture の対象があり（記録の active_payment_id があればそれと一致）、PAID なら Capture なし |
| 7 | 6 の照合は、システム出荷だけでなく管理者による発送を含む、緊急出荷の注文のすべての出荷に DB トリガーで適用する |
| 8 | 管理者がすでに発送済みにした注文は、14:05 の注文と同じく ALREADY_SHIPPED とし、配送業者・追跡番号は表示しない |
| 9 | 取込結果のバッチID は "3" / "E3" と表示する。緊急出荷一覧に発送者を表示する。注文詳細は変えない |
| 10 | 138-A（V103：緊急出荷の注文のシステム出荷）と 138-B（V104：取込で E の行を受け付ける・画面）に分ける |

決定 6 の補足（実装時の調整）：カード注文の `active_payment_id` は NULL のことがある（旧データやテストの注文。V86 の出力判定も「NULL、または一致」で扱っている）。そのため「記録の active_payment_id が NULL でなければ一致」とし、NULL のときは Capture の対象があることだけを求める。Capture の対象が注文の唯一の有効な与信であることは、今までどおり CaptureShipmentPolicy が確認する。

## 3. Feature132 の取込の仕組み（要点）

docs/feature132 はないので、ここに要点を残す。

- 画面：`/admin/warehouse-shipments`（AdminWarehouseShipmentController）。CSV の列は `バッチID,注文番号,出荷日,配送業者,追跡番号`（UTF-8、見出し行あり、1,000 行・1MB まで）。
- アップロード時の判定（WarehouseShipmentImportService、1 トランザクション、advisory lock 132002 で 1 件ずつ）：形式 → 出荷日 → 配送業者 → 追跡番号 → ファイル内の重複 → 出力行・SEALED のバッチ → 取込済み → 注文の状態。各行を REJECTED（理由付き）/ ALREADY_SHIPPED / PENDING として記録する。同じ SHA-256 のファイルは最初の取込を返す。
- バックグラウンドの出荷（WarehouseShipmentImportWorker、`app.warehouse-shipment-import.enabled`、既定 false）：PENDING を SKIP LOCKED で 1 行ずつ取り、トランザクションの外で出荷する。ORDERED は SYSTEM として Capture、PAID は Capture なしで発送。結果は DB から読み直して記録する（SHIPPED / CAPTURE_UNCONFIRMED / CAPTURE_FAILED / ALREADY_SHIPPED / REJECTED）。3 回で完了しなければ REJECTED。
- システム出荷の記録（V89）：発送者 account_id NULL・名前 `WAREHOUSE_SHIPMENT_IMPORT`。Capture の開始者と SHIPPED の状態履歴も SYSTEM / NULL / 同じ名前。
- 配送業者・追跡番号・倉庫の出荷日は、取込で出荷した注文の管理者・顧客の注文詳細に表示する（OrderShipmentTrackingReader）。

## 4. 138-A：緊急出荷の注文のシステム出荷（V103）

### 4.1 DB（V103__emergency_system_shipment.sql）

- `chk_orders_shipment_metadata`：システム出荷で認める出荷準備に、管理者による準備（account_id あり、名前が空でない）を追加。
- `f132_system_shipment_guard`：システム出荷には、自動準備の注文なら出力行、管理者が準備した注文ならその準備（管理者 id・名前・準備時刻）と一致する緊急出荷記録が必要。
- `f138_emergency_shipment_guard`（新規）：準備者が管理者の注文が SHIPPED になるとき、緊急出荷記録があれば決定 6 の照合を行う（23514）。記録のない旧手動準備は対象外。期待する Payment は IF の外で変数に入れて比べる。

### 4.2 アプリ

- `Order.isShippingPreparedForSystemShipment()`：自動準備、または有効な管理者による準備。システム出荷の記録（`requireNewShipment`）と CaptureShipmentPolicy はこれで判定する。
- `WarehouseExportShipmentGuard.allowsSystemShipment(order)`：出力行、または準備と一致する緊急出荷記録があること。CaptureShipmentOperations・ManualOrderShipmentService のシステム出荷で使う。
- `WarehouseExportShipmentGuard.matchesExport(order, paymentId)`：緊急出荷記録との照合（決定 6）を追加。管理者の発送とシステム出荷の両方で使う。
- PaymentSafetyClassifier：SYSTEM の出荷者を認める準備に管理者による準備を追加（記録は DB が保証する）。

### 4.3 テストで確認できないこと

- 記録のない旧手動準備（V97 より前）は、V97 のトリガーにより今は作れない（トリガーは無効にしない）。旧手動準備をシステム出荷しないことは、注文をメモリ上で旧手動準備の状態にして `WarehouseExportShipmentGuard` を確認する（WarehouseShipmentSystemActorPostgresTest）。DB 側（`f132_system_shipment_guard`）はテストできない。
- V98 で準備後の内容は凍結されるので、内容の不一致は DB では作れない。アプリの照合はメモリ上の注文で確認する。

### 4.4 テスト

- WarehouseShipmentSystemActorPostgresTest：緊急出荷のカード注文・PAID の注文のシステム出荷、未準備の注文の拒否、DB の照合（Capture なし・別の Payment・PAID の記録で Capture あり）、ガードの照合。
- PaymentSafetyClassifierTest、最新版数の 3 テスト（103）。
- V103 で、緊急出荷した ORDERED の注文を Capture なしで SHIPPED にできなくなったため、SQL で直接発送していたフィクスチャを直した：OrderShippingMetadataPostgresTest（PAID にしてから準備する）、AdminEmergencyShippingQueriesPostgresTest（発送する注文を PAID にしてから準備する）、OrderShippingPreparationPostgresTest.terminalStateRejectsEvenWhenPrepared と CaptureShipmentFlowPostgresTest.unsafeOrLegacyDataNeverTriggersGatewayPost（legacy-shipped）（発送者と Capture の対象 Payment を付けて発送する。後者は Capture 操作がないまま）。
- 回帰（2026-11-22）：緊急出荷の準備・Capture・出荷・取込に関わる 67 クラスがすべて成功（計画 ~/.cache/ec-site-f138/plan-a1.txt、plan-a-regress.txt）。

## 5. 138-B：取込で E の行を受け付ける（V104）

### 5.1 DB（V104__emergency_shipment_import_lines.sql）

- `warehouse_shipment_import_lines.emergency_shipping_preparation_id`（緊急出荷記録への外部キー）を追加。
- `chk_warehouse_shipment_import_line_content`：拒否以外の行は、`batch_id` と `emergency_shipping_preparation_id` のちょうど一方を持つ（V90 の無名の CHECK を置き換え）。`chk_warehouse_shipment_import_line_source`：拒否の行も両方は持たない。
- `f132_import_line_guard`：E の行（PENDING / ALREADY_SHIPPED）は、記録の注文が行の注文と一致し、出力記録が WRITTEN、または出力記録がなく V100 の CSV ダウンロード記録があること。追加した列も内容として変更不可。

### 5.2 アプリ

- WarehouseShipmentImportService：バッチID が `[1-9][0-9]{0,17}` なら 14:05 のバッチ、`E[1-9][0-9]{0,17}` なら緊急出荷記録で判定する（小文字の e、E0 などは INVALID_FORMAT）。緊急出荷の判定は、記録がない・注文が違う → NOT_EXPORTED、出力記録が PENDING / FAILED → FILE_NOT_WRITTEN、出力記録もダウンロード記録もない → NOT_EXPORTED。それ以降（取込済み・注文の状態）は 14:05 の行と同じ。拒否した行には、存在する記録の id だけを残す。
- WarehouseShipmentImportReason：FILE_NOT_WRITTEN を追加。INVALID_FORMAT の文言に「数字、または E＋数字」を追加。
- ワーカーは変更なし（138-A で緊急出荷の注文もシステムとして出荷できる）。
- 画面：取込結果のバッチID を "3" / "E3" で表示（`Line.batchLabel()`）。取込画面の説明に E のバッチID を追加。緊急出荷一覧の出荷状態に発送者（「倉庫出荷取込み」または管理者名）を表示。注文詳細は変更なし（取込で出荷した注文の配送業者・追跡番号は今までどおり表示される）。

### 5.3 テストで確認できないこと

- 出力記録がない緊急出荷（V102 より前）は、V102 のトリガーで必ず出力記録が作られるため、テストでは作れない（トリガーは無効にしない）。開発 DB の E1・E2（注文 24・25）でブラウザ確認する。

### 5.4 テスト

- WarehouseShipmentImportPostgresTest：E の行の判定（WRITTEN のカード・PAID → PENDING、PENDING / FAILED → FILE_NOT_WRITTEN、管理者が発送済み → ALREADY_SHIPPED、別の注文・存在しない記録・数字のバッチID → NOT_EXPORTED、形式の誤り）、ワーカーによる出荷（システム出荷・Capture・追跡番号・緊急出荷一覧の発送者）、DB ガード。
- AdminWarehouseShipmentControllerTest（E のバッチID と FILE_NOT_WRITTEN の表示）、AdminEmergencyShippingControllerTest（新規。発送者の表示）、AdminEmergencyShippingQueriesPostgresTest（発送者）、最新版数の 3 テスト（104）。
- 回帰（2026-11-22）：138-A と同じ 67 クラスに上の新規・変更クラスを加えた 70 クラスがすべて成功（計画 ~/.cache/ec-site-f138/plan-b1.txt、plan-b-regress.txt）。CaptureShipmentFlowPostgresTest も固定の管理者 ID 7 を使うため、ほかのクラスと同じコンテナで実行すると「admin id 7 is already used」で失敗する。単独で実行して成功を確認した。

## 6. 開発環境での確認記録（2026-11-22）

- アプリを `WAREHOUSE_SHIPMENT_IMPORT_ENABLED=true` で起動し、V103・V104 を開発 DB に適用した（v104）。.env は変えていない。
- 取込んだ CSV（shipped-emergency-test.csv）：E1・E2（注文 24・25。V102 より前の緊急出荷で、出力記録がなく CSV ダウンロード記録がある）、E3・E4（注文 27・28。出力記録 WRITTEN）、`e3`（INVALID_FORMAT）、`E99`（存在しない記録、NOT_EXPORTED）。
- 結果：E1〜E4 は PENDING → ワーカーが SYSTEM として出荷し、PAY.JP のテストモードで Capture が成功（4 行とも SHIPPED）。拒否の 2 行は想定どおり。取込結果のバッチID の表示（E1〜E4）、管理者・顧客の注文詳細（倉庫出荷取込み、配送業者・追跡番号・出荷日）、緊急出荷一覧の発送者の表示を、ブラウザで確認した（利用者の確認：OK）。
- 出荷メール：最初は顧客向けメールの送信（`CUSTOMER_NOTIFICATION_ENABLED`、既定 false）を有効にせずに起動したため届かなかった。通知は 4 件とも記録されていた（ORDER_SHIPPED、SYSTEM、warehouseImport=true、PENDING）。`CUSTOMER_NOTIFICATION_ENABLED=true` を加えて再起動したところ、4 件とも mailpit に届き、本文に配送業者・追跡番号・出荷日が入っていることを確認した。同時に、PENDING だった注文 25・27・28 の注文受付メールも送られた。
- 確認後、アプリは停止した。
