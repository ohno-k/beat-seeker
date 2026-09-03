# beat-seeker

beatmania IIDX score management and skill visualization tool.

## Project Structure
- `backend/`: Spring Boot 3.3.0 (Java 17)
- `frontend/`: Vite + Vue 3 + TypeScript + Tailwind CSS
- `tools/`: 調査・運用・検証用の単発スクリプト（旧版は `tools/legacy/`）。詳細は [SCRIPTS.md](SCRIPTS.md)
- `scripts/`: Top-Rankers データ取得パイプライン
- `sql/`: ワンショット実行用の SQL（マイグレーション・データ修復など）
- `data/`: スクリプトの入出力データ（`draft_changes*.json` 等）と曲リスト（`data/songlists/`）
- `docs/`: 設計書・運用ドキュメント・画像
- `chart_cache/`: 譜面傾向データ（`tools/batch_analyze.py` が生成）

## How to Run

### 1. Backend
Navigate to the `backend` directory and run the setup script to download Maven (only needed once per session) and start the Spring Boot application:

```powershell
cd backend
.\setup_maven.ps1
```

*(Note: In subsequent uses in the same terminal, you can just run `mvn spring-boot:run`)*

- **Requirements**: Java 17
- **Database**: H2 In-memory (default). No setup required for development.
- **Port**: 8080 (default)

### 2. Frontend
Navigate to the `frontend` directory, install dependencies (if not already done), and start the development server:

```powershell
cd frontend
npm install
npm run dev
```

- **Requirements**: Node.js
- **Port**: 5173 (default)
- **Access**: [http://localhost:5173](http://localhost:5173)

## Tech Stack (Same as PoiSpo)
- **Frontend**: Vue 3, TS, Vite, Tailwind CSS
- **Backend**: Spring Boot 3.3.0, JPA, PostgreSQL/H2, Spring Security, OAuth2

## Repository Docs

- [docs/完全設計書.md](docs/%E5%AE%8C%E5%85%A8%E8%A8%AD%E8%A8%88%E6%9B%B8.md) — 機能・構造・API仕様のワンドキュメント
- [docs/コスパ埋めレコメンド.md](docs/%E3%82%B3%E3%82%B9%E3%83%91%E5%9F%8B%E3%82%81%E3%83%AC%E3%82%B3%E3%83%A1%E3%83%B3%E3%83%89.md) — ランクアップアドバイス（期待 BEAT-PT による埋め推薦）の算出式
- [SCRIPTS.md](SCRIPTS.md) — プロジェクトルート直下のスクリプト説明
- [backend/SCRIPTS.md](backend/SCRIPTS.md) — backend のビルド・テスト用バッチ説明
- [frontend/SCRIPTS.md](frontend/SCRIPTS.md) — frontend のデータ整備スクリプト説明
- [UNUSED.md](UNUSED.md) — 未使用ファイル・メソッド、要整理対象の一覧
