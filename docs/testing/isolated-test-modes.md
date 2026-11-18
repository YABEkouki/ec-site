# 隔離テストの実行モード

テストは必ず `scripts/run-isolated-tests.sh` に実行モードと完全修飾クラス名を渡して実行します（`./mvnw test` は開発DBに接続するため使いません）。runner は失敗したクラスで止まるので、1回に1クラスずつ実行するのが基本です。

## モードの決め方

各テストクラスが使えるモードは、クラスに付けた `@IsolatedTestMode`（`com.example.ecsite.support.isolation`）で決まります。

| クラスの注釈 | 実行方法 |
|---|---|
| なし（大半のクラス。DBを使わない単体テストを含む） | `--testcontainers` |
| `@IsolatedTestMode(IsolatedTestMode.Mode.SHARED)` | `--reset-test-db`（共有 `ecsite_test`） |
| `@IsolatedTestMode({SHARED, TESTCONTAINERS})` | どちらでも可（`IsolatedTestSmokeTest`） |

runner は、DBへの接続やコンテナの起動より前に注釈を確認します。モードが合わない場合は `Test mode mismatch: <クラス>: requires ...` と表示し、何も起動せずに終了コード2で止まります。

注釈は継承しません。たとえば `Customer*` 系は SHARED の `OrderEditReauthorizationIntegrationTest` を継承していますが、自身には注釈がないため `--testcontainers` で実行します。

SHARED のクラス（2026-11-18時点で29クラス）は、`payment.safety` のFeature125（owned経路）系27クラスと、`support.isolation` の `FlywayMaintenanceProfilePostgresTest`・`IndependentContainerSmokeTest` です（後者は名前に反して、共有 `ecsite_test` への接続を確認するテストです）。一覧は `grep -rl "IsolatedTestMode(" src/test/java` で確認できます。

## 実行例

```bash
# testcontainers（注釈なしのクラス）
bash scripts/run-isolated-tests.sh --testcontainers com.example.ecsite.config.SecurityConfigTest

# 共有 ecsite_test（SHARED のクラス）。パスワードは compose.yaml の POSTGRES_PASSWORD（画面に表示しない）
export EC_TEST_DB_URL=jdbc:postgresql://127.0.0.1:5432/ecsite_test EC_TEST_DB_USER=ecuser
read -r -s -p 'Test DB password: ' EC_TEST_DB_PASSWORD; export EC_TEST_DB_PASSWORD
bash scripts/run-isolated-tests.sh --reset-test-db com.example.ecsite.payment.safety.OrderEditCardPostgresTest
unset EC_TEST_DB_URL EC_TEST_DB_USER EC_TEST_DB_PASSWORD
```

接続URLは `localhost` ではなく `127.0.0.1` を使います。runner は実行ごとに `/tmp/ecsite-tests.*`（約27MB）を残すので、結果を確認したら削除してください。

## 新しいテストを追加するとき

- 共有 `ecsite_test` が必要なテスト（Feature125 の識別検査など、`isolated_*` のコンテナDBでは動かないもの）にだけ `@IsolatedTestMode(IsolatedTestMode.Mode.SHARED)` を付けます。
- それ以外は注釈を付けず、`--testcontainers` で実行します。

## 常にスキップされるテスト

| クラス | 理由 |
|---|---|
| `MailServiceIntegrationTest` | `@Disabled`。Mailpit を使う手動の疎通確認用 |
| `PaymentAuditNotificationManualResendProcessRecoveryTest` | `ecsite.restart.enabled=true` が必要で、runner からは渡さない |

## 全件実行の目安（Feature134、2026-11-18）

全379クラスを1クラスずつ実行すると約6時間かかります（DBを使うクラスは1クラス約2.5分、単体テストは30クラスずつまとめれば1クラス約10秒）。Feature134 の棚卸し・修正後は、上記のスキップを除いて全クラスが成功する状態です。runner には安全のため `app.payment.*.enabled=false` などのシステムプロパティが渡されるので、既定値に依存するテストは必要な値をテスト内で明示してください。
