# Feature143 再与信が完了しなかった注文変更を終わらせる

状態：設計は承認済み（案 X。11 章）。143-A を実装中。基準は main 3e7118e（最新マイグレーション V110）。

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
