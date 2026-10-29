# Feature124 完了記録

記録日: 2026-10-29 JST

検証対象commit: `ab63498d03c9668539abfba6801eac6255e81286`

総合判定: **PASS**（下記の既知の制限・除外を含む）

## 目的・実装範囲

注文商品の数量増加に伴う変更後全額の再与信、旧与信取消、注文切替を、決済履歴・金額・revision・在庫予約と整合させる。

- Payment安全性分類と、証拠不足・不整合時の安全停止。
- 注文変更operation、追加在庫のHELD予約、新Paymentへの全額与信、旧Payment取消。
- 保存要求・冪等キー・Dispatchによる一回限り送信、ロック・version・snapshot/contextによる競合検出。
- 注文金額・数量・active Payment・revision・予約消費・変更履歴の整合した切替。
- 顧客の取り下げ受付、安全な新Flow終了確認、追加予約解放・ABORTED確定。
- Webhook認証と結果同期、GET照合、不整合管理・監査。
- 対象を限定した管理者GET-only旧取消回復、単一更新Tx、V68監査、固定コードによる診断。
- 変更期限到達後の発送準備開始、有効なactive Paymentへの発送時Capture。
- 増額完了後の発送ボタン表示修正、備考空文字・空白のNULL正規化。送信前の厳格な一致検証は維持。

金融結果不明時に成功を推測せず、金融POSTを自動再送しない。通常のMANUAL_REVIEW自動解除は行わない。

## Flyway V54〜V68

| Version | 概要 |
| --- | --- |
| V54 | 不変Capture要求の保存基盤 |
| V55 | Payment安全性分類の拡張 |
| V56 | 安全性評価・監査基盤の拡張 |
| V57 | 注文変更operation・変更候補・追加在庫予約 |
| V58 | 注文変更の金融要求・取引との対応 |
| V59 | 注文切替context |
| V60 | ブラウザ認証・確認管理 |
| V61 | Dispatch送信開始の一回限り制御 |
| V62 | 取り下げ意思の保存 |
| V63 | 取り下げ専用NEW_ABORT準備 |
| V64 | 新Flow終了証拠・金融履歴の書込み制御 |
| V65 | 新Flow終了照合の走査カーソル |
| V66 | 終了照合監査の基盤 |
| V67 | 取り下げ完了・予約解放・ABORTED確定 |
| V68 | 管理者旧取消回復監査、UUID・version・状態遷移・改変防止制約 |

最終監査では開発DBのV1〜V68成功、全68件のソースchecksum一致を確認した。

## 最終回帰

独立した指定commitのコピー、新規PostgreSQL 17コンテナ、IsolatedDatabaseGuard、Mock Gateway/SMTPを使用。
開発DB `ecsite`・共用DB `ecsite_test`・ホスト5432番へのテスト接続を禁止。

- 実行: 2026-10-29 15:22:20〜15:37:58 JST
- Maven終了コード: **0**
- Tests: **4,760** / Failures: **0** / Errors: **0** / Skipped: **3**
- `PostgresTest`名の結合テスト: **54クラス・1,623件、Failures 0、Errors 0、Skipped 0**。
  これはクラス名による集計範囲であり、DBを利用する全テストの総数とは区別する。
- SKIP: Mailpit手動疎通1件、明示有効化を要するプロセス再起動試験2件。
- 除外: `OrderPaymentResetPostgresTest`のV53固定試験23件。過去スキーマ固定の契約試験と現行Entityの不一致は別課題として残す。制約を緩和してPASS扱いにはしていない。
- WARN/ERRORログには制約拒否・通信失敗・監査失敗等を再現する異常系診断を含む。Maven ERRORは0件。長いindex名の切り詰め通知（SQLSTATE 42622）を含む。
- 開発DBの保存baselineと実行後の対象行内容・主キー情報は完全一致。
- 今回の隔離コンテナは終了・削除済み。元リポジトリtarget、保全ファイル、Feature122は不変。

証跡保存先（ローカル・Git登録対象外）:

`/home/kohki/backups/ec-site/20261029T151619+0900_ab63498_failclosed_gate/`

Mavenログ、Surefire XML、集計、実行前後baseline、検証結果を保全。
**証跡3,526ファイルのSHA-256検証は全件一致**。保存済みSHA256SUMS自体のSHA-256:

`6094e75cafe3109c1d7c35ef9c43b43a256e1af374625b982a9d8d98df23b969`

先行した中断実行の証跡は別ディレクトリに保全している。中断分を最終回帰PASSの集計へ混在させない。

## 実PAY.JP TEST受入試験

### A: 通常注文・備考空欄

- 注文2: 商品53・数量1、730円、revision 0、SHIPPED。
- Payment3: CAPTURED、shipment_capture_payment_id=3。
- CAPTURE取引9: SUCCEEDED、730円、revision 0。要求・取引は各1件、備考NULL。
- 発送準備: 2026-10-29 14:42:30 JST。
- 発送: 14:44:39 JST。支払: 14:44:42 JST。
- PAY.JPの保存済み対象Flowに対するGET: HTTP200、succeeded、amount=730、amount_received=730、amount_capturable=0、JPY、manual、TEST。
- PENDING金融取引・決済不整合0件。判定PASS。

### B: 増額・全額再与信・旧取消後のCapture

- 注文3: 商品53・数量1／730円から数量2／910円へ切替、revision 1、SHIPPED。
- 旧Payment4: CANCELLED。旧PaymentへのCAPTURE取引なし。
- 新Payment5: CAPTURED、shipment_capture_payment_id=5。
- CAPTURE取引10: SUCCEEDED、910円、revision 1。要求・取引は各1件、備考NULL。
- 注文変更operation: COMPLETED、version 8。追加1個の予約CONSUMED、変更履歴1件。
- 発送準備: 2026-10-29 14:47:22 JST。
- 発送: 14:49:39 JST。支払: 14:49:40 JST。
- 新Flow GET: HTTP200、succeeded、amount=910、amount_received=910、amount_capturable=0、JPY、manual、TEST。
- 旧Flow GET: HTTP200、canceled、730円、amount_receivedは明示的JSON null、amount_capturable=730、JPY、manual、TEST。
- PENDING金融取引・決済不整合0件。商品53在庫75、二重減算なし。判定PASS。

PAY.JP結果は受入照合時の過去のGET証拠であり、この記録作成時に再取得していない。
ローカル要求・取引各1件と外部受領額一致から二重Captureの兆候はないが、GETだけで過去のHTTP POST送信回数を断定しない。
回帰テスト中の実通信防止はMock保護・最小環境・ローカルHTTP試験の確認に基づく。パケット監視による証明ではない。

## 制限・引継ぎ

- V53固定試験の互換性課題と既知SKIPは継続管理する。
- 取り下げの全分岐を実PAY.JP・ブラウザで検証したものではない。対応範囲は隔離自動テストと区別する。
- 注文1には過去の管理者回復・今回限りの例外Capture運用がある。通常処理で完了した受入A/Bと分離し、実行マーカー・監査・バックアップを残す。
- Schedulerは受入時に停止。常時稼働運用の性能・継続性までを今回の受入結果から保証しない。
- 正式受注時刻の再定義、14:00締切／14:05倉庫出力、締切直前再与信、遅延与信成功時取消、緊急出荷、倉庫出力に伴う発送準備自動移行は将来Featureとする。
- DB初期化前にV68バックアップ・復元検証、外部決済とDBの対応、受入証拠、監査・例外運用記録、回帰証跡を保全する。

本記録はコード変更・金融操作・DB補正を伴わない。GitHub反映には、この文書を対象とする別commitの承認が必要。
