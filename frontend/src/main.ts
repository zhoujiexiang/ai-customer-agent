import { createApp } from 'vue'
import { createPinia } from 'pinia'
import naive from 'naive-ui'
import App from './App.vue'
import router from './router'
import './styles/main.css'

const app = createApp(App)

app.use(createPinia())
app.use(router)
// 全量引入 Naive UI：本项目组件用量不大，全量的 DX 收益大于那点体积
app.use(naive)

app.mount('#app')
