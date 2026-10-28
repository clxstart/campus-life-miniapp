import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './style.css'
import { installErrorReporting, reportError } from './utils/error-report'

// 先装错误上报，再挂应用：这样应用初始化阶段的报错也能被收到
installErrorReporting()

const app = createApp(App)
app.use(createPinia())
app.use(router)

// 组件渲染/生命周期里抛的错：Vue 会拦下来不让它冒到 window.onerror，所以要单独接
app.config.errorHandler = (error, instance, info) => {
  const route = instance && instance.$route ? instance.$route.fullPath : (location.hash || '')
  reportError(route, error)
  console.error('[campus] 组件错误', error, info)
}

app.mount('#app')
