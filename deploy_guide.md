# Render.io デプロイガイド (Beat-Seeker)

Render.io を使って、このアプリケーションを「最も簡単に」インターネット公開する手順です。

## 1. 事前準備
1. ソースコードを GitHub にプッシュしてください。
2. Google Cloud Console で OAuth 2.0 クライアント ID を作成し、承認済みのリダイレクト URI に以下を追加してください：
   - `https://<あなたのバックエンドのURL>/login/oauth2/code/google`

## 2. データベースの作成
1. Render のダッシュボードから **New > Database** を選択。
2. Name を `beat-seeker-db` とし、PostgreSQL を作成。
3. 作成後、**Internal Database URL** をコピーしておきます。

## 3. バックエンドのデプロイ
1. **New > Web Service** を選択。
2. リポジトリを選択し、以下を設定：
   - **Name**: `beat-seeker-api`
   - **Root Directory**: `backend`
   - **Environment**: `Docker`
3. **Environment Variables** (Advanced) を追加：
   - `SPRING_DATASOURCE_URL`: `jdbc:postgresql://<DBホスト名:5432>/beatseeker` の形式で入力。
   - `SPRING_DATASOURCE_USERNAME`: `postgres`
   - `SPRING_DATASOURCE_PASSWORD`: (DBパスワード)
   - `GOOGLE_CLIENT_ID`: (取得したID)
   - `GOOGLE_CLIENT_SECRET`: (取得したシークレット)
   - `FRONTEND_URL`: `https://<あなたのフロントエンドのURL>`

## 4. フロントエンドのデプロイ
1. **New > Static Site** を選択。
2. リポジトリを選択し、以下を設定：
   - **Name**: `beat-seeker`
   - **Root Directory**: `frontend`
   - **Build Command**: `npm install && chmod +x node_modules/.bin/vite && npx vite build`
   - **Publish Directory**: `dist`
3. **Environment Variables** を追加：
   - `VITE_API_BASE`: `https://<あなたのバックエンドのURL>`

---

## 今後の更新（デプロイ）の手順
このアプリケーションは **GitHub と連動** しています。

1. **ローカルで開発**: あなたのPCでプログラムを修正します。
2. **GitHubへプッシュ**: 修正を GitHub に push します。
3. **自動デプロイ**: Render が push を検知すると、自動的にビルドとデプロイを開始します。
   - バックエンド、フロントエンドそれぞれ数分で最新の状態に更新されます。

手動でやり直したい場合は、Render の各サービス画面にある **「Manual Deploy」** ボタンから実行できます。
