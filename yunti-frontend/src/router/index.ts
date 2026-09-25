import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from '../utils/auth'
import { useUserStore } from '../stores/user'

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
        {
          path: 'admin/reviews',
          name: 'PlatformReview',
          component: () => import('../views/admin/review/index.vue'),
          meta: { title: '企业审核', requiresPlatform: true },
        },
        {
          path: 'channels',
          name: 'Channels',
          component: () => import('../views/channels/index.vue'),
          meta: { title: '渠道接入', requiresEnterprise: true },
        },
        {
          path: 'channels/setup/:stage/:channelId?',
          name: 'ChannelSetup',
          component: () => import('../views/channels/setup.vue'),
          meta: { title: '渠道接入流程', requiresEnterprise: true },
        },
        {
          path: 'modules/bot',
          name: 'BotConfig',
          component: () => import('../views/bot/index.vue'),
          meta: { title: '智能机器人', requiresEnterprise: true },
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
router.beforeEach(async (to) => {
  if (!['Login', 'Register'].includes(String(to.name)) && !getToken()) {
    return { name: 'Login', query: { redirect: to.fullPath } }
  }
  if (to.meta.requiresPlatform) {
    const userStore = useUserStore()
    if (!userStore.userId) {
      try { await userStore.fetchProfile() } catch { return { name: 'Login' } }
    }
    if (userStore.userType !== 1) return { name: 'Dashboard' }
  }
  if (to.meta.requiresEnterprise) {
    const userStore = useUserStore()
    if (!userStore.userId) {
      try { await userStore.fetchProfile() } catch { return { name: 'Login' } }
    }
    if (userStore.userType !== 2) return { name: 'Dashboard' }
    if (!userStore.tenantCode || userStore.tenantCode === 'PLATFORM') return { name: 'Guide' }
  }
  return true
})

export default router
