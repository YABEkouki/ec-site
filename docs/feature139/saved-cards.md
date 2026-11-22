# Feature139 クレジットカードの登録（保存）

状態：139-A 実装中（2026-11-22、ブランチ feature/139-a-saved-cards-mypage、V105）。基準は main eb19697（最新マイグレーション V104）。

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
6. 既定カード：attach で最初に紐付けたカードが自動で既定になる。2 枚目以降の attach では変わらない。既定カードを削除すると `default_payment_method_id` は null になる。Setup Flow で登録したときの変化は未確認（ブラウザ確認で見る）。
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

## 6. 139-B：注文時の登録済みカード・決済画面での登録（概要。139-A のあとで詳細を決める）

- 決済画面（checkout/payment.html）：登録済みのカード（期限切れ以外）を並べ、既定カードを初めから選んだ状態にする。「新しいカード」を選ぶと、今の payments.js の入力欄を出す。
- 登録済みのカードを選んだとき：
  1. 注文時に作った Payment Flow（`confirm:false`、顧客なし）を更新し、`customer_id` と `payment_method_id` を設定する。
  2. `POST /v2/payment_flows/{id}/confirm`（`return_url` 付き）を送る。
  - どちらも冪等キーを先に記録し、トランザクションの外で送る。今の決済の送信記録（payment_transactions など）の形に合わせる。
  - Payment Flow の ID は作成のときに記録済みなので、402 などで結果が分からなくても、その ID で取得し直せる。
- confirm の結果：
  - `requires_capture` → 今の与信成功と同じ処理。
  - `requires_action` → 画面で `payments.handleNextAction({clientSecret})` を呼び、`/checkout/payment/return` に戻す（今の確認処理を使う）。
  - `requires_payment_method`（拒否・3D セキュアの失敗）→ 今の失敗と同じ。
  - 結果不明 → 取得し直す。自動で confirm を送り直さない。
- 決済画面の「このカードを登録する」：新しいカードの入力のとき、ブラウザで確定する前に Payment Flow へ `customer_id` を設定する。与信が成功したあと、カードが顧客に紐付いていなければ attach する（ATTACH の操作記録）。重複・上限・既定の扱いは 5.3 と同じ。attach の失敗は注文と与信に影響させない。
- 決済の安全判定（PaymentSafetyClassifier）・照合：サーバーから confirm を送る段階（PENDING の取引）を判定と照合に加える。

## 7. 139-C：注文変更の再与信・増額で登録済みのカード（概要。139-B のあとで詳細を決める）

- 新しい決済の作成リクエスト（OrderChangeHttpRequest）に、`customer_id` と `payment_method_id` を含む第 2 版の本文を加える（`confirm:false` のまま）。ハッシュに版を含める。
- confirm を別のリクエストとして送る。`ORDER_CHANGE_AUTHORIZATION` の後続として、新しい送信の種類と記録を加える。
- 3D セキュアは増額の画面（orders/increase-payment.html）で `handleNextAction` を呼び、今の戻り先を使う。
- 拒否・3D セキュアの失敗は `requires_payment_method` と `last_payment_error` で観測できる（3.1 の 8）。今の 3D セキュア失敗の証拠（ThreeDsFailureEvidence）を、サーバーからの confirm による失敗にも使えるか、別の種類が要るかを 139-C の設計で決める。
- 今の「カードを入力し直す」流れは残す。

## 8. 未確認のこと（ブラウザ確認で見る）

- Setup Flow で登録したとき、既定カードが自動で設定されるか（139-A）。
- 決済画面で入力したカードが、`customer_id` 付きの決済で自動的に顧客に紐付くか（139-B）。
