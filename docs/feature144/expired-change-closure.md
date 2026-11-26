# Feature144 新しい与信が期限切れになった不採用の注文変更を終わらせる

状態：実装済み（ブランチ feature/144-expired-change-closure、main への統合前）。基準は main e39e8fb（最新マイグレーション V112）。設計は 1〜9 章、変更点と結果は 11 章。

## 1. 目的

- 再与信まで成立したのに採用されず（最終判定 NOT_ADOPTED）、新しい与信が PAY.JP で期限切れになった注文変更を終わらせ、確保した在庫を戻し、受付を閉じる。
- 終わらせたあとは変更前の注文に戻る。元の与信も期限切れなら、Feature135 の「与信期限切れによる取消」で注文を取り消せる。
- PAY.JP には取消を送らない（既に期限切れで取り消されている）。GET で確認した証拠を残す。

## 2. 調査結果（注文 8、2026-11-26）

### 2.1 状態（開発 DB と PAY.JP テストモードの GET 2 回）

| 対象 | DB | PAY.JP |
|---|---|---|
| 注文 8（5,500 円、変更期限 11/07 14:00） | ORDERED、変更前の内容のまま | |
| 元の与信（決済 12） | AUTHORIZED | `canceled`、`cancellation_reason=expired`（期限 11/13 21:22、取消 21:41）、`amount_capturable=0`、`amount_received=null` |
| 新しい与信（決済 13、8,500 円） | AUTHORIZED、取引 SUCCEEDED（11/06 21:30） | 同上（期限 11/13 21:29）。metadata の注文番号・変更 ID は一致 |
| 注文変更 | MANUAL_REVIEW（NEW_FLOW_EVIDENCE_MISMATCH）、切替の決定なし、取り下げなし | |
| 最終判定 | NOT_ADOPTED・HOLD（11/16 11:39、判定時の状態 AUTHORIZED） | |
| 受付 | ACCEPTED・QUEUED（REAUTH_FINANCE_CONCLUSIVE）、owned、実行中の処理なし | |
| 在庫 | 1 個確保（HELD） | |

注文 8 は 11/06 の Feature125 開発中のブラウザテストで作られた。

### 2.2 なぜ終わらないか

1. 与信成立後、切替（元の与信の取消）が決まらないまま変更期限を過ぎた（開発中でアプリが止まっていた期間が長い）。
2. 11/13 に新旧の与信が期限切れ（PAY.JP v2 は期限切れの与信を `canceled`・`expired` にする。Feature135 で確認済み）。
3. 11/16 11:39 に最終判定 NOT_ADOPTED。11:40 の照会で `authorization()` の照合（`matchesFlow`）が通らず MANUAL_REVIEW。`canceled` の照合は `amount_received=0` を求めるが、期限切れの応答は `null` だった。
   - 照合が通っても、成立済みの与信の `canceled` は `ESTABLISHED_AUTH_CANCELED_EXTERNALLY` で MANUAL_REVIEW になる。
4. 出口がない。
   - Feature143：新しい与信が未成立（PENDING）の変更だけが対象。
   - Feature129-B（不採用の後片付け）：新しい与信の取消を送る経路で、MANUAL_REVIEW は飛ばされる。期限切れの与信には取消を送れない。
   - Feature135：終わっていない注文変更があると使えない。また AUTHORIZED の決済が 1 件だけの注文が対象（注文 8 は 2 件）。

### 2.3 今のコードでも起こりうるか（コードの読み取り、未再現）

与信成立後に切替が決まらず、不採用の後片付けが新しい与信の期限（国内 7 日）までに終わらないと同じ状態になる。本番では、アプリの長い停止や後片付けの失敗の継続が重ならない限り起こりにくい。このため自動の処理は作らず、管理者の操作だけにする。

## 3. 対象

次をすべて満たす注文変更。Feature125 の順（`f125_reauth_lock`）でロックして判定する。

- 一括編集（owned）の REAUTHORIZE。切替の決定なし、取り下げの記録なし、適用なし。
- 自動処理の期限（`automatic_until`）を過ぎている（11.1 の案 X'。設計時は「最終判定が NOT_ADOPTED」）。
- 状態が AUTHORIZED、または MANUAL_REVIEW で理由が NEW_FLOW_EVIDENCE_MISMATCH・ESTABLISHED_AUTH_CANCELED_EXTERNALLY。
- 新しい決済が PAYJP・CARD・AUTHORIZED、再与信の取引が SUCCEEDED の 1 件だけ。記録上の期限（V93）を過ぎている。保留中の取引なし。
- 元の決済が AUTHORIZED で、取消の記録なし（元の与信には触れない）。
- 受付が ACCEPTED・未終了、実行中の処理なし。在庫の確保が HELD。
- Feature125 runtime が有効。

## 4. 流れ

1. 管理者が注文詳細で「期限切れの注文変更を終了する」を押す（確認ダイアログ、備考は任意）。
2. 3 章を確認する（ロックなしの読み取り）。
3. 新しい与信の Payment Flow を GET する（TX の外）。次をすべて満たすときだけ進む。
   - `status=canceled`、`cancellation_reason=expired`、`amount_capturable=0`、`amount_received` が null か 0。
   - ID・金額・通貨・`capture_method=manual`・`livemode` が一致。metadata の注文番号と変更 ID が一致。
4. 1 つの TX で、ロック後に 3 章を再判定し、次を行う。
   - 再与信の取消取引（CANCEL、目的 ORDER_CHANGE_NEW_AUTH_ABORT）を SUCCEEDED で記録し、新しい決済を CANCELLED にする（POST は送らない。Feature135 の元の与信の扱いと同じ）。
   - 注文変更を ABORTED、在庫を RELEASED（入出庫 1 件）。
   - 受付を CLOSED_UNAPPLIED（新しい理由 NEW_AUTH_EXPIRED）。
   - 証拠の行（GET の結果の要約。カード情報・client secret は含めない）を記録する。
5. 結果を表示する。元の与信も期限切れなら、同じ画面に Feature135 の取消が表示される。

PAY.JP が `canceled`（expired）でない場合は何も書かず、「PAY.JP で与信がまだ期限切れになっていません」と表示する。

## 5. DB（V113）

- `order_change_expired_closures`：証拠の行。追記のみ（更新・削除・TRUNCATE 禁止）。注文変更・受付・新しい決済ごとに 1 行。
- 証明は Feature130-B（V85）・143（V111）と同じ形にする：証拠の行を記録した TX の中だけで、既存の守りが「この受付を NEW_AUTH_EXPIRED で閉じる」「この変更を ABORTED にする」「この決済を CANCELLED にする」「在庫を戻す」ことを認める。
- 遅延制約：証拠の行は、取消取引 SUCCEEDED・決済 CANCELLED・変更 ABORTED・在庫 RELEASED・受付終了のすべてがそろったコミットにだけ存在できる。逆も同じ。
- V94 の「変更期限後の取消は期限切れ取消だけ」は、目的 ORDER_CANCELLATION の取引だけが対象。本 Feature の取消取引（目的 ORDER_CHANGE_NEW_AUTH_ABORT）は対象外だが、証拠の行のない ORDER_CHANGE_NEW_AUTH_ABORT の SUCCEEDED を期限後に作れないよう、新しい制約で守る（既存の 129-B の経路を確認して、妨げないことをテストで確かめる）。
- 変更する既存の関数は、V112 時点の全文を基に差分だけを加える。

## 6. 画面

- 管理者の注文詳細：対象の注文に「期限切れの注文変更を終了する」を表示する。終了の記録（日時、操作者、備考、PAY.JP の期限と取消日時）を表示する。
- お客様：表示は既存のまま（変更前の内容）。メールは送らない。注文を取り消した場合は Feature135 の取消メールが送られる。

## 7. テスト

テストの中で期限切れの状態を作る方法：決済の期限の記録（V93）は推定値で、テストでは与信の時刻を過去にした fixture を使う。PAY.JP の応答はモックにする。トリガーは無効にしない。

- 対象条件の拒否（アプリと DB の両方）：最終判定なし、切替決定済み、取り下げあり、新しい決済が AUTHORIZED でない、期限前、元の与信が取消済み、受付中の処理あり、runtime 停止中。
- GET の応答ごとの扱い：`requires_capture`、`canceled` で理由が expired でない、金額・metadata の不一致、読めない応答 → 何も書かない。
- 終了後：決済 CANCELLED、取引 1 件、変更 ABORTED、在庫が 1 回だけ戻る、受付 CLOSED_UNAPPLIED（NEW_AUTH_EXPIRED）、元の与信は変わらない、Feature135 の取消が使えるようになる。
- 二重実行・同時実行で 1 回だけ。DB が証拠のない変更を拒否すること。
- 129-B、143、Feature135 が変わらないこと。
- 守りを外すとテストが失敗すること（コピー上）。回帰は変更した関数・表を使うテストを grep で洗い出す。版数の 3 テストを更新する。

## 8. 対象外

- 自動の終了（2.3 のとおり稀なため）。
- `matchesFlow` の変更（Feature125 の照合）。期限切れの応答でも MANUAL_REVIEW になるのは変えない。理由の表示（NEW_FLOW_EVIDENCE_MISMATCH）は残る。
- 与信が期限内の不採用の変更（Feature129-B の経路）。

## 9. 決めていただくこと

| # | 項目 | 案 |
|---|---|---|
| 1 | 操作 | 管理者の操作だけ（自動にしない） |
| 2 | 対象の状態 | AUTHORIZED と、MANUAL_REVIEW（NEW_FLOW_EVIDENCE_MISMATCH・ESTABLISHED_AUTH_CANCELED_EXTERNALLY）。最終判定 NOT_ADOPTED が必須 → 案 X' で「自動処理の期限を過ぎている」に変更（11.1） |
| 3 | 新しい決済の記録 | CANCEL 取引（ORDER_CHANGE_NEW_AUTH_ABORT）SUCCEEDED と決済 CANCELLED。POST は送らない |
| 4 | 受付の終了理由 | 新しい理由 NEW_AUTH_EXPIRED |
| 5 | 分割 | 分けない（V113・終了処理・画面を 1 ブランチ） |
| 6 | 注文 8 のその後 | 終了後、Feature135 の取消で注文を取り消す（取消メールが送られる） |

## 10. 適用と確認

- V113 の適用（アプリの再起動）、注文 8 のブラウザ確認（PAY.JP テストモードの GET）は事前に確認する。

## 11. 実装の結果（2026-11-27）

### 11.1 設計からの変更

- **案 X'（ご承認済み）**：対象の条件「最終判定 NOT_ADOPTED」は「自動処理の期限（`automatic_until`）を過ぎている」に変えた。V71 が一括編集の期限を 14:00 に固定するため、最終判定（14:05）をテストの中で作れない。Feature143 の案 X と同じ扱い。注文 8 はどちらの条件も満たす。
- 対象の条件には、ほかに次を加えた。受付中の処理がなく、すべての試行に結果があること。`f143_reauth_current`（V112 の基準の扱い）を満たすこと。開いている 143 の終了依頼がないこと。
- PAY.JP の GET は専用の `observeExpiredFlow` を使う（`GET /v2/payment_flows/{id}`）。既存の照合（`matchesFlow`）は変えていない。

### 11.2 実装

- V113：
  - 表 `order_change_expired_closures`（追記のみ）
  - 関数：`f144_change_closable`（対象の判定）、`f144_close_expired`（唯一の書き込み口）
  - 遅延チェック 2 つ：
    - 証拠の行があるときは、終了に必要な変更がすべてそろっていること
    - 期限後の ORDER_CHANGE_NEW_AUTH_ABORT の取消取引には、送信の記録か証拠の行があること
  - 終了後に決済・取引・変更を変えられないようにする守り
  - 既存の関数 8 つ：最新の定義を写し、本 Feature の分岐だけを加えた（`f125_reauth_assert_current`、`f125_reauth_inventory_update`、`f125_runtime_other_work`、`f125_execution_close_guard`、`f125_execution_final_check`、`f125_closure_capture`、`f125_admission_final_check`、`f125_closure_assert_final`）
- Java：
  - `ExpiredFlowObservation`、`PayJpPaymentGateway.observeExpiredFlow`
  - `ExpiredChangeClosureService`：GET は TX の外。結果は CLOSED、NOT_EXPIRED、UNREADABLE、CHANGED のどれか。
  - `POST /admin/orders/{id}/close-expired-change`
  - 注文詳細の表示：ボタンと終了の記録

### 11.3 テスト

- `OrderChangeFlowClosurePostgresTest`：49 件 PASS（Feature144 は 15 件）。確かめたこと：
  - 3 つの状態がどれも終了すること
  - 次の場合は何も書かないこと：
    - `requires_capture` や、期限切れ以外の取消の応答
    - 読めない応答、ID が別の Flow の応答
    - 別の変更の応答（DB が拒否）
    - 取消理由が期限切れでない応答（DB が拒否）
  - 次の場合は対象外になること：記録上の期限の前、自動処理の期限の前、一括編集の稼働の停止中
  - DB の守り：証拠のない取消取引、直接の挿入、終了後の変更、証拠の行の更新や削除を拒否すること。2 回目の終了は何もせず false を返すこと。
  - 管理者の画面：CSRF、表示、二重操作
- 回帰：変更した関数・表を使うテストを grep で洗い出し、PAY.JP の接続部分の単体テストを加えた。合わせて 147 クラスがすべて PASS（約 3,400 件）。
  - 初回の失敗 3 クラスは、すべてテストの側を直した。
    - `OrderReauthorizationPostgresTest`：V107 までのスキーマで画面を開くと、新しい表がない。143 と同じ方法で、新しいサービスを Mock にした。
    - `LegacyBaselineChangeClosureUpgradePostgresTest`：版数の期待値を 112 から 113 に変えた。
    - `OrderChangeFlowClosurePostgresTest` の `closesAfterTheFlowIsReadAsCanceled[3]`（Feature143 のテスト）：1 回だけ在庫が確保されないまま止まった。再実行では PASS。原因は確かめていない。4 秒の自動処理の期限を、高い負荷の中で超えた可能性がある。
  - テストではないクラス（Mock・Fixture）が計画に混ざり、4 行が起動できなかった。テストクラスだけで再実行し、PASS した。
- 守りを外した確認（コピー上）：14 通りのうち 12 通りを検出した。
  - 初回に検出できなかった 2 通り（DB の取消理由の確認、Java の Flow ID の照合）は、テストを 2 件加えて検出できるようにした。
  - 検出できないまま残ったものは 2 通り。
    - `f144_change_closable` の状態の条件：ほかの条件（切替・取り下げなし、新しい決済が AUTHORIZED、取引 1 件）で、ほぼ同じ範囲に絞られる。ただし、ほかの理由の MANUAL_REVIEW で重なる場合がないことは確かめていない。
    - `f125_closure_capture` の形の確認：証拠の行を書くのは `f144_close_expired` だけなので、二重の守りになっている。

### 11.4 確かめていないこと・既知の制約

- 本物の PAY.JP の応答による終了：ブラウザでの確認（注文 8）で確かめる。
- 129-B がすでに取消の依頼を作っている AUTHORIZED の変更（`abort_request_id` あり）は対象外。この場合は終わらない（注文 8 は該当しない）。
