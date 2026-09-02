import { createApp } from 'vue'
import './style.css'
import './output.css'
import './aprilFools.css'
import App from './App.vue'
import MaintenanceView from './views/MaintenanceView.vue'
import router from './router'
import { useMaintenance } from './composables/useMaintenance'

// メンテナンス中は App 自体をマウントせず、メンテナンス画面だけを描画する。
// App.vue はマウント時にスコア取得や認証チェックの API を叩くため、
// ここで差し替えることで停止中のバックエンドへの無駄なリクエストも発生しない。
// 解除するときは `composables/useMaintenance.ts` の MAINTENANCE_MODE を false に戻す。
const { isMaintenance } = useMaintenance()
const RootComponent = isMaintenance.value ? MaintenanceView : App

// router.isReady() が解決するまで mount を遅延させる。
// これを待たないと初回ロード時に useRoute().params が空のまま onMounted が走り、
// /share/:token のような :param 系ルートで params が undefined になる競合が起きる。
const app = createApp(RootComponent).use(router)
router.isReady().then(() => app.mount('#app'))
