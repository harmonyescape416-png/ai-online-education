import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { ElLoading } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import 'element-plus/es/components/loading/style/css'
import './styles/tokens.css'
import './styles/main.css'
import App from './App.vue'
import router from './router'

createApp(App).use(createPinia()).use(router).use(ElLoading).mount('#app')
