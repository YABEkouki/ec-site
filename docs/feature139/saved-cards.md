# Feature139 クレジットカードの登録（保存）

状態：139-A 完了（2026-11-22、V105、ブラウザで確認した（9 章））。139-B は詳細設計中（6 章）。139-C は未着手。基準は main eb19697（最新マイグレーション V104）。

## 1. 目的

- 決済画面で「今後使うカードとして登録する」を選べるようにする。次回からは登録済みのカードを選ぶだけで決済でき、カード番号の入力は要らない。
- カード番号は当サイトに保存しない。PAY.JP v2 の顧客（Customer）に支払い方法（PaymentMethod、`pm_…`）として登録し、決済のときに指定する。
- 1 人で複数のカードを登録できる。マイページでカードの登録・削除・既定カードの選択ができる。
- 当サイトで持つデータは最小限にする。顧客もカードも DB に持たず、PAY.JP から取得する。

## 2. 決定事項（2026-11-22）

| # | 決定事項 |
|---|---|
| 1 | 登録はマイページ（Setup Flow）と決済画面（「このカードを登録する」）の両方で行う。マイページを先に作る（139-A）。 |
| 2 | 顧客 ID はこちらで決めた値（環境ごとの接頭辞 ＋ users.id）にする。顧客とカードの対応表、ブランド・下 4 桁・有効期限などは DB に持たず、毎回 PAY.JP から取得する。DB に持つのは、カード操作の記録（外部送信と監査）だけ。 |
| 3 | 注文時（139-B）と注文変更の再与信・増額（139-C）で、登録済みのカードを選べる。どちらも「仮売上 → 出荷時に売上確定」と与信期限（Feature135）の流れは変えない。 |
| 4 | 3D セキュアは `request_three_d_secure=automatic`（PAY.JP の既定）。一度 3D セキュアを済ませたカードは、以降の決済で 3D セキュアなしで与信される（テストモードで確認）。チャージバック補償の対象外になる決済が出ることは承知のうえとする。 |
| 5 | 売上確定前の注文で使っているカードも削除できる（削除しても既存の仮売上の売上確定・取消はできる。テストモードで確認）。 |
| 6 | 既定カードを 1 枚選べる。PAY.JP の `default_payment_method_id` を使い、DB には持たない。最初に登録したカードは既定にする。既定カードを削除したら、残りのうち最も新しく登録したカードを既定にする（案 B）。 |
| 7 | 登録できるのは 1 人 5 枚まで。同じカード（`card.fingerprint` が同じ）は重複して登録しない。 |
| 8 | 画面：マイページにカード一覧（ブランド・下 4 桁・有効期限・既定）と、追加・削除・既定にするボタン。決済画面では登録済みのカード（既定カードを初めから選んだ状態）と「新しいカード」を並べる。有効期限切れのカードは「期限切れ」と表示し、選べないようにする。 |
| 9 | 外部送信は今の方式に合わせる：冪等キーを先に DB に記録 → トランザクションの外で送信 → 結果を記録。結果不明のときは PAY.JP から取得し直して確かめ、自動では送り直さない。 |
| 10 | 退会の機能はないので、今回は扱わない（顧客を削除すると全カードが削除状態になり、既存の仮売上は売上確定できることだけ確認済み）。 |

## 3. PAY.JP v2 の仕様（確認結果）

項目名は https://docs.pay.jp/v2/openapi.json （API リファレンスの元データ）で確認した。ガイドの一部に出てくる `customer` / `payment_method` という名前は誤り。

| 用途 | API・項目 |
|---|---|
| 顧客 | `POST /v2/customers`（`id` を指定できる。100 文字まで、英数字・`-`・`_`）、`GET`・`POST`（更新）・`DELETE /v2/customers/{id}`。`default_payment_method_id` |
| 顧客のカード一覧 | `GET /v2/customers/{id}/payment_methods`（`limit`・`starting_after`） |
| カード | `card.brand`・`card.last4`・`card.exp_month`・`card.exp_year`・`card.fingerprint`、`customer_id`、`detached_at`、`created_at` |
| 登録 | `POST /v2/setup_flows`（`customer_id`、`payment_method_types=["card"]`、`usage` は既定で `off_session`、登録時に 3D セキュアを行う）→ 画面で `widgets.confirmSetup({return_url})` → 戻り先の URL に `setup_flow_id`・`setup_flow_client_secret` が付く。Setup Flow を確定する API はなく、確定はブラウザ（payments.js）だけ |
| 紐付け・削除 | `POST /v2/payment_methods/{id}/attach`（`customer_id`）、`POST /v2/payment_methods/{id}/detach` |
| 決済 | `POST /v2/payment_flows`（`customer_id`・`payment_method_id`・`capture_method`・`confirm`・`return_url`（confirm=true のときだけ））、`POST /v2/payment_flows/{id}`（更新。`customer_id`・`payment_method_id` は requires_payment_method / requires_confirmation のときだけ）、`POST /v2/payment_flows/{id}/confirm`（`payment_method_id`・`return_url`） |
| 冪等キー | `Idempotency-Key`（POST・PUT・PATCH。DELETE は対象外。最低 24 時間保持。同じキーで違う内容はエラー） |

### 3.1 テストモードで確認したこと（2026-11-22）

1. 登録済みのカード ＋ `capture_method=manual` ＋ サーバーからの confirm で与信できる。カードの初回は `requires_action`（`next_action.type=card_three_d_secure`）、認証後に `requires_capture`。`expired_at` は 7 日後。
2. 3D セキュアを一度済ませたカードは、2 回目以降すぐ `requires_capture` になる。`request_three_d_secure=any` なら毎回 `requires_action`。
3. 顧客のない決済にはカードを指定できない（422）。単独で作ったカードは、あとから attach できる。**決済画面で入力したカードが、顧客付きの決済で自動的に紐付くかは未確認**（ブラウザが必要。139-B のブラウザ確認で見る）。
4. カードを削除しても、削除前の仮売上は売上確定・取消ができる。削除したカードで新しく決済すると 400 `detached_payment_method_not_usable`。同じカードを再度削除すると 400 `payment_method_already_detached`、再度紐付けると 400 `payment_method_already_attached`。
5. 同じカードの重複登録を PAY.JP は断らない（fingerprint は同じ値）。
6. 既定カード：attach、または Setup Flow で最初に登録したカードが自動で既定になる（Setup Flow は 139-A のブラウザ確認で確認）。2 枚目以降では変わらない。既定カードを削除すると `default_payment_method_id` は null になる。
7. 顧客 ID の重複作成は 400 `already_exists_id`。顧客を削除すると、その顧客のカードはすべて削除状態になる（`customer_id` は null、`detached_at` あり）。削除前の仮売上は売上確定できる。
8. 決済の失敗：
   - 3D セキュアの失敗 → `requires_payment_method`、`last_payment_error.code=three_d_secure_failed`。
   - 3D セキュアのあとに拒否 → `requires_payment_method`、`last_payment_error.code=card_declined`。
   - **作成と confirm を 1 回で送ったとき（`confirm:true`）に拒否されると、HTTP 402 `card_declined` が返り、本文に決済の ID がない。それでも決済は作られている**（`requires_payment_method`）。そのため、決済は必ず「作成（`confirm:false`）→ ID を DB に記録 → `POST /confirm`」の 2 段階で行う。

## 4. 全体の設計

### 4.1 顧客 ID

- `payjp.customer.id-prefix`（環境変数 `PAYJP_CUSTOMER_ID_PREFIX`）＋ users.id。例：`ecu-dev1-42`（PayJpCustomerProperties）。
- 接頭辞の形式は `[A-Za-z0-9_-]{0,39}-`（40 文字まで、`-` で終わる）。公開鍵がないときも使えないようにする。設定がないときは、カードの機能を使えないようにする（画面にも出さない）。起動は止めない。
- 開発 DB を作り直すと users.id が再び使われるため、テストモードに残った以前の顧客に紐付いてしまう。DB を作り直したら接頭辞を変える（運用の注意として残す）。
- 顧客は、初めてカードを登録するときに作る（`GET` が 404 なら `POST`。冪等キーあり）。`POST` が `already_exists_id` を返したら、もう一度 `GET` して作成済みとして扱う（同時に 2 つの操作をしたときの対策。DB の一意制約違反を捕まえて続けるのとは別）。
- 本人確認：カードの一覧・削除・既定の変更・決済の前に、カードの `customer_id` が本人の顧客 ID であることを確かめる。決済では `customer_id` を本人の顧客 ID にするので、他人のカードは PAY.JP も拒否する。

### 4.2 DB：カード操作の記録（V105）

`saved_card_operations`（カード番号・ブランド・下 4 桁・有効期限・client_secret は持たない）

| 列 | 内容 |
|---|---|
| id | bigserial |
| user_id | users.id（NOT NULL、外部キー） |
| operation_type | CUSTOMER_CREATE / SETUP_START / SETUP_COMPLETE / DETACH / SET_DEFAULT（139-B で ATTACH を追加） |
| reason | 利用者の操作（USER）か、自動の処理（DUPLICATE：重複、LIMIT：上限超え、DEFAULT_REPLACEMENT：既定カードの付け替え）か |
| provider_customer_id | 顧客 ID（NOT NULL） |
| provider_setup_flow_id | PAY.JP の Setup Flow ID（SETUP_START / SETUP_COMPLETE のとき） |
| provider_payment_method_id | `pm_…`（DETACH / SET_DEFAULT / SETUP_COMPLETE のとき） |
| idempotency_key | 一意。CUSTOMER_CREATE・SETUP_START・DETACH・SET_DEFAULT の送信で使う |
| status | PENDING → SUCCEEDED / FAILED / UNCONFIRMED、UNCONFIRMED → SUCCEEDED / FAILED |
| error_code | PAY.JP のエラーコード（メッセージは持たない） |
| created_at / completed_at | `timezone('Asia/Tokyo', clock_timestamp())` |

DB トリガーで守ること：
- 識別の列（user_id・種類・各 ID・冪等キー・created_at）は変更不可。行の削除は不可。
- 状態の遷移は上の表のとおり。終了状態（SUCCEEDED / FAILED）は変更不可。completed_at は終了状態のときだけ入る。
- 種類ごとに必要な ID がそろっていること（CHECK）。

### 4.3 外部送信の流れ（共通）

1. トランザクション 1：操作の記録を PENDING で作る（冪等キーを含む）。
2. トランザクションの外で PAY.JP に送る（冪等キー付き）。
3. トランザクション 2：結果を記録する。
   - 成功 → SUCCEEDED。
   - 4xx（PAY.JP の判断）→ FAILED（エラーコードを記録）。
   - タイムアウト・5xx・応答が読めない → UNCONFIRMED。
4. UNCONFIRMED と、古い PENDING（接続・読み取りのタイムアウトの合計 ＋ 1 分より前。送信中の処理が中断した場合）は、次にその人がカードの画面を開いたときと、操作の前に、PAY.JP から取得し直して確かめる。
   - CUSTOMER_CREATE は顧客があるか、SETUP_START は顧客の Setup Flow のうち `metadata.saved_card_operation_id` が操作の id と一致するもの（見つかれば、その Setup Flow ID を記録する）、DETACH はカードの `detached_at`、SET_DEFAULT は顧客の `default_payment_method_id` を見る。
   - 結果の記録は「今の状態が PENDING / UNCONFIRMED のときだけ」の条件付きで更新し、2 つの処理が同時に確かめても二重に記録しない。
   - 確かめられたら SUCCEEDED / FAILED にする。自動では送り直さない。
   - 同じ人に UNCONFIRMED がある間は、新しい操作を受け付けない（「確認中です」と表示する）。

同じ人の操作が重ならないように、操作を始めるときは users の行を `FOR UPDATE` でロックし、PENDING / UNCONFIRMED の操作がないことを確かめてから記録を作る。外部送信のあいだはロックを持たない。

## 5. 139-A：マイページでのカードの登録・削除・既定カード（V105）

### 5.1 画面

- `/mypage/cards`（GET）：カード一覧。PAY.JP から顧客の `default_payment_method_id` とカード一覧を取得して表示する。
  - 表示：ブランド・下 4 桁・有効期限・既定・期限切れ。
  - 顧客がまだない（404）ときは「登録されたカードはありません」。
  - PAY.JP に接続できないときは「カード情報を取得できませんでした」。
  - 5 枚あるときは追加のボタンを出さない。
- `/mypage/cards/new`（POST）：Setup Flow を作り、その画面で payments.js の登録フォームを出す。client_secret はセッションに保存せず、画面にだけ渡す（今の決済画面と同じ）。
- `/mypage/cards/return`（GET）：登録後の戻り先。外部の画面から戻る GET では何も変更せず、`setup_flow_id` を持つフォームを表示して自動で POST する（`/mypage/cards/complete`）。
  - プライバシーフィルター（SavedCardPrivacyFilter）で、クエリを `setup_flow_id`（形式を確かめたもの）だけにする。`setup_flow_client_secret` はアプリにも記録にも残らない。画面を開いたら `history.replaceState` で URL からクエリを消す。
- `/mypage/cards/complete`（POST）：`setup_flow_id` が本人の SUCCEEDED の SETUP_START と一致するときだけ、サーバーで Setup Flow を取得して `customer_id` と `status` を確かめ、succeeded なら登録完了の処理（5.3）をする。何度送っても同じ結果になる。
- `/mypage/cards/{pm}/delete`（POST）・`/mypage/cards/{pm}/default`（POST）：本人のカードであることを確かめてから送る。
- マイページの入口（mypage/index.html）に「登録カード」へのリンクを追加する。
- CSP：新しい SavedCardPrivacyFilter が `/mypage/cards/new`（payments.js 用の nonce と CSP）と `/mypage/cards/return`（nonce の CSP、クエリの除去）を扱う。今の OrderChangePaymentPrivacyFilter は変えない。

### 5.2 Setup Flow の開始

1. カードの数（PAY.JP の一覧）が 5 枚未満であることを確かめる。
2. 顧客がなければ作る（CUSTOMER_CREATE）。
3. Setup Flow を作る（SETUP_START、冪等キー付き。`customer_id`、`payment_method_types=["card"]`、`metadata.user_id`）。
4. payments.js の登録フォームを表示し、`widgets.confirmSetup({return_url: /mypage/cards/return})` を呼ぶ。

### 5.3 登録の完了（戻り先）

1. Setup Flow が succeeded で `payment_method_id` があれば、SETUP_COMPLETE を SUCCEEDED で記録する（同じ Setup Flow で 1 回だけ）。
2. 顧客のカード一覧を取得し直し、次の順に判定する。
   - 新しいカードと fingerprint が同じカードがほかにある → 新しいカードを削除する（DETACH、reason=DUPLICATE）。「このカードはすでに登録されています」と表示する。
   - 登録順（`created_at`）で 6 枚目以降に当たる → 新しいカードを削除する（DETACH、reason=LIMIT）。同時に 2 つ登録して 6 枚になったとき、先に登録した方は残る。
   - 既定カードがない（削除したあとも含む）→ 最も新しいカードを既定にする（SET_DEFAULT、reason=DEFAULT_REPLACEMENT）。
   - 新しいカードがもう一覧にない（2 回目の送信）→ そのカードを当サイトが DUPLICATE / LIMIT で削除した記録があればその結果、なければ登録済みとして扱う。
3. requires_action などで止まっているときは「登録が完了していません」と表示し、もう一度最初から登録してもらう。Setup Flow を作り直すときは、古いものを cancel しない（PAY.JP 側で期限切れになる）。

### 5.4 削除

1. カードが本人のものであることを確かめる。
2. DETACH を送る。
3. 削除したカードが既定だった（削除前の `default_payment_method_id` と一致した）とき、残りのカードのうち `created_at` が最も新しいカードを既定にする（SET_DEFAULT、reason=DEFAULT_REPLACEMENT）。
4. 3 が失敗・結果不明のときは既定なしのまま表示し、「既定のカードを選び直してください」と案内する。次の操作のとき、既定がなければ 3 をやり直す。

`payment_method_already_detached` が返ったときは、操作は FAILED（そのエラーコード）として残し、カードは削除済みとして扱う（削除の前に、カードが本人のもので削除されていないことを確かめている）。

### 5.5 既定にする

`POST /v2/customers/{id}`（`default_payment_method_id`）を送る（SET_DEFAULT、reason=USER）。

### 5.6 ゲートウェイ

- 新しいインターフェース `SavedCardGateway`（PayJpPaymentGateway とは分ける）。
  - 顧客の取得・作成、カード一覧、Setup Flow の作成・取得、detach、既定の更新。
  - 応答の record は、client_secret を toString に出さない。
- 接続とタイムアウトは今の `payjp.safety` の設定に合わせる。

### 5.7 テスト

- SavedCardPostgresTest（SHARED）：
  - V105 のトリガー：PENDING で始まること、遷移、変更不可、削除不可、登録完了には成功した開始が必要なこと、種類と理由の組み合わせ、1 人 1 件までの送信中。
  - サービス（メモリ上の偽の PAY.JP）：顧客を 1 回だけ作ること、最初のカードを既定にすること、重複・上限超え（同時登録を含む）の削除、既定カードを削除したら最も新しいカードを既定にすること、他人・削除済みのカードの拒否、結果不明を送り直さずに取得で確かめること、失敗の記録、送信中の Setup Flow を操作 id で見つけること、期限切れの表示。
- PayJpSavedCardGatewayTest：送信する項目名（`id`・`customer_id`・`default_payment_method_id`・`Idempotency-Key`）、一覧のページ送りと削除済み・他人のカードの除外、404、エラーの分類、client_secret を toString に出さないこと、不正な ID を送らないこと。
- SavedCardControllerTest（@WebMvcTest）：一覧の表示、使えないとき・取得できないとき、未ログイン、登録画面の CSP、CSRF、上限、戻り先のクエリの除去（client_secret が画面に出ないこと）、登録結果、削除・既定。
- 最新版数の 3 テスト（105）。
- ブラウザ確認（テストモード）：登録（3D セキュアあり）・重複・削除・既定の付け替え。あわせて、Setup Flow で登録したときに既定カードがどう変わるかを確かめる。

## 6. 139-B：注文時の登録済みカード・決済画面での登録（詳細設計案、2026-11-22）

### 6.1 今の注文時の与信（前提）

- 注文を作ると、Payment Flow（`confirm:false`、顧客なし、`capture_method=manual`）と、与信の取引（AUTHORIZE、PENDING、INITIAL_ORDER_AUTHORIZATION）が作られる（PaymentAuthorizationService.prepareAuthorization）。Payment Flow の ID は Payment.provider_payment_id にある。
- 決済画面（`/checkout/payment`）は、Payment Flow が `requires_payment_method` のときだけ payments.js の入力欄を出す。ブラウザが `widgets.confirmPayment` で確定し、戻り先（`/checkout/payment/return`）から結果の確認（`/checkout/payment/continue`）を POST する。
- 結果の確認は、PAY.JP から Payment Flow を取得して取引に反映する（refreshAuthorization → apply）。`requires_capture` なら与信成功、`canceled` なら失敗。`requires_payment_method`（拒否・3D セキュアの失敗）なら同じ Payment Flow のまま続けられる。
- ブラウザでの確定は、同じ注文で 1 回だけ（localStorage の印）。中断した 3D セキュアは、見るだけで再開しない。

**139-B でもこの前提は変えない。** 与信の取引の状態は、今までどおり PAY.JP の Payment Flow を取得して決める。新しく記録するのは「当サイトから Payment Flow に送ったこと」（冪等キー・結果）だけで、決済の安全判定（PaymentSafetyClassifier）・照合・与信期限（Feature135）・出荷時の Capture は変えない。

### 6.2 登録済みのカードで支払う

1. 決済画面に、登録済みのカード（期限切れ以外）を並べる。既定カードを初めから選んだ状態にし、最後に「新しいカード」を置く。
   - 登録済みのカードは PAY.JP から取得する（139-A の SavedCardService）。取得できないとき、使えないとき、カードがないときは、今の入力欄だけを出す。
2. 「このカードで支払う」→ `POST /checkout/payment/saved-card`（カードの ID と、今の結果の確認と同じ注文・Payment・取引の ID、CSRF）。
3. サーバーでの処理：
   1. 注文が本人のもので、今の CheckoutTarget（ORDERED、有効な Payment、PENDING の AUTHORIZE）と一致すること、カードが本人の顧客の削除されていないカードであることを確かめる。
   2. **Payment Flow の更新**（FLOW_UPDATE）：`POST /v2/payment_flows/{id}` に `customer_id` と `payment_method_id` を送る（Payment Flow が requires_payment_method / requires_confirmation のときだけ PAY.JP が受け付ける）。
   3. **確定**（CONFIRM）：`POST /v2/payment_flows/{id}/confirm` に `return_url`（`/checkout/payment/return`）を送る。
   - どちらも、送信記録（6.4）を PENDING で作ってからトランザクションの外で送り、結果を記録する。
   4. 送ったあとは、今の refreshAuthorization で Payment Flow を取得して取引に反映し、結果に応じて画面を分ける。
4. 結果：
   - `requires_capture`（AUTHORIZED）→ 注文完了（今と同じ）。
   - `requires_action` → 3D セキュアへ。決済画面に「本人認証へ進む」を出し、`payments.handleNextAction({clientSecret})` を呼ぶ。認証後は今の戻り先・結果の確認を使う。
     - 「本人認証へ進む」を出すのは、この取引で当サイトが CONFIRM を送っていて、Payment Flow が requires_action のときだけ。ブラウザでの確定が中断したもの（今の「見るだけ」）とは区別する。
     - 3D セキュアは、登録時（Setup Flow）に済んでいれば出ない（3.1 の 2）。
   - `requires_payment_method`（拒否・3D セキュアの失敗。CONFIRM が 402 で FAILED になる場合を含む）→ 決済画面に戻し、「このカードはご利用いただけませんでした。別のカードを選んでください。」と表示する（再試行は 6.10 の 1）。
   - `requires_confirmation`（更新はできたが確定が届かなかった）→ 決済画面に「このカードで支払う」を再び出す（同じカードで CONFIRM をもう一度。新しい冪等キー）。
   - 結果不明（送信も取得も失敗）→ 今の「確認中」の画面（`/checkout/payment/wait`）。自動では送り直さない。
5. 一度登録済みのカードの更新が通ると、Payment Flow には顧客とカードが付く。そのあと「新しいカード」に切り替えられるのは、Payment Flow が requires_payment_method に戻ったとき（拒否のあと）だけにする。requires_confirmation の Payment Flow にブラウザから別のカードで確定できるかは確かめていないため。

### 6.3 決済画面で「このカードを登録する」

1. 「新しいカード」の入力欄の下に「このカードを登録する（次回から入力不要）」のチェックを置く。登録済みのカードが 5 枚あるとき、機能が使えないときは出さない。
2. チェックしたまま「カードで支払う」を押すと、ブラウザで確定する前に、`POST /checkout/payment/register-intent`（fetch、CSRF ヘッダー）を送る。サーバーでは次を行う。
   - 顧客がなければ作る（139-A の CUSTOMER_CREATE）。
   - Payment Flow の更新（FLOW_UPDATE、purpose=REGISTER_NEW_CARD）で `customer_id` だけを設定する。
   - 成功したら、ブラウザは今までどおり `widgets.confirmPayment` を呼ぶ。失敗したら確定せずに「カードを登録する準備ができませんでした。登録しない場合はチェックを外してお支払いください。」と表示する。
3. 与信が成功したら（結果の確認で AUTHORIZED になったとき）、登録の後処理をする。
   - Payment Flow の `payment_method_id` のカードを取得する。
   - カードの `customer_id` が空なら紐付ける（ATTACH。139-A の操作記録に種類を追加）。本人の顧客ならそのまま。他人の顧客なら何もしない（記録して止める）。
   - そのあと、重複・上限・既定の扱いは 5.3 と同じ（重複・上限超えなら新しいカードを削除する。削除しても与信と出荷時の Capture には影響しない（3.1 の 4））。
   - 後処理の失敗・結果不明は注文に影響させない。注文完了の画面に「カードを登録できませんでした。マイページから登録してください。」と出す。
   - 注文完了の画面まで戻らなかったとき（ブラウザを閉じたなど）は、後処理を行わない。PAY.JP が自動で紐付けていた場合は、そのカードがマイページに出る（重複の確認は行われない）。
4. 「登録する」にチェックした決済でも、カードは顧客付きの Payment Flow で使われる。チェックしないときは今までどおり顧客を付けない。

### 6.4 DB（V106）

**`checkout_card_sends`**：当サイトから注文時の Payment Flow に送った更新と確定の記録。カード番号・client_secret・PAY.JP の応答本文は持たない。

| 列 | 内容 |
|---|---|
| id | bigserial |
| user_id・order_id・payment_id・transaction_id | 本人・注文・Payment・与信の取引（AUTHORIZE） |
| send_type | FLOW_UPDATE / CONFIRM |
| purpose | SAVED_CARD（登録済みのカードで支払う）/ REGISTER_NEW_CARD（新しいカードを登録する。FLOW_UPDATE だけ） |
| provider_payment_flow_id | Payment.provider_payment_id と一致 |
| provider_customer_id | 本人の顧客 ID |
| provider_payment_method_id | SAVED_CARD のときのカード（REGISTER_NEW_CARD の FLOW_UPDATE では空） |
| idempotency_key | 一意 |
| status・error_code・created_at・completed_at | 139-A の操作記録と同じ（PENDING → SUCCEEDED / FAILED / UNCONFIRMED、UNCONFIRMED → SUCCEEDED / FAILED） |

DB トリガーで守ること：
- 作成時：取引が AUTHORIZE・PENDING で、Payment（PAYJP・CARD、provider_payment_id が Payment Flow ID と一致）と注文（ORDERED、user_id が本人、active_payment_id がその Payment）に属すること。読み取りは注文 → Payment の順に `FOR SHARE` で行う（アプリは同じ順に `FOR UPDATE` 済み）。
- 1 つの取引で、送信中（PENDING / UNCONFIRMED）は 1 件まで（部分一意索引）。
- CONFIRM は、同じ取引の最新の FLOW_UPDATE が SAVED_CARD・SUCCEEDED で、同じカードであること。CONFIRM は 1 つの取引で 3 回まで。
- 中身は変更不可、状態は前にしか進まない、削除不可（139-A と同じ）。

**`saved_card_operations`（V105）**：種類に ATTACH を追加する（CHECK を作り直す。カード必須、Setup Flow なし、冪等キーあり、理由 USER）。ATTACH の結果不明は、カードの `customer_id` が本人の顧客かを見て確かめる。

### 6.5 外部送信と結果不明

- 流れは 139-A と同じ（PENDING を記録 → トランザクションの外で送る → 結果を記録）。注文の行 → Payment の行の順にロックしてから送信記録を作る。
- 結果不明の確かめ方（取得だけ。送り直さない）：
  - FLOW_UPDATE：Payment Flow の `customer_id`（と SAVED_CARD なら `payment_method_id`）が送った値なら SUCCEEDED、違えば FAILED。
  - CONFIRM：Payment Flow が requires_confirmation のままなら届いていない（FAILED）。それ以外の状態なら届いた（SUCCEEDED）。
  - 確かめるのは、決済画面を開いたとき・結果の確認のとき・次の送信の前。
- 与信の取引の状態は、送信記録ではなく Payment Flow の取得で決める（今の refreshAuthorization）。そのため、決済の安全判定・照合・Webhook の同期・与信期限・出荷時の Capture・管理者の取消は変えない。管理者の取消と確定が同時に起きた場合の扱いは、今のブラウザでの確定と同じ（PAY.JP の状態を見て反映する）。

### 6.6 ゲートウェイ

SavedCardGateway に次を追加する（PayJpPaymentGateway は変えない）。
- `updateFlowCustomer(flowId, customerId, paymentMethodId|null, key)`：`POST /v2/payment_flows/{id}`
- `confirmFlow(flowId, returnUrl, key)`：`POST /v2/payment_flows/{id}/confirm`。402 は REJECTED（`card_declined` など）として扱う。
- `findFlow(flowId)`：状態・`customer_id`・`payment_method_id` だけを読む（結果不明の確認と登録の後処理用）。
- `attach(paymentMethodId, customerId, key)`：`POST /v2/payment_methods/{id}/attach`

### 6.7 画面

- 決済画面（checkout/payment.html）：
  - 登録済みのカードの選択欄（ブランド・下 4 桁・有効期限、既定に印）と「このカードで支払う」。
  - 「新しいカード」を選ぶと今の入力欄と「このカードを登録する」のチェックを出す。
  - 「本人認証へ進む」（6.2 の 4）。
  - payments.js と CSP は今のものを使う。fetch の送り先（`/checkout/payment/register-intent`）は同じオリジンなので CSP の connect-src 'self' で足りる。
- 注文完了の画面：登録の後処理の結果（登録した／重複／上限／できなかった）を 1 行で出す。
- 今のブラウザでの確定（localStorage の印、中断した 3D セキュアを再開しない）は変えない。

### 6.8 テストモードでの事前確認（2026-11-22、すべて期待どおり）

今の注文時と同じ本文（顧客なし、`confirm:false`、`capture_method=manual`）で作った Payment Flow で確かめた。

1. 更新で `customer_id` と `payment_method_id` を設定 → `requires_confirmation`。`confirm`（`return_url`）で、3D セキュア未済みのカードは `requires_action` → 認証後 `requires_capture`、済みのカードはすぐ `requires_capture`。
2. 確定で拒否 → HTTP 402 `card_declined`。Payment Flow は `requires_payment_method`（`last_payment_error.code=card_declined`）。同じ Payment Flow を別のカードで更新・確定し直すと `requires_capture`。
   - 与信が成功したあとも、`last_payment_error` には前の拒否が残る。そのため、与信の判定は状態（`requires_capture`）だけで行い、`last_payment_error` の有無では判断しない（今の PayJpAuthorizationMapper も、`requires_capture` ではエラーを見ない）。
3. 更新で `customer_id` だけを設定 → `requires_payment_method` のまま。
4. 他人の顧客のカードで更新 → 400 `payment_method_not_owned_by_customer`。
5. 与信が成功した Payment Flow への確定・更新 → 400 `invalid_status` / `fields_not_updatable_in_status`。二重に確定しても与信は二重にならない。

ブラウザが必要なため、139-B のブラウザ確認で見ること：
- 顧客付きの Payment Flow でブラウザから確定したカードが、自動で顧客に紐付くか（6.3 の 3 の ATTACH が要るか）。
- `handleNextAction` で 3D セキュアに進み、戻り先から与信が確認できること。

### 6.9 テスト

- CheckoutCardSendPostgresTest（SHARED）：V106 のトリガー（本人・注文・Payment・取引の一致、送信中 1 件、CONFIRM の前提、変更不可）、ATTACH の追加。
- サービスのテスト（偽の PAY.JP）：登録済みのカードでの与信（成功・3D セキュア・拒否・届かなかった確定・結果不明）、本人以外のカードの拒否、登録の後処理（紐付け・重複・上限・既定・失敗しても注文に影響しないこと）。
- ゲートウェイのテスト：送る項目名、402 の扱い。
- CheckoutController の @WebMvcTest と CheckoutPaymentPrivacyPostgresTest：選択欄、CSRF、「本人認証へ進む」の出し分け、client_secret を残さないこと。
- 最新版数の 3 テスト（106）。

### 6.10 決定事項（2026-11-22）

1. 登録済みのカードが拒否されたあと（Payment Flow が requires_payment_method に戻ったとき）、同じ注文で別のカード（登録済み・新しいカード）を選び直せる。当サイトからの CONFIRM は 1 つの与信の取引で 3 回まで（DB トリガーでも守る）。超えたら「確認中」と同じく、注文をやり直してもらう。
2. 登録の後処理は、注文完了の画面に戻ったとき（結果の確認で AUTHORIZED になったとき）だけ行う。
3. 6.8 の事前確認を行った。

## 7. 139-C：注文変更の再与信・増額で登録済みのカード（概要。139-B のあとで詳細を決める）

- 新しい決済の作成リクエスト（OrderChangeHttpRequest）に、`customer_id` と `payment_method_id` を含む第 2 版の本文を加える（`confirm:false` のまま）。ハッシュに版を含める。
- confirm を別のリクエストとして送る。`ORDER_CHANGE_AUTHORIZATION` の後続として、新しい送信の種類と記録を加える。
- 3D セキュアは増額の画面（orders/increase-payment.html）で `handleNextAction` を呼び、今の戻り先を使う。
- 拒否・3D セキュアの失敗は `requires_payment_method` と `last_payment_error` で観測できる（3.1 の 8）。今の 3D セキュア失敗の証拠（ThreeDsFailureEvidence）を、サーバーからの confirm による失敗にも使えるか、別の種類が要るかを 139-C の設計で決める。
- 今の「カードを入力し直す」流れは残す。

## 8. 未確認のこと（ブラウザ確認で見る）

- 決済画面で入力したカードが、`customer_id` 付きの決済で自動的に顧客に紐付くか（139-B）。

## 9. 139-A のブラウザ確認（2026-11-22、テストモード）

アプリを `PAYJP_CUSTOMER_ID_PREFIX=ecu-dev1-` で起動した（開発 DB に V105 を適用）。一般ユーザー（users.id 2）で次を確認し、すべて期待どおりだった。

1. 4242 のカードを登録（3D セキュアあり）→「カードを登録しました。」、既定になる。
2. 同じ 4242 をもう一度登録 →「このカードはすでに登録されています。」（新しいカードは DETACH / DUPLICATE で削除）。
3. 5555…4444 を追加し、「既定にする」で既定を切り替え。
4. 既定のカードを削除 → 残りの 4242 が既定になる（SET_DEFAULT / DEFAULT_REPLACEMENT）。

照合：
- saved_card_operations の 11 行（CUSTOMER_CREATE 1、SETUP_START 3、SETUP_COMPLETE 3、DETACH 2、SET_DEFAULT 2）はすべて SUCCEEDED。
- PAY.JP の顧客 `ecu-dev1-2` は、既定カードが残った 4242。重複した 4242 と削除した 4444 には `detached_at` があり、Setup Flow 3 件は succeeded。Setup Flow の `metadata.saved_card_operation_id` は操作記録の id と一致する。
- 1 枚目のあとに当サイトから既定を設定した記録がない → Setup Flow で最初に登録したカードは、PAY.JP が自動で既定にする（3.1 の 6）。
- 顧客の作成時に送った `metadata.user_id` は、PAY.JP の顧客では空だった。当サイトはこの値を使わないので影響はない。

