import { createApp } from 'vue'
// カスケードレイヤーの順番の宣言（Tailwind の base を Vuetify より下に置く）。必ず vuetify/styles より前
import './layers.css'
import 'vuetify/styles'
import './style.css'
import './output.css'
import './aprilFools.css'
import App from './App.vue'
import router from './router'
import { vuetify, syncVuetifyThemeWithDarkClass } from './plugins/vuetify'

// router.isReady() が解決するまで mount を遅延させる。
// これを待たないと初回ロード時に useRoute().params が空のまま onMounted が走り、
// /share/:token のような :param 系ルートで params が undefined になる競合が起きる。
const app = createApp(App).use(router).use(vuetify)
syncVuetifyThemeWithDarkClass()
router.isReady().then(() => app.mount('#app'))
