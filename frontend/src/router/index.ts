import { createRouter, createWebHistory } from 'vue-router'
import DashboardView from '../views/DashboardView.vue'
import ScoresView from '../views/ScoresView.vue'
import RankingView from '../views/RankingView.vue'
import HistoryView from '../views/HistoryView.vue'
import ProfileView from '../views/ProfileView.vue'
import FriendsView from '../views/FriendsView.vue'
import ChangelogView from '../views/ChangelogView.vue'
import TermsView from '../views/TermsView.vue'
import AboutView from '../views/AboutView.vue'
import ResetPasswordView from '../views/ResetPasswordView.vue'
import ChartListView from '../views/ChartListView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/dashboard' },
    { path: '/reset-password', name: 'reset-password', component: ResetPasswordView },
    { path: '/dashboard', name: 'dashboard', component: DashboardView },
    { path: '/scores', name: 'scores', component: ScoresView },
    { path: '/ranking', name: 'ranking', component: RankingView },
    { path: '/history', name: 'history', component: HistoryView },
    { path: '/profile', name: 'profile', component: ProfileView },
    { path: '/friends', name: 'friends', component: FriendsView },
    { path: '/changelog', name: 'changelog', component: ChangelogView },
    { path: '/terms', name: 'terms', component: TermsView },
    { path: '/about', name: 'about', component: AboutView },
    { path: '/chart-list', name: 'chart-list', component: ChartListView },
    // 他ユーザーのスコアを共有リンクで閲覧するルート
    { path: '/user/:userId', name: 'user-dashboard', component: DashboardView },
    { path: '/user/:userId/scores', name: 'user-scores', component: ScoresView },
  ],
})

export default router
