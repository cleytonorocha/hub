import { createApp } from 'vue'
import './style.css'
import App from './App.vue'
import router from './router'
import { installSecureFetch } from './services/secureFetch'

installSecureFetch()
createApp(App).use(router).mount('#app')
