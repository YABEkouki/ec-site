# Feature130-B 一括編集受付のNOT_ADOPTED後の自動終了

## 問題

一括編集（owned, route `REAUTHORIZE`）の注文変更が、14:05の最終判定（Feature128）で `NOT_ADOPTED` になった後の流れは次のとおり。

1. PAY.JPで新与信が成功すると、Feature129-Bの照合（`OrderEditNotAdoptedReconciliationService`）が不要な新与信を取消す。取消は `NEW_ABORT` の一回限りの送信で行う。
2. operationは `ABORTED` になり、在庫予約は解放され、元の注文と旧与信はそのまま残る。
3. ところがadmissionは `ACCEPTED / REVIEW_REQUIRED`（reason `REAUTHORIZATION_ABORTED`）のまま終了しない。

admissionが未終了のため、出荷・売上確定のゲート（`OrderEditAdmissionGuard.requireClear`）は止まり続ける。129-Bの候補からは外れ、汎用の終了処理（V72 `f125_execution_close_request`）も `REVIEW_REQUIRED` を「不確実な作業」として拒否する。そのため、出荷のブロックが永久に解除されない。

`OrderEditReauthorizationIntegrationTest` で、最終判定の行だけをテスト内で差し込み、ほかは本番の経路のまま進めて再現した。

## 対応（V85）

V80（3DS失敗の業務終了）と同じ方式をとる。DBが検証した追記専用の証明行を、記録したトランザクションの中でだけ有効な権限として使う。

### 証明テーブル `order_edit_not_adopted_closures`

- admissionごとに1行。append-onlyで、UPDATE・DELETE・TRUNCATEは拒否する。
- 主な列：`operation_id`（最終判定への外部キー）、`new_payment_id`、`expected_version`、`document`/`canonical`/`digest`、`recording_xid`。
- 文書（document）は `f125_closure_encode` で正規化してsha256を取る。中身は、最終判定・取消要求・未解決のまま残ったattemptの一覧（`resolvedAttempts`）・在庫解放の移動IDなど。

### 証明行のINSERT時に検証すること

挿入時、トリガーがまず正規の順でロックを取る（`f125_reauth_lock` → `f125_closure_lock_related`）。その後、DB上で次の条件をすべて確かめる。

- Feature125のruntimeが稼働中（`f125_execution_check_runtime`）
- admissionが `REAUTHORIZE / ACCEPTED / REVIEW_REQUIRED / REAUTHORIZATION_ABORTED`、未終了・未適用で、owned operationと一致する
- 実行制御は `version = expected_version` で、実行中のattemptとリースがなく、最新の結果が `REVIEW:REAUTHORIZATION_ABORTED`
- 最終判定が `NOT_ADOPTED`
- operationは `ABORTED`。注文切替（`switch_decided_at`）・適用・内容変更履歴がない
- 旧与信は `AUTHORIZED` のまま。`OLD_CANCEL` の要求も旧与信取消の取引もなく、`PENDING` の取引もない
- 新与信は `CANCELLED`。`NEW_ABORT` の取引が `SUCCEEDED` で、要求・送信記録が `OBSERVED` まで揃い、金額・flow IDが一致する
- 注文のすべての金融要求の送信記録が `OBSERVED` で、`PENDING` の取引がない
- 正の在庫予約はすべて `RELEASED` で、解放の在庫移動がある
- `f125_reauth_assert_current` による確認：このoperation自身の記録を除き、注文・明細・旧与信などがadmission時点の基準と完全に一致する

### 終了関数 `f125_not_adopted_close_unapplied(admission, expected_version)`

1. ロックを取る。
2. 証明行が既にあれば、同じversionの再実行として同じ結果を返す（冪等）。versionが違えば衝突エラーにする。
3. 制御versionのCASを確認する。
4. 次の書き込みを1つのトランザクションで行う。
   - 証明行
   - 未適用の終了証跡：`CLOSED_UNAPPLIED / CHANGE_NOT_ADOPTED`。`closure_request_id` にはoperation IDを入れる
   - 制御行のfencing token・versionの更新
   - admissionの `CLOSED_UNAPPLIED` への遷移

PAY.JPへの通信も、業務データ・金融データの書き込みもしない。

### 既存関数の拡張（V80本体＋証明の判定のみ）

| 関数 | 変更 |
|---|---|
| `f125_closure_capture` | 同じトランザクション内に証明がある場合に限り `REVIEW_REQUIRED` の終了を許可する。許可するのは `CLOSED_UNAPPLIED / CHANGE_NOT_ADOPTED` で、`closure_request_id` が証明のoperationと一致する終了だけ |
| `f125_execution_close_guard`, `f125_execution_final_check`, `f125_closure_assert_final`, `f125_runtime_other_work` | 証明の `resolvedAttempts` に載っているattemptを解決済みとして扱う |
| `f125_closure_assert_final` | 証明がある場合は、3DS失敗の場合と同様に `f125_reauth_assert_current` で基準と照合する |
| `f125_admission_final_check` | 終了済みadmissionにoperationが紐づくことを、証明があり、operationが `ABORTED` で、reasonが `CHANGE_NOT_ADOPTED` の場合だけ許可する |

3DS失敗（V80）の扱いは変えていない。

遅延制約トリガー `not_adopted_final` は、証明行と同じトランザクションでadmissionが実際に終了しているかを `f125_not_adopted_closed` で確認する。終了していなければ、証明行だけのコミットを拒否する。

## 自動実行（Java）

`OrderEditNotAdoptedReconciliationService.reconcileDue` は、従来の照合に続けて第2段の処理を行う。

- 対象：`REVIEW_REQUIRED / REAUTHORIZATION_ABORTED` の未終了admissionのうち、operationが `ABORTED`、最終判定が `NOT_ADOPTED` のもの
- 処理：対象ごとに、READ COMMITTEDの短いトランザクションで `f125_not_adopted_close_unapplied` を呼ぶ

候補の抽出は対象を絞り込むだけで、終了してよいかの判断はDB関数が行う。失敗した場合（runtime停止中のSQLSTATE 55000を含む）は、warnログ `Order edit not-adopted closure deferred` を出し、次回のスケジュールで再試行する。スケジューラは既存の `OrderEditNotAdoptedReconciliationScheduler`（`app.payment.reconciliation.enabled`）をそのまま使う。

顧客・管理者の操作は不要。保留一覧（130-A）の注記も、この自動終了に合わせて変更した。

### 129-Bの起動時依存の修正

`orderEditExecutionService` は、Capture接続設定がなくてもアプリが起動できるよう `@Lazy` にしてある。129-Bの照合サービスは、これをコンストラクタで直接受け取っていたため、Capture接続設定がない環境ではアプリが起動できなくなっていた。

- 影響：全コンテキストを起動するテストが失敗していた（`IsolatedTestSmokeTest`・`OrderShippingPreparationPostgresTest` など。mainで再現を確認）。
- 修正：`ObjectProvider` で受け取り、照合対象があるときだけ取得するようにした。
- 終了処理（第2段）はこのサービスを使わないため、Capture接続設定がなくても動く。

## 対象外（今回は終了しない）

- 新与信が作られなかったケース、または新与信がFAILEDのケース：証明は `CANCELLED` と `NEW_ABORT` の組み合わせだけを受け付ける
- operationが `MANUAL_REVIEW` のケース、最終判定が `LATE_ADOPTION` のケース

いずれも従来どおり保留一覧に表示され、管理者の確認対象になる。

## テスト

`OrderEditReauthorizationIntegrationTest`（共有テストDBモード、25件すべて成功）：

- `notAdoptedOwnedCancellationClosesTheAdmissionAndReleasesTheShippingGate`：NOT_ADOPTED → 新与信取消 → admissionの自動終了までを本番の経路で確認する
  - 出荷ゲートが開くこと、保留一覧から消えること
  - その後PAY.JPへの通信がないこと
  - 同じversionでの再実行が冪等で、別versionは衝突になること
  - 証明行がappend-onlyであること
- `notAdoptedClosureRefusesUnfinishedWorkStaleVersionsDetachedProofAndStoppedRuntime`：
  - 取消前は拒否される
  - 汎用の終了処理は引き続き拒否される
  - 古いversionはCASで衝突する
  - 証明行だけのINSERTはコミット時に拒否される
  - runtime停止中は何も書かず、通信もしない
  - runtime再開後に1回で終了する

最終判定の行だけは、テスト用の仕掛けとしてトリガーを一時的に無効にして差し込んでいる。owned経路では締切が `14:00` に固定され、admission後は変更できないため。

最新マイグレーションの版数を確認する3テスト（`IsolatedTestSmokeTest`、`OrderShippingPreparationPostgresTest`、`AdminOrderShippingPreparationSecurityPostgresTest`）はV85に更新した。
