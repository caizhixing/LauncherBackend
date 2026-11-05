import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'

// 工程引导占位入口（PRD feedback-web-admin §8）：仅装配 Element Plus；
// 路由与守卫由 issue #1 交付，业务代码不进引导提交
createApp(App).use(ElementPlus).mount('#app')
