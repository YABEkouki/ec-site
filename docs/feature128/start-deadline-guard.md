# Feature128 A-1 注文変更期限（14:00）以降の再与信開始ガード

設計資料：[docs/future/warehouse-shipping-design.md](../future/warehouse-shipping-design.md) 6.2章、10章、12章3

## ルール

- 注文変更期限 `orders.change_deadline_at`（Feature127。正式受注前はNULL）以降は、注文変更の再与信を**開始しない**。
- 再与信の「開始」は次の2つ。どちらも期限前にDB上で開始済みとなったものだけが、結果を待つ対象になる（最終判定は A-2）。
  1. **サーバーのCREATE POST**（新しいPAY.JP Payment Flowの作成）。開始＝`order_change_request_dispatches` の READY→STARTED のコミット（V61の一回限りの送信許可）。
  2. **ブラウザの confirmPayment**（与信・3DS）。サーバーから止められないため、一度だけ発行する確認画面（`browser_confirmation_issued_at`、V60）の発行を開始とみなす。期限前に発行済み＝期限前に開始済み。
- 期限は `grace_until`（期限＋10分）ではない。`grace_until`・`automatic_until`・既存行・Feature125の証跡は変更・再計算しない。
- 時刻は呼び出し側から受け取らない。アプリは Order ロック後に読んだ DB 時刻、DB は `clock_timestamp()` を使う。期限NULL（未受注）は拒否側に倒す。

## 判定箇所

| 時点 | アプリ（Orderロック下のDB時刻） | DB（V82） |
|---|---|---|
| Payment作成・CREATEリクエスト保存（`prepareLocked`） | 期限以降なら Payment を作らず予約を解放し ABORTED。証跡 `BEFORE_CREATE_REQUEST` | 証跡INSERT時に期限経過・金融処理なしを再検証 |
| CREATEの送信許可（`findForDispatch`） | 期限以降なら `DispatchDeadlinePassed`（送信しない） | READY→STARTED の行更新時と**コミット時（DEFERRED）**の二重チェック。SQLSTATE `EC128` |
| 未送信のCREATEの後始末（`createDeadlinePassed`） | 従来経路：新Payment・与信transactionを `FAILED`（`NOT_SENT_AFTER_DEADLINE`、provider ID なし）、予約解放、ABORTED。一括編集経路：既存の失敗クローズ処理に委ね、`MANUAL_REVIEW`（`CREATE_DEADLINE_PASSED`）。どちらも証跡 `CREATE_NOT_STARTED` | dispatch が READY のままであることを検証。証跡がある操作の STARTED を以後禁止 |
| ブラウザ確認画面の発行（両経路） | 期限以降は発行しない | `browser_confirmation_issued_at` の設定を行更新時・コミット時に検証 |

コミット時のDB側チェックで失敗した場合、STARTED はロールバックされPOSTは送られない。sender は `EC128` を `DispatchDeadlinePassed` に変換し、上記の後始末に回す。
コミット成功からネットワーク送信までの数ミリ秒は残る（期限直前のコミットは「期限前の開始」とみなす）。

期限前に開始済みの再与信（CREATE STARTED／確認画面発行済み）の結果反映は、従来どおり続行する。成立・不成立は A-2（14:05最終判定）で確定する。

## DB（V82）

- `order_change_deadline_closures`：締切による不成立の追記専用証跡（operation_id 主キー、phase、deadline_at、closed_at＝DB時刻、判定時点の状態・version、xid）。UPDATE/DELETE/TRUNCATE不可。
- トリガー `f128_create_dispatch_deadline`（BEFORE）／`f128_create_dispatch_deadline_commit`（DEFERRED）。
- トリガー `f128_browser_confirmation_deadline`（BEFORE）／`f128_browser_confirmation_deadline_commit`（DEFERRED）。

## 顧客表示

締切で不成立となった操作は、状態表示で「注文変更期限を過ぎたため、この変更は成立しませんでした」と案内する（ABORTED：変更前の内容のまま／MANUAL_REVIEW：決済状態の確認待ち）。

## A-2（14:05最終判定）の合意済み方針

- 旧与信取消の決定（`switch_decided_at`）後に14:05時点で注文切替が未完了の操作は、元注文に戻さず**前進のみ（遅延成立）**とし、出荷保留にする。旧与信が取消済みの可能性があるため。
- 保留後は Webhook・照合バッチによる自動解消を待つ。翌日の倉庫出力時に再判定し、完了していれば変更後の内容で出荷、未完了なら保留を続け管理者対応とする。
- 遅延成立を減らすため、旧与信取消の決定は期限＋4分（14:04）までに限る。それ以降に新与信が確認された操作は切替を決定せず、不成立（元注文）に倒す。
- システムで解消できないもの（取消拒否、照会不能の継続、新与信が無効、切替のDB処理失敗など）は、管理者が介入して手動で解消する（既存の旧与信取消復旧画面を含む）。

## 既知の制約・後続

- RESERVED のまま期限を迎えた操作の自動解放は、従来どおり `grace_until`（14:10）の期限切れ処理か、次の顧客操作時（その時点で締切クローズ）。14:05での確定は A-2。
- 確認画面を期限前に受け取った顧客が期限後に confirmPayment を実行することは防げない（設計判断：開始済みとみなす）。14:05以降の遅延成功は設計資料7章（後続Feature）。
- LEGACY 注文の `change_deadline_at` はDB上で変更可能（V81の保護対象外）。
- 一括編集経路の実時刻での期限到達テストは、V71が期限14:00固定のため行えない。DBガードは経路共通で、アプリの判定も共通の `prepareLocked`／`findForDispatch` を通る。
