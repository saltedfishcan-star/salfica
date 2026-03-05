import './assets/main.css'

import { createApp } from 'vue'
import App from './App.vue'
import router from './router'

// 前端应用入口：创建应用、注入路由并挂载。
const app = createApp(App)

app.use(router)

app.mount('#app')
