import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from '../utils/auth'
import { useUserStore } from '../stores/user'
import { fetchMemberAccess } from '../api/member'

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
      path: '/invite/accept/:code',
      name: 'InviteAccept',
      component: () => import('../views/invite/accept.vue'),
      meta: { title: '接受邀请' },
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
          meta: { title: '企业信息', requiresEnterprise: true, requiresManager: true },
        },
        {
          path: 'admin/reviews',
          name: 'PlatformReview',
          component: () => import('../views/admin/review/index.vue'),
          meta: { title: '企业审核', requiresPlatform: true },
        },
        {
          path: 'platform/support',
          name: 'PlatformSupportTickets',
          component: () => import('../views/platform/support/index.vue'),
          meta: { title: '支持工单', requiresPlatform: true },
        },
        {
          path: 'channels',
          name: 'Channels',
          component: () => import('../views/channels/index.vue'),
          meta: { title: '渠道接入', requiresEnterprise: true, requiresManager: true },
        },
        {
          path: 'channels/setup/:stage/:channelId?',
          name: 'ChannelSetup',
          component: () => import('../views/channels/setup.vue'),
          meta: { title: '渠道接入流程', requiresEnterprise: true, requiresManager: true },
        },
        {
          path: 'modules/bot',
          name: 'BotConfig',
          component: () => import('../views/bot/index.vue'),
          meta: { title: '智能机器人', requiresEnterprise: true, requiresManager: true },
        },
        {
          path: 'members',
          name: 'Members',
          component: () => import('../views/members/index.vue'),
          meta: { title: '成员管理', requiresEnterprise: true, requiresManager: true },
        },
        {
          path: 'skill-groups',
          name: 'SkillGroups',
          component: () => import('../views/skill-groups/index.vue'),
          meta: { title: '技能组', requiresEnterprise: true, requiresManager: true },
        },
        {
          path: 'modules/qa',
          name: 'QualityAssurance',
          component: () => import('../views/qa/index.vue'),
          meta: { title: '质检中心', requiresEnterprise: true },
        },
        {
          path: 'modules/kb',
          name: 'KnowledgeBase',
          component: () => import('../views/kb/index.vue'),
          meta: { title: '企业知识库', requiresEnterprise: true },
        },
        {
          path: 'modules/qa/alerts',
          name: 'QaAlerts',
          component: () => import('../views/qa/alerts.vue'),
          meta: { title: '实时预警', requiresEnterprise: true },
        },
        {
          path: 'modules/workspace',
          name: 'Workspace',
          component: () => import('../views/workspace/index.vue'),
          meta: { title: '在线客服', requiresEnterprise: true },
        },
        {
          path: 'modules/tickets',
          name: 'Tickets',
          component: () => import('../views/tickets/index.vue'),
          meta: { title: '工单中心', requiresEnterprise: true },
        },
        {
          path: 'modules/customers',
          name: 'Customers',
          component: () => import('../views/customers/index.vue'),
          meta: { title: '客户 360', requiresEnterprise: true },
        },
        {
          path: 'modules/customers/tags',
          name: 'CustomerTags',
          component: () => import('../views/customers/tags.vue'),
          meta: { title: '标签体系', requiresEnterprise: true },
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
      path: '/visitor',
      name: 'Visitor',
      component: () => import('../views/visitor/index.vue'),
      meta: { title: '在线客服', public: true },
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
  if (!to.meta.public && !['Login', 'Register', 'InviteAccept'].includes(String(to.name)) && !getToken()) {
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
  if (to.meta.requiresManager || to.name === 'Guide') {
    const userStore = useUserStore()
    if (!userStore.userId) {
      try { await userStore.fetchProfile() } catch { return { name: 'Login' } }
    }
    if (userStore.userType === 2 && userStore.tenantCode !== 'PLATFORM') {
      try {
        const access = await fetchMemberAccess()
        if (!access.canManage) return { name: 'Dashboard' }
      } catch { return { name: 'Dashboard' } }
    }
  }
  return true
})

export default router
