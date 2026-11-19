# Feature135 PAY.JP 与信期限の監視と対応

## 1. PAY.JP の仕様（2026-11-18 確認）

公式ドキュメント・告知と、テストモードへの GET（Payment Flow 2 件）で確認した。

| 項目 | 内容 |
|---|---|
| 期限 | 国内発行カード 7 日、海外発行カード 5 日（2026-09-14 以降）。国内は `request_extended_authorization` で 25 日まで延長可（本システムは指定しない）。 |
| 基準 | 応答の `expired_at` を正とする（カードにより短縮されるため、日数を仮定しない）。 |
| 期限切れ後 | 2026-10-19 以降、Payment Flow は `canceled`（`cancellation_reason=expired`）になり、Webhook `payment_flow.canceled` が送られる。変更前からの残存分は補正されるが Webhook は送られない。 |
| 期限切れと取消のずれ | テストモードで `canceled_at` は `expired_at` の約 18 分後。この間 GET では `requires_capture` に見えるため、ローカルで `expired_at` と比較して判定する。 |
| 売上確定 | 失効した支払いは確定できない（v2 のエラーコードは未記載）。 |
| 3DS | 記載なし。観測では `expired_at` は与信成立のちょうど 7 日後。 |
| テストモード | 早送りの手段はない。実際に期限まで待つと自動で `canceled` になる（開発 DB で確認）。 |

参照：
- https://docs.pay.jp/v2/guide/status-management/auth-capture
- https://pay.jp/info/2026-10-09-120000
- https://pay.jp/info/2026-03-03-130000

## 2. 調査時点の問題（Feature135 着手前）

- Payment に期限の記録がなかった。
- `payment_flow.canceled` は、保留中の CANCEL 取引がないと何もしない（期限切れはこれに当たる）。
- 照合処理は AUTHORIZED を対象にしない。不整合監査は「AUTHORIZED／CANCELED」を汎用の不整合として検出するだけ。
- 売上確定は注文を SHIPPED にしてから PAY.JP を呼ぶ。期限切れだと失敗し、照合でも `canceled` を PENDING に変換するため、出荷済みで CAPTURE が永久に PENDING になる。
- 倉庫出力（V86）・出荷準備は期限を確認しない。

## 3. サブ Feature

| | 内容 | 状態 |
|---|---|---|
| A-1 | 期限の記録（V93）と出荷のゲート | 完了 |
| A-2 | 管理画面の表示（注文一覧の列・絞り込み、詳細の期限・不整合） | 完了 |
| B-1 | 管理者による「与信期限切れによる取消」（V94） | 完了（ブラウザ確認済み） |
| B-2 | 出荷済みで売上確定が PENDING のまま Flow が `canceled` のものを FAILED として確定（V95） | 完了（テストで確認、ブラウザ確認なし） |
| B-3 | 推定値の期限を PAY.JP の `expired_at` に置き換える GET（既定で無効） | 完了（ブラウザ確認済み） |
| C-1 | 管理画面での通知と案内（トップの件数、一覧の絞り込み、注文詳細の案内文） | 完了（ブラウザ確認済み） |
| C-2 | お客様への案内（与信期限切れによる取消の専用メール文面、注文詳細の表示）（V96） | 完了（ブラウザ確認済み） |

## 4. A-1 の設計（V93）

### 4.1 記録 `payment_authorization_expiries`

- Payment 1 件につき 1 行。payments に列を足さないのは、payments にかかる Feature125 の更新ガードへの影響を避けるため。
- 不変条件：**AUTHORIZED になった Payment には必ず行がある。** payments の AFTER トリガー `zz_f135_payment_authorized` が、AUTHORIZED への遷移（INSERT を含む）で ESTIMATED 行を作る。
  - 推定値は `payments.created_at + 5 日`。与信は Flow 作成より前には起きず、5 日は最短の既定期間（海外カード）なので、実際の期限より遅くならない。
- アプリは与信を反映するのと同じトランザクションで、PAY.JP の `expired_at` に一度だけ置き換える（`source=PROVIDER`）。経路は初回与信（`PaymentService.applyAuthorizationResult`）と注文変更・一括編集の再与信（`OrderReauthorizationTransactions`）。
  - 値が範囲外（基準時刻以前、または 61 日超）のときは推定値のまま残し、警告ログを出す。与信の反映自体は失敗させない。
- トリガー `f135_expiry_guard`：
  - INSERT は AUTHORIZED の Payment だけ。値は常にトリガーが決める。
  - UPDATE は ESTIMATED → PROVIDER の 1 回だけ。
  - DELETE は Payment 自体の削除に伴う CASCADE だけ。TRUNCATE は不可。
- 既存の AUTHORIZED は、マイグレーションで推定値を補う。

### 4.2 ゲート（アプリと DB の両方）

| 段階 | 条件 | アプリ | DB |
|---|---|---|---|
| 倉庫出力 | 期限まで 24 時間超 | `validateCardPaymentForShippingPreparation` → 保留理由 `AUTHORIZATION_EXPIRING` | `f135_line_expiry_guard`（出力行） |
| 管理者の出荷準備 | 期限まで 24 時間超 | 同上（メッセージ表示） | `f135_order_expiry_guard`（準備開始） |
| 売上確定の開始 | 期限前 | `CaptureShipmentOperations` の NEW 分岐（SHIPPED にする前） | `f135_order_expiry_guard`（ORDERED→SHIPPED） |

- 時刻は DB の時計（`timezone('Asia/Tokyo', clock_timestamp())`）で判定する。アプリも同じ関数 `f135_authorization_valid_beyond` を使う。
- 出荷取込（Feature132）では、売上確定の開始が拒否されると行が `SHIPMENT_REFUSED` になる。出荷済みで売上確定が PENDING のまま残る状態は作らない。
- 既存の、出荷済みで CAPTURE が PENDING のものは B で扱う。

## 5. B-1 の設計（V94）

### 5.1 方針の経緯

- 安全検査（`PaymentSafetyClassifier`）は、CANCELLED の Payment に「注文 CANCELLED・CANCEL 取引成功・履歴の実行者一致」を求める。Payment だけを CANCELLED にすると不整合になるため、期限切れは**注文の取消**として反映する。
- 既存の取消（お客様・管理者）は変更期限（`OrderModificationGuard`）までしかできない。与信が期限切れになる時点では変更期限を過ぎているため、管理者専用の操作を新設し、**この操作に限り変更期限後でも取消を認める**（2026-11-18 承認）。

### 5.2 処理（`ExpiredAuthorizationCancellationService`）

1. 読み取りの事前確認：注文が ORDERED、発送準備前、未解決の注文変更なし、期限の記録が切れた AUTHORIZED の PAY.JP カード決済がちょうど 1 件。
2. トランザクションの外で Payment Flow を GET し、`canceled` であることを確認する。そうでなければ何も変えずに拒否する（期限から PAY.JP の取消まで約 18 分のずれがある）。
3. 1 回の書き込みトランザクション：Order をロック → Payment を id の昇順でロック（`PaymentService.startExpiredAuthorizationCancellation`）→ すべて再確認 → CANCEL 取引（目的 ORDER_CANCELLATION、実行者 ADMIN）→ V94 の監査行 → 取消結果の反映 → 注文の取消・在庫戻し・履歴・お客様への注文取消メール（既存の `cancelOrderAfterPaymentCancellation`）。
- PAY.JP への POST は送らない。冪等性：注文が CANCELLED なら ALREADY_CANCELLED を返し、何もしない。

### 5.3 DB の担保（V94）

- `payment_expired_cancellation_observations`：POST なしで確定した取消の監査行。追記専用。CANCEL 取引の Payment の期限が DB の時計で切れている場合だけ書ける。
- 遅延トリガー `f135_late_cancellation_guard`：変更期限以降に作られた ORDER_CANCELLATION 取引は、コミットまでに監査行が必要。通常の取消は変更期限前にしか作られない。

## 6. B-2 の設計（V95）

- PAY.JP の Flow を GET で読む 3 か所（定期照合・管理者の「売上確定の再確認」・発送の再実行）で、`canceled` は売上確定を FAILED として確定する（`CaptureResult.fromFlowState`）。失敗コードは `payment_flow_canceled`、失敗理由に `cancellation_reason`（英小文字・数字・`_` のみ。それ以外や欠落は `unknown`）を含める。理由は問わない（取り消された与信からは売上確定できないため）。
- Webhook `payment_flow.canceled` は、従来どおり取消の照合を先に行い、処理中の取消がない（SKIPPED）ときだけ売上確定の照合に回す。
- 注文は SHIPPED、決済は AUTHORIZED のまま（決済を CANCELLED にすると、注文 CANCELLED を求める安全性チェックの不変条件と矛盾するため）。管理画面は既存の「決済要確認」、倉庫の発送取込の行は既存の流れで CAPTURE_FAILED になる。
- 監査：`payment_capture_cancellation_observations`（追記のみ、注文 ID・確認日時はトリガーが決める）。書けるのは、発送済み・未入金の注文の売上確定対象の決済で、PENDING/FAILED の CAPTURE 取引のみ。
- DB の強制（遅延制約トリガー）：失敗コード `payment_flow_canceled` の CAPTURE FAILED と監査行は、コミット時に必ず対になる。それ以外の FAILED（既存の経路）は変えない。
- 同じ `canceled` を再度受けた場合（Webhook と照合の競合）は ALREADY_APPLIED で何も変えない。管理者の再確認は結果 FAILED を表示し、以後の再確認は既存どおり「安全に確認できない」で止まる。
- お客様への連絡・別の方法での請求は C で扱う。

## 7. B-3 の設計（マイグレーションなし）

- `AuthorizationExpiryRefreshScheduler`（`app.payment.authorization-expiry-refresh.enabled=${AUTHORIZATION_EXPIRY_REFRESH_ENABLED:false}`、間隔 `AUTHORIZATION_EXPIRY_REFRESH_FIXED_DELAY`（既定 10 分）、1 回 20 件、再問い合わせ間隔 6 時間）。
- 対象：与信期限が ESTIMATED、決済が AUTHORIZED の PAY.JP カード決済（PAY.JP の ID あり）、注文が取消済みでない、与信の基準時刻から 25 日以内（国内の延長上限。過ぎたものは確実に失効しているため問い合わせない）。期限の早い順。
- PAY.JP への GET（`retrieveAuthorization`）はトランザクションの外。`requires_capture` で `expired_at` があるときだけ、短いトランザクションで注文 → 決済（id 昇順）をロックし、同じ PAY.JP ID の AUTHORIZED 決済で ESTIMATED のままであることを再確認してから V93 の一度きりの置き換えを行う。範囲外の値は V93 の範囲チェックで記録されない。
- `canceled` など記録できない応答や範囲外の値は、同じプロセス内では 6 時間問い合わせない（再起動で消える）。GET の失敗は次回に再試行。
- 推定で期限切れでも、PAY.JP の値で有効なら、発送準備などのゲートは PAY.JP の値で判定される。
- 実行ごとに件数（対象・記録・記録対象外・競合・失敗）をログに出す。応答の中身は出さない。

## 8. C の設計

- C-1（マイグレーションなし）：管理画面トップの「決済要対応」に「カード与信期限切れ（未発送）」「カード与信期限切れ・24時間未満」「発送済み・売上未確定（与信取消）」の件数を出し、注文一覧の同名の絞り込みへリンクする（取得失敗でもトップは表示する）。一覧の絞り込みに「発送済み・売上未確定（与信取消）」（V95 の監査行があり、SHIPPED で未入金）を追加。注文詳細に状況ごとの案内文（期限切れで取消可能／取消不可、売上未確定（与信取消）、与信期限切れによる取消の記録）を出す。
- C-2（V96）：V91 の `f133_on_status_change` を置き換え、ORDER_CANCELLED の元データに `authorizationExpired`（その注文に V94 の監査行があるか。遅延トリガーなので同じトランザクションの監査行が見える）を加える。V96 より前に記録された通知は元データを変えない（キーなし＝false 扱い）。該当する通知は件名「ご注文をキャンセルいたしました（お支払い期限切れ）」で、与信期限切れ・請求なし・再注文の案内を本文に入れる。お客様の注文詳細にも同じ趣旨を表示し、管理者の案内文も「取消のお知らせメールで再注文をご案内します」に改める。
- 入れないもの：期限切れの自動取消（B-1 は管理者の判断で取り消す案A）、管理者へのメール通知（既存の監査通知は目的が違い、既定で無効）、期限前の再与信（お客様の認証が必要で規模が大きい。必要なら別 Feature）。

## 9. 開発 DB の状況（2026-11-18）

- AUTHORIZED 20 件のうち 15 件は PAY.JP 上で `canceled`（不整合監査で OPEN として検出済み）。V93 適用後は推定値で期限切れとなり、出荷できない。B で CANCELLED に反映する。
- PENDING の Payment 27（注文 17）は、注文変更の再与信でお客様が確認を完了しなかったもの。注文変更は `MANUAL_REVIEW`（`UNSETTLED_AUTH_AFTER_GRACE`）。PAY.JP 上は `requires_payment_method` で、与信枠は確保されていない。注文 8 とともに手動判断とする。
- 2026-11-19：開発 DB は V96。C-2 のブラウザ確認で注文 5 を与信期限切れとして取り消し、通知 #11 が専用文面で送信された（Mailpit）。V96 より前に取り消された注文 4・15 の通知（#10・#9）は従来の文面で送信、注文 13 の通知（#8）は 24 時間を過ぎていたため送信されず期限切れになった。

## 10. 完了（2026-11-19）

Feature135 は A-1〜C-2 のすべてを main に統合して完了した。B-3 の期限更新（`AUTHORIZATION_EXPIRY_REFRESH_ENABLED`）は既定で無効のままとする。
