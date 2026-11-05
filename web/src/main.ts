import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import './styles/tokens.css'
import App from './App.vue'
import router from './router'

// tokens.css 必须在 Element Plus 样式之后引入，才能覆盖 --el-* 变量
createApp(App).use(ElementPlus).use(router).mount('#app')
