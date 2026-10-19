# 注文・決済データ初期化（V53専用・手動実行）

これはFlyway／アプリ起動時の処理ではありません。今回承認されているのは、隔離Testcontainers PostgreSQLでの検証だけです。通常開発・共有・本番環境での実行には、対象・削除範囲・バックアップを示した別途の明示承認が必要です。

## 保持・削除範囲

`reset-order-payment-data.sql`の`reset_targets`に全削除25テーブルを列挙しています。注文・決済・注文変更履歴、決済不整合・監査実行・通知・対応・手動再送・UNKNOWN関連履歴を削除します。orders→paymentsの固定Capture参照をNULLにして循環参照を解消します。V52遅延制約トリガーは無効化せず、全削除後にIMMEDIATEで検査します。

通知状態は6種類の行を維持し、active=false、episode_no=0、観測／解消日時=NULLにします。versionは楽観ロック用に1増加します。注文に紐づく在庫移動履歴だけを削除し、order_id=NULLかつADMIN_ADJUSTMENTの行を保持します。不明な分類は拒否します。

商品・カテゴリ・ユーザー・管理者・住所・Flyway履歴など、対象外のpublicテーブルは全列を保持します。商品在庫は現在庫のままです。在庫の復元・調整、シーケンスのリセット、旧Captureの互換処理は行いません。IDは継続します。

## 実施手順と停止条件

1. 対象環境、ホスト／ポート、DB名、接続ユーザー、デプロイ版・V53、実行者、作業日時を記録し、削除の明示承認を得ます。接続先確認値を接続中のDBから自動転記して確認を省略しないでください。
2. 注文・決済書込みを停止します。Webhook受信、Reconciliation、監査・通知・手動再送Schedulerを含む全アプリJVMを停止してください。通知enabled=falseだけでは期限切れ要求の回復処理は止まりません。他の利用者や接続が残る場合は中止します。
3. PostgreSQLの整合バックアップと復元手順を確保し、保管先・検証結果を記録します。削除前のOrder／Payment／取引IDとprovider IDの対応も安全な場所に保全します。PAY.JP側の確認・整理は別作業であり、このSQLは外部APIを呼びません。外部側の変更はDBのROLLBACKやバックアップ復元では戻りません。
4. **読取り専用の接続確認**を実行し、承認済みの対象と人が照合します。環境やスキーマに相違、未完了PAY.JP処理の整理方針未決定、バックアップ未検証なら中止します。
5. まず同じ接続先で以下のpsqlラッパーをDRY_RUN実行します。接続先は明示した安全な接続設定（例：承認済みservice設定）で指定してください。パスワードをコマンド履歴に含めないでください。
6. DRY_RUN成功、対象外データ・在庫・シーケンスの照合成功を確認後、承認範囲内でのみapplyトークンを指定して再実行します。SQLエラー時はON_ERROR_STOPで終了し、未commitのTXは接続終了時にrollbackされます。失敗後に手動COMMITしないでください。
7. commit後、下記SELECTと保全した件数／在庫を照合します。別途承認された新規テスト注文で準備開始→SHIPPED先行→Captureを確認し、旧provider IDの遅延Webhookが無関係な注文を変更しないことを確認します。
8. 全JVMを再起動してReconciliation等のメモリ内カーソルも破棄し、Scheduler／Webhookを再開します。相違があれば再開を止めて調査します。復元が必要なら書込み停止を維持して検証済みバックアップを復元し、外部決済側とは別途照合します。

読取り専用の識別確認例（SQLは接続先を選びません）：

```sql
SELECT current_database(), current_user, inet_server_addr(), inet_server_port(),
       current_setting('server_version'), current_setting('transaction_read_only');
SELECT version, success FROM public.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;
SELECT conrelid::regclass, confrelid::regclass, pg_get_constraintdef(oid)
FROM pg_constraint WHERE contype='f' ORDER BY conrelid::regclass::text, conname;
```

ラッパー例（値は作業承認書から設定。以下は実在の接続先ではありません）：

```bash
psql -X 'service=APPROVED_MAINTENANCE_TARGET' \
  -v expected_database=APPROVED_DATABASE \
  -v expected_user=APPROVED_USER \
  -v expected_server_addr=APPROVED_SERVER_ADDRESS \
  -v expected_server_port=APPROVED_SERVER_PORT \
  -v approval=APPROVE_ORDER_PAYMENT_RESET \
  -v backup_reference=VERIFIED_BACKUP_REFERENCE \
  -f scripts/manual/reset-order-payment-data.psql
```

上記はapply未指定なので、全検査後に**必ずROLLBACK**します。COMMITは同じ引数に`-v apply=APPLY_ORDER_PAYMENT_RESET`を追加した場合だけです。Unix socket接続では確認済みの場合に限りexpected_server_addr／portへ空文字を指定します。TCPの場合はPostgreSQLが返すサーバー内部アドレス／ポートであり、ホスト側転送ポートとは異なる点に注意してください。

## COMMIT前後の確認

SQL自身が、全削除対象の0件、注文由来在庫履歴0件、通知状態6行・初期状態、維持対象の全列JSONB一致、全publicシーケンスlast_value一致をCOMMIT前に検査します。FKの対象テーブル組合せ・件数・validated属性がV53想定と違えば拒否します。FKの列・削除アクションや独自トリガーを含むDDL全体の同一性を証明するものではないため、上記カタログ結果もレビューしてください。

commit後の要点：

```sql
SELECT count(*) AS orders FROM orders;
SELECT count(*) AS payments FROM payments;
SELECT count(*) AS transactions FROM payment_transactions;
SELECT count(*) AS old_capture_pending FROM payment_transactions
WHERE transaction_type='CAPTURE' AND status='PENDING';
SELECT * FROM payment_audit_notification_states ORDER BY warning_type;
SELECT count(*) FROM stock_movements WHERE order_id IS NOT NULL;
SELECT id, stock FROM products ORDER BY id; -- 作業前の値と完全一致
SELECT * FROM stock_movements WHERE order_id IS NULL ORDER BY id; -- 作業前と一致
SELECT schemaname, sequencename, last_value FROM pg_sequences
WHERE schemaname='public' ORDER BY sequencename; -- 作業前と一致
SELECT count(*) FROM pg_constraint WHERE contype='f' AND NOT convalidated;
```

全25テーブルはSQL末尾のループで0件検査済みです。対象外テーブルの作業前後スナップショット、FK整合性、Flyway履歴も保管してください。シーケンスはトランザクションでは巻き戻りません。本SQLはnextval/setvalを呼びませんが、他プロセスの採番を止めない限り一致保証はできません。

## 検証と制約

```bash
bash scripts/run-isolated-tests.sh -Dtest=OrderPaymentResetSqlContractTest,OrderPaymentResetPostgresTest
```

専用PostgreSQL 17コンテナをDocker由来情報で検証してから書込みます。全削除対象に非空fixtureを投入し、循環FK・V52制約トリガー、保持データ・在庫・ID継続、異常時rollback、実psqlのDRY_RUN／COMMIT、実Service＋モックGatewayで初期化後の新方式を検証します。

SQLはpublic全テーブルをACCESS EXCLUSIVE NOWAITでロックするオフライン保守用です。対象外テーブルの全行JSONBを一時表（ON COMMIT DROP）に保持するため、個人情報・認証ハッシュを含み得ます。出力せず、接続・一時領域を保護し、データ量に応じた作業領域と時間を確保してください。大量データの性能、バックアップの実在、承認の有効性、外部PAY.JPの状態はSQLでは保証しません。

## 隔離検証記録（2026-10-19）

基準HEAD: `3a1aba4b9cd0cc621c1aa3968f6ce1e728123ab0`。
新規SQL契約テスト1件、専用PostgreSQL検証23件、既存`CaptureShipmentFlowPostgresTest`39件の最終レポートは、合計63件・Failures 0・Errors 0・Skipped 0です。新規24件は上記コマンドで再実行して成功しています。既存39件は同じ隔離スクリプトの`-Dtest=OrderPaymentResetSqlContractTest,OrderPaymentResetPostgresTest,CaptureShipmentFlowPostgresTest`実行で成功を確認しました。

FK追加・通知状態不足・分類不明在庫・V52トリガーの途中強制検査・途中例外・削除漏れを注入し、全行のrollbackを照合しました。接続確認値／承認／バックアップ確認の不一致、gate欠落、実ロック競合も拒否します。実psqlでは既定ROLLBACK・明示COMMIT・apply指定中のエラー終了を確認しました。初期化後の実Serviceは、旧provider IDをSKIPPEDとし、新IDで発送準備・SHIPPED先行commit・Capture成功/PENDING/FAILEDと再POSTを検証しました。Gatewayはモックのみで、通常開発・共有・本番DBでの実行やPAY.JP整理の検証ではありません。
