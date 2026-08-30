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
const PastRankingView = () => import('../views/PastRankingView.vue')
const HistoryView = () => import('../views/HistoryView.vue')
const ProfileView = () => import('../views/ProfileView.vue')
const FriendsView = () => import('../views/FriendsView.vue')
const ChangelogView = () => import('../views/ChangelogView.vue')
const TermsView = () => import('../views/TermsView.vue')
const AboutView = () => import('../views/AboutView.vue')
const ResetPasswordView = () => import('../views/ResetPasswordView.vue')
const ChartListView = () => import('../views/ChartListView.vue')
const PastVersionScoresView = () => import('../views/PastVersionScoresView.vue')
const ShareView = () => import('../views/ShareView.vue')
const CompetitionAdminView = () => import('../views/CompetitionAdminView.vue')
const CompetitionSummaryView = () => import('../views/CompetitionSummaryView.vue')
const AdminUserComparisonView = () => import('../views/AdminUserComparisonView.vue')
const ObsIndividualStandingsView = () => import('../views/ObsIndividualStandingsView.vue')
const WrappedView = () => import('../views/WrappedView.vue')
const KinjoCupView = () => import('../views/KinjoCupView.vue')
const LeagueView = () => import('../views/LeagueView.vue')
const LoungeView = () => import('../views/LoungeView.vue')

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
    // 過去作ランキング（作品ごとに保存した最終 PT のアーカイブ）
    { path: '/past-ranking', name: 'past-ranking', component: PastRankingView },
    // タイムライン (自分 + フレンドのアップロード活動)。
    // 実体は DashboardView を流用し、App.vue 側で activeTab='timeline' を表示する設計。
    { path: '/timeline', name: 'timeline', component: DashboardView },
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
    // 使い方ガイド (各機能の操作手順)。実体は AboutView を流用し、App.vue 側で activeTab='manual' を表示する。
    { path: '/manual', name: 'manual', component: AboutView },
    // 非公式難易度表
    { path: '/difficulty-table', name: 'difficulty-table', component: AboutView },
    // 譜面リスト
    { path: '/chart-list', name: 'chart-list', component: ChartListView },
    // 作品別スコア一覧（プロフィールの過去作スコアから遷移）。
    // 実体は App.vue 側でパスを検知して activeTab='past-version-scores' として描画する。
    { path: '/past-scores/:version', name: 'past-version-scores', component: PastVersionScoresView },
    // 他ユーザーのスコアを共有リンクで閲覧するルート
    // DashboardView / ScoresView を使い回し、URL パラメータ :userId で表示対象を切り替える
    { path: '/user/:userId', name: 'user-dashboard', component: DashboardView },
    { path: '/user/:userId/scores', name: 'user-scores', component: ScoresView },
    // 月末振り返り（Spotify Wrapped 風）。自分用は要ログイン、/user/:userId/wrapped は公開（OGP 展開対象）
    { path: '/wrapped/:year/:month', name: 'wrapped', component: WrappedView },
    { path: '/user/:userId/wrapped/:year/:month', name: 'user-wrapped', component: WrappedView },
    // ログイン不要の URL 共有ページ（発行されたトークンで閲覧）
    { path: '/share/:token', name: 'share-view', component: ShareView },
    // きんじょー杯 特設ページ（参加者一覧・公開閲覧。追加/削除は管理者のみ）。
    // App.vue 側でパスを検知してスタンドアロン描画する。
    { path: '/kinjocup', name: 'kinjocup', component: KinjoCupView },
    // リーグモード（週次課題曲 3 曲で昇降格を競う。要ログイン）
    { path: '/league', name: 'league', component: LeagueView },
    // 隠しページ: 軍人将棋（友達との対局用）。サイト内のどこからもリンクせず、
    // robots.txt でもクロール除外している。実体は App.vue 側でパスを検知してスタンドアロン描画する。
    { path: '/lounge', name: 'lounge', component: LoungeView },
    // 大会管理: Competition セクションの 4 ID ホワイトリストで保護。
    // 直接アクセス・ブックマーク用エントリ。実際の権限判定はサーバ側 + View 側で行う。
    { path: '/competition-admin', name: 'competition-admin', component: CompetitionAdminView },
    // 大会サマリー (団体戦): 試合別 / 選手別の全結果一覧。運営画面のボタンから別タブで開く。
    // 要主催権限 (サーバ側で判定)。実体は App.vue 側でパスを検知してスタンドアロン描画する。
    { path: '/competition/summary/:competitionId', name: 'competition-summary', component: CompetitionSummaryView },
    // OBS ブラウザソース用の個人戦順位表 (公開トークン経由・認証不要・透過背景)。
    { path: '/obs/individual/:token', name: 'obs-individual-standings', component: ObsIndividualStandingsView },
    // 管理者用: 任意の 2 ユーザー間のスコア勝敗比較。
    // useAdmin.isAdmin で表示ガード + サーバ側 /api/admin/** で実権限チェック。
    { path: '/admin/user-comparison', name: 'admin-user-comparison', component: AdminUserComparisonView },
  ],
})

export default router
