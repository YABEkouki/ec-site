# Feature143 再与信が完了しなかった注文変更を終わらせる

状態：143-A・143-A2 は main に統合済み（4e402ca、V111〜V112）。143-B は設計承認済み（15 章、2026-11-26）、実装とテストが完了（16 章。main 未統合、ブラウザ未確認）。

## 1. 目的

- 一括編集の再与信が完了しないまま止まった注文変更を終わらせ、確保した在庫を戻し、受付を閉じる。
- 終わらせたあとは変更前の注文に戻る。元の与信が有効なら出荷でき、失効していれば Feature135 の期限切れ取消を使える。
- 新しい与信が取り消されたと PAY.JP で確認できるまで、在庫の戻しと受付の終了は行わない。

## 2. 調査結果

### 2.1 注文 17（開発 DB、PAY.JP テストモードの GET 2 回）

| 対象 | 状態 |
|---|---|
| 注文変更 | MANUAL_REVIEW（UNSETTLED_AUTH_AFTER_GRACE）、最終判定は NOT_ADOPTED・HOLD |
| 新しい与信（決済 27、2,190 円） | DB は PENDING、PAY.JP は `requires_payment_method`（`three_d_secure_failed`）、取消なし |
| 元の与信（決済 26、1,710 円） | DB は AUTHORIZED、PAY.JP は `canceled`（expired、期限 11/17 01:20:57、取消 11/17 01:41:20） |
| 在庫 | 商品 55 を 1 個確保したまま（HELD） |
| 受付 | ACCEPTED・RECOVERY_PENDING（REAUTHORIZATION_WAITING）、実行中の処理なし |

最後の試行は 3D セキュアの失敗だった。

### 2.2 なぜ終わらないか

- 新しい与信の取消は、与信が成立した（AUTHORIZED）場合にしか始まらない（`decideLocked` → `prepareAbort`）。
- 一括編集の自動処理の期限（`automatic_until`）は、注文 17 では受付の 10 分後（DB の上限は 1 時間）。期限を過ぎると変更は採用されず、新しい処理の取得（claim）も DB が拒否する。
- 期限後、`requires_payment_method`・`requires_confirmation`・`processing` を照会すると MANUAL_REVIEW になる。14:05 を待たない。
- `requires_action` は REQUIRES_ACTION のまま残る。
- MANUAL_REVIEW は、照会・Webhook・129-A/B のすべてで飛ばされる。
- 129-B は `OrderEditExecutionService.reconcile` から実行を取得し直す方式だが、期限後は取得できない。期限後の 129-B が実際に進むかは、コードの読み取りでは進まない見込み（未再現）。このため、本 Feature は実行の取得に頼らない。

### 2.3 使える土台

- PAY.JP の取消は与信の成立前でも効く（`requires_payment_method` は Feature142 の事前確認、3D セキュア途中の注文は 142 のブラウザ確認で確認済み）。
- `f125_reauth_inventory_update` は、新しい決済が FAILED（FLOW_CANCELED）で、状態が AUTHORIZING・AUTH_UNKNOWN・REQUIRES_ACTION・ABORTED なら、在庫の戻しを既に認めている。
- MANUAL_REVIEW 以外では、`authorization()` が canceled を観測すると、決済を FAILED、在庫を戻し、注文変更を ABORTED にする。
- 足りないのは、取消を送る記録と保留、MANUAL_REVIEW からの反映、受付を閉じる証明（`f125_not_adopted_*` は CANCELLED と NEW_ABORT の組だけを受け付ける）。

## 3. 対象

次をすべて満たす注文変更。Order、Payment（id 昇順）の順にロックし、既存の `f125_reauth_lock` の順序に従って判定する。

- 一括編集（owned）の REAUTHORIZE、切替の決定なし、取り下げの記録なし。
- 状態が AUTHORIZING・REQUIRES_ACTION、または MANUAL_REVIEW で理由が UNSETTLED_AUTH_AFTER_GRACE。
- `automatic_until` を過ぎている（最終判定の有無は問わない）。受付は ACCEPTED・未終了・RECOVERY_PENDING で、実行中の処理なし。
- 新しい決済が PAYJP・CARD、PENDING か REQUIRES_ACTION、Payment Flow の ID あり。取引は PENDING の再与信 1 件だけ。作成の送信は OBSERVED。
- 元の決済が AUTHORIZED（DB）。元の与信の取消・NEW_ABORT の記録なし。
- 在庫の確保が HELD で、証拠が受付時と一致する（`f125_reauth_assert_current`）。
- Feature125 runtime が有効。

## 4. 流れ

1. 受付（TX1）：3 章を確認して REQUESTED を記録。
2. GET（TX の外）。
   - `requires_capture`：RELEASED。既存の処理に戻す（不採用なので新しい与信を取り消す経路）。ただし MANUAL_REVIEW では反映できないため、5.2 のとおり扱う。
   - `processing`・照会失敗：RELEASED（時間をおく）。
   - `canceled`：送らずに 5 へ。
   - それ以外の未完了：3 へ。
3. SENDING と冪等キーを記録（TX2）。
4. 取消の POST（TX の外、`abandoned`）→ GET（TX の外）。
5. 反映（TX3）：`canceled` を、金額・通貨・metadata（注文番号・変更の ID）まで照合して確認し、同じ TX で次を行う。
   - 再与信の取引を FAILED（FLOW_CANCELED）、新しい決済を FAILED。
   - 注文変更を ABORTED、在庫を RELEASED（入出庫 1 件）。
   - 受付を CLOSED_UNAPPLIED（REAUTH_UNFINISHED）、終了の記録を CLOSED。

結果が不明・エラーのときの扱いは Feature142 と同じ（同じキーで再送、23 時間で失効、ERROR は 10 分以上空けて合計 5 回まで、以後は管理者）。

## 5. 競合と保留

### 5.1 保留（REQUESTED・SENDING の間）

- 与信成功の反映をしない（`authorization()` は変更せず WAITING）。`decide`・`prepareAbort`・受付の実行を始めない。登録カードの送信と本人認証の開始を受け付けない。
- 取消が勝った場合は FAILED・FLOW_CANCELED で終わる。変更は採用されないので、お客様への影響は不採用と同じ。
- REJECTED なら保留を解き、GET の状態で既存の処理に戻す。

### 5.2 MANUAL_REVIEW で与信が成立していた場合

自動では進めず、終了処理を RELEASED にして「与信が成立しているため終了できない」と表示する（件数を見て別 Feature で検討）。新しい与信は PAY.JP の期限（約 7 日）で失効する。

### 5.3 決済 26 のように元の与信が失効している場合

元の決済の DB の状態は変えない。終了後、Feature135 の操作で取り消す。元の与信が有効なら変更前の内容で出荷できる。

## 6. DB（V111）

- `order_change_flow_closures` と `order_change_flow_closure_sends`（状態と遷移は Feature142 と同じ形。削除・TRUNCATE・書き換え禁止）。
- 保留のトリガー：新しい決済の AUTHORIZED、再与信の取引の SUCCEEDED、注文変更の状態の変更（ABORTED を除く）、NEW_ABORT の記録、登録カードの送信の挿入。
- 受付を閉じる証明は専用の表と関数にする（`f125_not_adopted_*` は変えない）。受付の最終チェックなど、既存の関数には REAUTH_UNFINISHED の組だけを追加する。
- 遅延制約：CLOSED は、注文変更 ABORTED・決済 FAILED・在庫 RELEASED・受付終了のすべてがそろったコミットだけ。逆も同じ。
- 変更する既存の関数は、V110 時点の全文を基に差分だけを加える。V96 より後の定義であることを確認してから書く。

## 7. 分割

| | 内容 |
|---|---|
| 143-A | V111 と終了処理、管理者の操作（注文 17 で確認） |
| 143-B | 期限を過ぎた対象の自動終了（既定は無効。期限後の猶予を置く）。未完了のまま期限を過ぎた owned の変更を MANUAL_REVIEW にしない |

調査時の案（A＝再発防止、B＝既存の解消）から、順番を入れ替えた。期限後はすぐ MANUAL_REVIEW になるため、どちらも MANUAL_REVIEW から終わらせる同じ土台が必要で、管理者の操作で先に確かめるほうが安全。

## 8. 画面

- 管理者の保留一覧と注文詳細に「完了しなかった注文変更を終了」を追加する（確認ダイアログ、備考は任意、記録を表示）。
- お客様には「ご注文は変更前の内容のままです」と表示する（既存の文面）。メールは既存の不成立のメールに従う。新しいメールは作らない。

## 9. テスト

- 対象条件の拒否（アプリと DB の両方）。
- 与信成立との競合：受付前、GET と POST の間、POST の後。Webhook・照会・画面。
- POST の応答ごとの扱い、同じキー、各 TX の直後の停止からの復旧、二重実行。
- 在庫の戻し・履歴・受付の終了が 1 回だけであること。終了後に出荷ゲートが開くこと、135 の取消に進めること。
- runtime 停止中は何も書かず、通信しないこと。
- 3DS 失敗の取りやめ、129-B、LATE_ADOPTION が変わらないこと。
- 守りを外すとテストが失敗すること（スクラッチのコピー。DB トリガーは無効にしない）。
- 回帰は変更した関数・表を使うテストを grep で洗い出す。版数の 3 テストを更新する。

既知の制約：V71 が変更期限を 14:00 に固定するため、最終判定のあとの状態は自然な手順では作れない（130-B はトリガーを一時的に無効にした）。本 Feature では無効にしない。自動処理の期限は `f125_execution_initialize` の引数で短くできるので、条件を期限の経過にした（案 X）。

## 10. 対象外

- 注文 8（NEW_FLOW_EVIDENCE_MISMATCH、決済 13 は AUTHORIZED）。原因が違う。別に調べる。
- 一括編集でない従来の変更（Feature140 で新規は停止）。
- RESERVED・AUTH_UNKNOWN（Payment Flow の作成結果が不明）、LATE_ADOPTION。
- お客様自身の操作。

## 11. 決めていただくこと

| # | 項目 | 案 |
|---|---|---|
| 1 | 順番（7 章） | 143-A＝管理者の操作、143-B＝自動 |
| 2 | 終わらせる時期 | 自動処理の期限（`automatic_until`）のあと。当初案は最終判定のあとだったが、トリガーを無効にせずに検証できないため変更した（案 X） |
| 3 | 自動終了の既定 | 無効（Feature142 と同じ） |
| 4 | 5.2（MANUAL_REVIEW で与信成立） | 自動では進めず、終了しない |

## 12. 適用と確認

- V111 の適用（`./start-dev.sh`）、PAY.JP テストモードの取消を含むブラウザ確認は、事前に確認する。runtime の再承認が必要になる場合がある。
- 注文 17・8 は残す（17 は 143-A の確認用）。
- 戻すとき：V111 は削除せず、新しいマイグレーションで外す。SENDING が残っている間は外さない。

## 13. 143-A の結果

### 13.1 設計との差

- 操作は一覧ではなく注文詳細に置いた。
- 与信が成立していた場合は、MANUAL_REVIEW 以外の状態でも終了しない（11 章の項目 4 を全状態に広げた）。
- AUTHORIZED のまま期限を過ぎた変更は対象外。129-B が期限後に進まない見込みだが、再現していないため別途調査する。
- トリガーを設計より 2 つ増やした（終了後の変更の拒否、終了処理中の実行開始の拒否）。

### 13.2 テスト

- `OrderChangeFlowClosurePostgresTest` は 25 件 PASS（共有テスト DB、トリガーの無効化なし）。
- 回帰は 215 クラスすべて PASS。214 クラスは初回で 4,574 件（失敗 0、エラー 0、スキップ 2）。`OrderEditOperationalCardPostgresTest` は初回、テスト開始前に `Test launch failed` で止まり、変更せずに再実行して 23 件 PASS。原因は特定できていない。
- 回帰のあとに変えたのは `OrderChangeFlowClosurePostgresTest` だけ（23 件から 25 件）。本体のコードは変えていない。

### 13.3 守りを外した確認

コピー上で 32 か所を 1 つずつ外した。対象はアプリの判定 3、トリガー 19、既存関数への追加 8、終了条件 2。

- 29 か所はテストが失敗し、検出できた。うち 5 か所は最初は検出できず、テストを強めた（progressBlocked、request_fence、failure_fence、closure_capture の形の限定、決済の状態の条件）。
- 3 か所は検出できない。V111 と既存のトリガーを読み、手前の守りが先に止める二重の守りと判断して残した。
  - `f143_attempt_fence`：V72 のトリガーが、QUEUED 以外の受付と期限後の実行開始を先に拒否する。
  - `f143_closed_commit`、`f143_aborted_commit`：CLOSED と保留中の ABORTED には同じトランザクションの証明が必要で、`unfinished_final` がコミット時に検査する。
- 終了条件（`f143_change_closable`）のうち外したのは期限と決済の状態の 2 つ。ほかの条件は個別に外していない。

### 13.4 確認できていないこと

- PAY.JP の実 API での取消と、ブラウザでの操作。
- 注文 17 は、開発 DB への SELECT で終了条件を満たすことだけを確認した（V111 未適用のため、証明の表の条件を除いて評価）。

## 14. 143-A2 V81 より前の受付の基準

### 14.1 不具合

注文 17 で終了を押すと「終了処理の結果を確認できませんでした」と表示された。終了の行を書く前に `f125_reauth_assert_current`（V75）が「Unexpected business or financial change outside owned reauthorization」で拒否していた。終了の行は書かれず、PAY.JP にも何も送っていない。

原因は V81 で `orders` に加えた `acceptance_mode`（既定 'LEGACY'）と `accepted_at`（null）。受付時に保存した基準 `unapplied_baseline` は注文の行を丸ごと JSON にしたもので、V81 より前の受付ではこの 2 つのキーがない。今の注文にはあるため、比較が必ず一致しない。古い形の基準を持つ受付は 19 件、そのうち終わっていないのは注文 8（QUEUED）と 17（RECOVERY_PENDING）の 2 件。

### 14.2 直したこと（案 A）

- V112 `f143_legacy_order_local(baseline, actual)`：次をすべて満たすときだけ、今の注文から 2 つのキーを除いて比較する。それ以外は今の値をそのまま返す。
  - 基準の注文に 2 つのキーがどちらもない。
  - 今の注文が `acceptance_mode='LEGACY'` かつ `accepted_at` が null（V81 で入った既定値のまま）。
- V112 で `f125_reauth_assert_current` を置き換え、比較の前にこの関数を通す。ほかは V75 と同じ。
- 案 A との差 1：Java 側の実行開始時の比較（`OrderEditApplicationPlanRepository.prepare`）も同じ規則にした。DB 側だけ直すと、古い受付の実行開始が `REAUTHORIZATION_INITIALIZATION_UNCONFIRMED` で止まることがアップグレードのテストで分かった。V112 がない DB では従来の比較を使う。
- 案 A との差 2：終了ボタンの表示判定 `f143_change_closable` に、今の状態が基準と一致するかの確認（`f143_reauth_current`）を加えた。押しても DB が拒否する変更にボタンを出さないため。稼働が止まっている、または世代が違うときは確認せず false にする。
- 変えていないもの：`f125_financial_assert_current` は REAUTHORIZE では使われない（自分の行として比較から除かれる）。`f125_reauth_conclusive` も基準を丸ごと比べるが、実行記録のない受付にしか使われない。

### 14.3 テスト

- 新規 `LegacyBaselineChangeClosureUpgradePostgresTest`（独立コンテナ、2 件 PASS）：V80 で受付を作り、V112 まで上げてから実行して終了する。終了後は注文変更が CLOSED、在庫が戻り、元の与信は AUTHORIZED のまま。比較から除く条件は関数を直接呼んで確かめる。
- `OrderChangeFlowClosurePostgresTest` は 26 件 PASS（`aChangedOrderIsNotOffered` を追加）。
- 回帰は 110 クラスすべて初回で PASS。2,592 件（失敗 0、エラー 0、スキップ 2。スキップは再起動用の既存テスト）。

### 14.4 守りを外した確認

6 か所を 1 つずつ外し、すべてテストが失敗した。

| 外したもの | 失敗したテスト |
|---|---|
| `f125_reauth_assert_current` の新しい比較 | `legacyAdmission` |
| Java 側の実行開始時の比較 | `legacyAdmission` |
| 基準に 2 つのキーがないときだけ除く条件 | `onlyTheUpgradeDefaultsAreLeftOut` |
| 値が既定値のときだけ除く条件 | `onlyTheUpgradeDefaultsAreLeftOut` |
| 表示判定に加えた一致の確認 | `aChangedOrderIsNotOffered` |
| その中の稼働状態の確認 | `aStoppedRuntimeWritesAndSendsNothing` |

### 14.5 確認できていないこと

- 開発 DB への V112 の適用と、注文 17 のブラウザでの終了、PAY.JP テストモードでの取消。
- 注文 8 は別の理由（NEW_FLOW_EVIDENCE_MISMATCH）で止まっており、対象外。

## 15. 143-B 自動の終了（設計案）

基準は main 4e402ca（最新マイグレーション V112）。

### 15.1 方針

- 143-A の終了処理（`ChangeFlowClosureService`）をそのまま使い、定期処理が対象を選んで受け付けるだけにする。PAY.JP への取消、反映、保留、DB の守りは 143-A と同じ。
- 形は Feature142-B に合わせる。既定は無効。

| 設定 | 既定 | 範囲 |
|---|---|---|
| `ORDER_CHANGE_CLOSURE_ENABLED` | false | |
| `ORDER_CHANGE_CLOSURE_AFTER`（自動処理の期限 `automatic_until` からの猶予） | 60 分 | 10 分〜7 日 |
| 実行の間隔 | 5 分 | 10 秒〜24 時間 |
| RELEASED・REJECTED のあとの間隔（管理者の操作による終了も同じ） | 6 時間 | 10 分〜7 日 |
| 1 回の件数 | 20 件（期限の古い順） | 1〜100 |

- 対象は `f143_change_closable` を満たす変更（AUTHORIZING・REQUIRES_ACTION・MANUAL_REVIEW の UNSETTLED_AUTH_AFTER_GRACE）。一覧で選び、Order と注文変更をロックした状態でもう一度判定してから受け付ける。受付中・送信中の終了には触れない。
- 記録の操作者は SYSTEM、備考は「自動終了」。
- 1 件の失敗で全体を止めない。送信中の確かめ直しは既存の定期処理（143-A）が行う。

### 15.2 7 章からの変更（承認が必要）

7 章では「未完了のまま期限を過ぎた owned の変更を MANUAL_REVIEW にしない」を 143-B に含めていた。これは行わないことを提案する。

- 143-A で MANUAL_REVIEW（UNSETTLED_AUTH_AFTER_GRACE）から終了できるようにしたため、自動の終了もそのまま対象にできる。
- MANUAL_REVIEW への移行は `OrderReauthorizationTransactions.authorization` の共通の経路にあり、Feature125・128・129 の状態遷移に影響する。変えなくても目的は達成できる。
- 自動の終了が無効のあいだは、MANUAL_REVIEW のほうが管理者の目に留まりやすい。

### 15.3 DB

マイグレーションはない（Feature142-B と同じ）。期限前の終了は V111・V112 が既に拒否する（`automatic_until` を過ぎていること）。猶予はアプリの設定で、10 分未満では起動しない。

### 15.4 お客様への連絡

- 終了の処理自体はメールを送らない（143-A と同じ。注文 17 でも送られていない）。
- 「変更不成立」のメール（CHANGE_NOT_ADOPTED）は、変更期限の 5 分後（14:05）の最終判定で送られる。最終判定は ABORTED の変更を対象にしないため、14:05 より前に終了するとこのメールは送られない（コードの読み取り。未確認）。
- 3D セキュアの失敗などで普通に取りやめになった変更も、14:05 より前に ABORTED になればメールは送られない。自動の終了もこれと同じ扱いにすることを提案する。お客様の画面には「ご注文は変更前の内容のままです」と表示される。

### 15.5 画面

- 管理者の注文詳細：終了の記録に「SYSTEM・自動終了」と表示する（143-A の表示をそのまま使う）。
- 新しい一覧は作らない。対象は少なく、注文詳細と保留の一覧で確認できる。

### 15.6 テスト

- 選ばれる条件：猶予前は選ばない、猶予後は選ぶ、AUTHORIZING・REQUIRES_ACTION・MANUAL_REVIEW のどれも選ぶ。対象外（与信成立、決済の状態、受付中の終了がある、runtime 停止）は選ばない。
- RELEASED・REJECTED のあとは間隔を空ける。1 回の件数と順序。1 件の失敗で止まらない。二重に動いても受付は 1 件。
- 記録の操作者が SYSTEM であること。設定の範囲外で起動しないこと、無効なら定期処理が作られないこと。
- 守りを外すとテストが失敗すること（コピー上、トリガーは無効にしない）。回帰は変更したクラスを使うテストを grep で洗い出す。

### 15.7 確認

- ブラウザ確認：お客様の画面で一括編集の変更を始め、3D セキュアの認証を完了せずに止める。自動処理の期限（10 分）と猶予を過ぎたあと、`ORDER_CHANGE_CLOSURE_ENABLED=true` と短い猶予（10 分）で起動して自動で終了することを確かめる。PAY.JP テストモードの取消を含むので、事前に確認する。
- 確認後は既定（無効）に戻して起動する。

### 15.8 決めていただくこと

| # | 項目 | 案 |
|---|---|---|
| 1 | 7 章の「MANUAL_REVIEW にしない」 | 行わない（15.2） |
| 2 | 猶予の既定 | 自動処理の期限から 60 分（最小 10 分） |
| 3 | お客様へのメール | 新しいメールは作らない。14:05 より前に終了した場合は不成立のメールも送られない（15.4） |
| 4 | 既定 | 無効 |

## 16. 143-B の結果

### 16.1 実装

設計（15 章）のとおり。マイグレーションはない。

- `ChangeFlowClosureStore.dueOperations`：期限の古い順に、猶予と再試行の間隔を過ぎ、受付中の終了がなく、`f143_change_closable` を満たす変更を選ぶ。
- `ChangeFlowClosureStore.requestDue`：Feature125 の順でロックしてから、同じ条件をもう一度判定して受け付ける。操作者は SYSTEM、備考は「自動終了」。管理者の受付と同じ処理（`requestLocked`）を使う。
- `ChangeFlowClosureService.closeDue`：受け付けた終了を 143-A と同じ流れで進める。1 件の失敗はログに残して次へ進む。
- `ChangeFlowClosureScheduler` と `OrderChangeClosureProperties`（`app.order-change-closure.*`）。既定は無効。範囲外の値では起動しない。

### 16.2 テスト

- `OrderChangeFlowClosurePostgresTest` に自動終了の 7 件を追加し、34 件 PASS（共有テスト DB、トリガーの無効化なし）。
  - 猶予前は選ばない、AUTHORIZING・REQUIRES_ACTION・MANUAL_REVIEW を終了する、RELEASED のあとは間隔を空ける、受付中の終了は触らない、runtime 停止中は選ばない、2 つが同時に動いても送信は 1 回、期限の古い順。
  - このクラスのテストは runtime の世代を共有するため、先のテストが終了できる変更を残す。自動終了のテストでは、それらに送らない受付中の記録を付けて対象から外す（`retireOthers`）。
- 新規 `OrderChangeClosurePropertiesTest`（11 件）、`ChangeFlowClosureServiceTest`（1 件）。
- 回帰：変更したクラスと設定を使う 15 クラスすべて PASS（689 件、失敗 0、エラー 0）。うち `OrderChangeFlowClosurePostgresTest` は、テストの誤り（モックの準備を続けて 2 回行った）を直して再実行した。

### 16.3 守りを外した確認

14 か所を 1 つずつ外し、すべてテストが失敗した。最初は 2 か所が検出できず、テストを強めた（一覧を終了条件で絞ること、期限の古い順）。

| 外したもの | 失敗したテスト |
|---|---|
| 猶予の条件 | `theAutomaticClosureWaitsForTheGraceAndClosesAsTheSystem` |
| 再試行までの間隔 | `aReleasedClosureIsRetriedOnlyAfterTheWait` |
| 受付中の終了を除く条件 | `anOpenClosureIsLeftToItsOwner` ほか 5 件 |
| 一覧を終了条件で絞る | `aStoppedRuntimeIsNotClosedAutomatically` |
| 期限の古い順 | `theLongestOverdueComesFirst` |
| 受付時の再判定 | `aReleasedClosureIsRetriedOnlyAfterTheWait` ほか 1 件 |
| ロック前の判定 | `aStoppedRuntimeIsNotClosedAutomatically` |
| 操作者 SYSTEM | `theAutomaticClosureWaitsForTheGraceAndClosesAsTheSystem` |
| 1 件の失敗で止めない | `oneFailureDoesNotStopTheOthers` |
| 設定の最小値、既定の無効、無効時に定期処理を作らない、定期処理の例外の扱い | `OrderChangeClosurePropertiesTest` の各テスト |

### 16.4 確認できていないこと

- ブラウザでの確認と、PAY.JP テストモードでの自動の取消。
- 14:05 より前に終了した場合に不成立のメールが送られないこと（15.4。コードの読み取りのみ）。
