/**
 * 【ルーターの役割】 Vue Router による SPA ルーティング定義。
 *
 * beat-seeker は SPA 構成で、ほぼすべての画面遷移をクライアント側で完結させる。
 * ルートは「自分自身の画面」と「他ユーザーを覗くための /user/:userId 系」の 2 グループに大別される。
 *
 * 履歴モード: HTML5 History API（createWebHistory）
 *   → URL に `#` を付けずに `/dashboard` のような見た目の綺麗なパスで遷移できる。
 *     本番環境ではサーバー側で「存在しないパスは index.html にフォールバック」させる必要あり。
 *
 * 各ビューは遅延インポート（`() => import(...)`）にしてあり、
 * 初回ロードでは必要なビューのチャンクだけをフェッチする。
 * ルート / ダッシュボードは多くのユーザーの最初の着地点なので、
 * 初回応答性のため同期インポートとし、残りはルート遷移時に遅延ロードする。
 */
import { createRouter, createWebHistory } from 'vue-router'
import DashboardView from '../views/DashboardView.vue'

const ScoresView = () => import('../views/ScoresView.vue')
const RankingView = () => import('../views/RankingView.vue')
const HistoryView = () => import('../views/HistoryView.vue')
const ProfileView = () => import('../views/ProfileView.vue')
const FriendsView = () => import('../views/FriendsView.vue')
const ChangelogView = () => import('../views/ChangelogView.vue')
const TermsView = () => import('../views/TermsView.vue')
const AboutView = () => import('../views/AboutView.vue')
const ResetPasswordView = () => import('../views/ResetPasswordView.vue')
const ChartListView = () => import('../views/ChartListView.vue')
const ScorePredictionView = () => import('../views/ScorePredictionView.vue')

/**
 * SPA のルートテーブル定義。
 * main.ts で app.use(router) されて Vue アプリに組み込まれる。
 *
 * 注: 実際のビュー切替は App.vue 内の `activeTab` ベースで行っており、
 * このルータは主に router.push 経由のユーザー間遷移と URL の構築に使われる。
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    // ルート (/) は公開ランディング。未ログインユーザーが直接来る場合のクロール対象。
    // ログイン済みユーザーは App.vue 側で activeTab を 'dashboard' に切り替える。
    { path: '/', name: 'home', component: DashboardView },
    // パスワードリセット画面（メールリンクから直接遷移）
    { path: '/reset-password', name: 'reset-password', component: ResetPasswordView },
    // 自分のダッシュボード
    { path: '/dashboard', name: 'dashboard', component: DashboardView },
    // 自分のスコア一覧
    { path: '/scores', name: 'scores', component: ScoresView },
    // ランキング
    { path: '/ranking', name: 'ranking', component: RankingView },
    // プレイ履歴
    { path: '/history', name: 'history', component: HistoryView },
    // プロフィール設定
    { path: '/profile', name: 'profile', component: ProfileView },
    // フレンド一覧
    { path: '/friends', name: 'friends', component: FriendsView },
    // 更新履歴
    { path: '/changelog', name: 'changelog', component: ChangelogView },
    // 利用規約
    { path: '/terms', name: 'terms', component: TermsView },
    // プライバシーポリシー (利用規約から分離: AdSense 審査向け)
    { path: '/privacy-policy', name: 'privacy-policy', component: TermsView },
    // お問い合わせ・運営者情報 (利用規約から分離)
    { path: '/contact', name: 'contact', component: TermsView },
    // サービス説明（About）
    { path: '/about', name: 'about', component: AboutView },
    // 攻略ガイド (一覧 + 各記事)
    { path: '/guide', name: 'guide-list', component: AboutView },
    { path: '/guide/:slug', name: 'guide-article', component: AboutView },
    // 非公式難易度表
    { path: '/difficulty-table', name: 'difficulty-table', component: AboutView },
    // 譜面リスト
    { path: '/chart-list', name: 'chart-list', component: ChartListView },
    // 譜面分析（スコア予測）の譜面別ディープリンク
    // textage の譜面ID `<version>/<slug>.html?<diffCode>` を 3 つのパスセグメントに分解して保持。
    // 例: textage `30/_cmflg.html?1AC00` → URL `/chart/30/_cmflg/1AC00`
    // 外部サイトからのリンクや SNS 共有用の安定した URL として使う。
    { path: '/chart/:version/:slug/:diff', name: 'chart-analysis', component: ScorePredictionView },
    // 他ユーザーのスコアを共有リンクで閲覧するルート
    // DashboardView / ScoresView を使い回し、URL パラメータ :userId で表示対象を切り替える
    { path: '/user/:userId', name: 'user-dashboard', component: DashboardView },
    { path: '/user/:userId/scores', name: 'user-scores', component: ScoresView },
  ],
})

export default router
