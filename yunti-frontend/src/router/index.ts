import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from '../utils/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'Login',
      component: () => import('../views/login/index.vue'),
      meta: { title: '登录' },
    },
    {
      path: '/register',
      name: 'Register',
      component: () => import('../views/register/index.vue'),
      meta: { title: '注册企业账号' },
    },
    {
      path: '/',
      component: () => import('../layouts/AdminLayout.vue'),
      redirect: '/dashboard',
      children: [
        {
          path: 'dashboard',
          name: 'Dashboard',
          component: () => import('../views/dashboard/index.vue'),
          meta: { title: '数据概览' },
        },
        {
          path: 'enterprise',
          name: 'EnterpriseReview',
          component: () => import('../views/enterprise/index.vue'),
          meta: { title: '企业信息' },
        },
      ],
    },
    {
      path: '/guide',
      name: 'Guide',
      component: () => import('../views/guide/index.vue'),
      meta: { title: '企业开通引导' },
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'NotFound',
      component: () => import('../views/error/404.vue'),
    },
  ],
})

// 简单路由守卫：未登录跳登录页
router.beforeEach((to) => {
  if (!['Login', 'Register'].includes(String(to.name)) && !getToken()) {
    return { name: 'Login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router
