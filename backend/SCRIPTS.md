# backend/ 直下スクリプト・バッチ一覧

backend（Spring Boot）ディレクトリ直下に置かれているビルド／起動／テスト用バッチ・スクリプトの説明です。
本体コードは `src/main/java/` 配下にあり、以下はいずれも開発者の手元作業を補助するためのもの。

## 1. ビルド・起動関連（**現役**）

| ファイル | 種別 | 用途 |
|----------|------|------|
| [setup_maven.ps1](setup_maven.ps1) | PowerShell | **初回セットアップ用**: Apache Maven 3.9.6 をダウンロード・展開してPATHに追加し、`mvn clean compile` → `mvn spring-boot:run` を連続実行する。README.md でも推奨されている起動手順。 |
| [mvn.cmd](mvn.cmd) | Batch | `apache-maven-3.9.6/bin/mvn.cmd` へのラッパー。`backend/` 内で `mvn ...` と打てば直接このラッパーが呼ばれ、バンドルされた Maven で実行される。 |
| [do_compile.bat](do_compile.bat) | Batch | **コンパイルのみ実行**するショートカット。出力は `compile_new.log` に書き出し、最終行に `EXIT_CODE=...` を追加する。CIログ確認用。 |

### 使い分け

```powershell
# 1. 新しい環境で最初に1回
.\setup_maven.ps1

# 2. 以降は
.\mvn.cmd spring-boot:run

# 3. コンパイルだけチェックしたい場合
.\do_compile.bat
```

## 2. 手動APIテスト用スクリプト（**UNUSED: 現在は使われていない**）

以下はローカル起動した API を叩いて動作確認するためのワンショットスクリプト群。
すべて `iidxId: '9999-9999'`, `password: 'password123'` のようなハードコード値で動作する、**初期導入時の疎通確認用**。

| ファイル | 叩くエンドポイント | 認証 | 出力先 |
|----------|-------------------|------|--------|
| [test_login.js](test_login.js) | `POST /api/auth/login` | - | `test_login_res.txt` |
| [test_me.js](test_me.js) | `GET /api/auth/me` | なし（未認証時の挙動確認） | `test_res_me.txt` |
| [test_register.py](test_register.py) | `POST /api/auth/register` | - | 標準出力 |
| [test_req.js](test_req.js) | `POST /api/auth/register`（別IDで） | - | 標準出力 |
| [test_req2.js](test_req2.js) | `POST /api/auth/register`（さらに別IDで） | - | `test_res.txt` |

**注意**:
- これらの手動スクリプトは **CI では実行されない**。正規のテストは `backend/src/test/` で書くべき。
- `test_req.js` と `test_req2.js` はほぼ同一内容（違いは iidxId とレスポンス書き込み先）。
- テスト結果の `test_login_res.txt` / `test_res*.txt` は `.gitignore` に追加済み。

## 3. `apache-maven-3.9.6/` ディレクトリ

`setup_maven.ps1` が自動DLする Maven 本体。`.gitignore` で除外対象に追加済み。
既にコミットされている場合は `git rm --cached -r backend/apache-maven-3.9.6/` で untrack してください（[UNUSED.md §4](../UNUSED.md#4-git-トラッキングからの除外推奨git-rm---cached) 参照）。

## 4. ビルド成果物／ログ（**git トラッキング対象外推奨**）

以下はすべて `.gitignore` 対象。既存ファイルは `git rm --cached` が必要。

- `target/` — Maven ビルド成果物（95ファイル）
- `boot_run.log`, `build.log`, `compile.log`, `compile_utf8.log`, `run.log`, `java_ver.log`, `mvn_version.log` — 実行ログ
- `netstat.txt`, `process_list.txt` — 環境調査コマンドの出力ダンプ
