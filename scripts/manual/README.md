# 手動保守スクリプト

## 注文・決済データ初期化（V53専用）は廃止しました（Feature134-B、2026-11-18）

`reset-order-payment-data.sql`／`.psql`は、Flywayの最新成功版数がV53であるDBだけを対象にした手動初期化スクリプトでした。SQL自身がV53以外を拒否するため、V54以降のDB（開発DBはV92）では実行できず、検証テスト`OrderPaymentResetPostgresTest`も現行EntityとV53スキーマの不一致（V81の`orders.acceptance_mode`など）で失敗し続けていました。

使えない破壊的SQLを残さないため、スクリプト・検証テスト・関連fixtureを削除しました。内容と2026-10-19の隔離検証記録は、追加コミット`92e1853`（`git show 92e1853`）と、削除前の`scripts/manual/README.md`（`git show 92e1853:scripts/manual/README.md`）で参照できます。

現行スキーマ向けの初期化が必要になった場合は、削除対象テーブル・保持データ・バックアップ・承認手順を改めて設計する別Featureとして作り直してください。旧SQLをそのまま版数だけ書き換えて使うことはできません（V54以降に追加された注文・決済・通知・倉庫連携・Feature125関連のテーブル、トリガー、FKを考慮していないためです）。
