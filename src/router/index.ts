import { createRouter, createWebHistory } from 'vue-router'
import SetupView from '../views/SetupView.vue'
import BoardView from '../views/BoardView.vue'
import CaptureView from '../views/CaptureView.vue'

// 路由设计：默认进入配置页，再跳转到榜单页。
const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      redirect: '/setup',
    },
    {
      path: '/setup',
      name: 'setup',
      component: SetupView,
    },
    {
      path: '/board',
      name: 'board',
      component: BoardView,
    },
    {
      path: '/capture',
      name: 'capture',
      component: CaptureView,
    },
    {
      path: '/about',
      name: 'about',
      // route level code-splitting
      // this generates a separate chunk (About.[hash].js) for this route
      // which is lazy-loaded when the route is visited.
      component: () => import('../views/AboutView.vue'),
    },
  ],
})

export default router
