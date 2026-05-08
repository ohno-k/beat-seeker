import { createApp } from 'vue'
import './style.css'
import './output.css'
import './aprilFools.css'
import App from './App.vue'
import router from './router'

// router.isReady() が解決するまで mount を遅延させる。
// これを待たないと初回ロード時に useRoute().params が空のまま onMounted が走り、
// /share/:token のような :param 系ルートで params が undefined になる競合が起きる。
const app = createApp(App).use(router)
router.isReady().then(() => app.mount('#app'))
