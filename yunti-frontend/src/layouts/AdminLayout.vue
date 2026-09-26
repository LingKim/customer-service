<template>
  <el-container class="admin-layout">
    <el-aside width="220px" class="admin-aside">
      <div class="admin-logo">
        <el-icon :size="22"><ChatDotRound /></el-icon>
        <span>云梯智能客服</span>
      </div>
      <el-menu
        router
        :default-active="$route.path"
        class="admin-menu"
        background-color="#0b1220"
        text-color="#8a97ad"
        active-text-color="#ffffff"
      >
        <el-menu-item index="/dashboard">
          <el-icon><DataBoard /></el-icon>
          <span>数据概览</span>
        </el-menu-item>
        <el-menu-item v-if="userStore.userType === 2 && canManage" index="/enterprise">
          <el-icon><OfficeBuilding /></el-icon>
          <span>企业信息</span>
        </el-menu-item>
        <el-menu-item v-if="userStore.userType === 2 && canManage" index="/channels">
          <el-icon><Connection /></el-icon>
          <span>渠道接入</span>
        </el-menu-item>
        <el-menu-item v-if="userStore.userType === 2 && canManage" index="/modules/bot">
          <el-icon><MagicStick /></el-icon>
          <span>智能机器人</span>
        </el-menu-item>
        <el-menu-item v-if="userStore.userType === 2 && canManage" index="/members">
          <span>成员管理</span>
        </el-menu-item>
        <el-menu-item v-if="userStore.userType === 2 && canManage" index="/skill-groups">
          <el-icon><Connection /></el-icon>
          <span>技能组</span>
        </el-menu-item>
        <el-menu-item v-if="userStore.userType === 2" index="/modules/qa">
          <span>质检中心</span>
        </el-menu-item>
        <el-menu-item v-if="userStore.userType === 2" index="/modules/kb">
          <span>企业知识库</span>
        </el-menu-item>
        <el-menu-item v-if="userStore.userType === 2" index="/modules/qa/alerts">
          <span>实时预警</span>
        </el-menu-item>
        <el-menu-item v-if="userStore.userType === 2" index="/modules/workspace">
          <span>在线客服</span>
        </el-menu-item>
        <el-menu-item v-if="userStore.userType === 2" index="/modules/tickets">
          <span>工单中心</span>
          <el-badge v-if="ticketTodo" :value="ticketTodo" class="menu-count" />
        </el-menu-item>
        <el-menu-item v-if="userStore.userType === 2" index="/modules/customers">
          <span>客户 360</span>
        </el-menu-item>
        <el-menu-item v-if="userStore.userType === 1" index="/admin/reviews">
          <el-icon><Stamp /></el-icon>
          <span>企业审核</span>
        </el-menu-item>
        <el-menu-item v-if="userStore.userType === 1" index="/platform/support">
          <span>支持工单</span>
          <el-badge v-if="platformTodo" :value="platformTodo" class="menu-count" />
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="admin-header">
        <el-breadcrumb separator="/">
          <el-breadcrumb-item :to="{ path: '/dashboard' }">首页</el-breadcrumb-item>
          <el-breadcrumb-item>{{ $route.meta.title || '工作台' }}</el-breadcrumb-item>
        </el-breadcrumb>
        <div class="admin-user">
          <el-badge v-if="userStore.userType === 2" :value="unreadCount" :hidden="!unreadCount">
            <el-button text @click="openNotifications">消息</el-button>
          </el-badge>
          <span>{{ userStore.name || '未登录' }}</span>
          <el-dropdown @command="handleCommand">
            <span class="admin-user-trigger">
              <el-icon><UserFilled /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">个人中心</el-dropdown-item>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <el-main class="admin-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
  <el-drawer v-model="notifyOpen" title="消息中心" size="440px" @open="loadNotifications">
    <el-button :disabled="!unreadCount" @click="markAllRead">全部标为已读</el-button>
    <el-empty v-if="!notices.length" description="暂无消息" />
    <div v-for="notice in notices" :key="notice.id" class="notice" :class="{ unread: !notice.read }"
         @click="openNotice(notice)">
      <strong>{{ notice.title }}</strong>
      <p>{{ notice.content }}</p>
      <small>{{ notice.publishTime }}</small>
    </div>
  </el-drawer>
</template>
<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getEnterpriseGuide } from '../api/enterprise'
import { fetchMemberAccess } from '../api/member'
import { fetchTicketOverview } from '../api/customer/ticket'
import { fetchPlatformTicketOverview } from '../api/platform/ticket'
import { fetchNotifications, markAllNotificationsRead, markNotificationRead,
  type NotificationItem } from '../api/customer/notification'
import { useUserStore } from '../stores/user'

const router = useRouter()
const userStore = useUserStore()
const DONE_KEY = 'yunti_onboard_done'
const canManage = ref(false)
const ticketTodo = ref(0)
const platformTodo = ref(0)
const unreadCount = ref(0)
const notifyOpen = ref(false)
const notices = ref<NotificationItem[]>([])
let refreshTimer: number | undefined

async function refreshTicketStatus() {
  try {
    if (userStore.userType === 1) {
      const overview = await fetchPlatformTicketOverview()
      platformTodo.value = overview.pending + overview.processing + overview.confirming
    } else if (userStore.userType === 2) {
      const overview = await fetchTicketOverview()
      ticketTodo.value = overview.pending + overview.processing + overview.confirming
      await loadNotifications()
    }
  } catch {
    // 工单状态不可用时不影响其它菜单。
  }
}

async function loadNotifications() {
  if (userStore.userType !== 2) return
  try {
    notices.value = await fetchNotifications({ limit: 50 })
    unreadCount.value = notices.value.filter((notice) => !notice.read).length
  } catch {
    // 消息接口不可用时保留已有列表。
  }
}

function openNotifications() {
  notifyOpen.value = true
  void loadNotifications()
}

async function openNotice(notice: NotificationItem) {
  if (!notice.read) {
    try {
      await markNotificationRead(notice.id)
      notice.read = true
      unreadCount.value = Math.max(0, unreadCount.value - 1)
    } catch {
      return
    }
  }
  if (notice.linkView === 'tickets' || notice.linkView === 'platform-support') {
    notifyOpen.value = false
    void router.push(notice.linkView === 'platform-support'
      ? { path: '/modules/tickets', query: { ticketType: '2' } }
      : '/modules/tickets')
  }
}

async function markAllRead() {
  try {
    await markAllNotificationsRead()
    notices.value.forEach((notice) => { notice.read = true })
    unreadCount.value = 0
  } catch {
    // 失败时保留未读状态。
  }
}

onMounted(async () => {
  try {
    if (!userStore.userId) await userStore.fetchProfile()
    void refreshTicketStatus()
    refreshTimer = window.setInterval(() => void refreshTicketStatus(), 60_000)
    if (userStore.userType === 1) return
    if (userStore.tenantCode && userStore.tenantCode !== 'PLATFORM') {
      const access = await fetchMemberAccess()
      canManage.value = access.canManage
      if (!access.canManage) return
    } else {
      canManage.value = true
    }
    const guide = await getEnterpriseGuide()
    const done = localStorage.getItem(DONE_KEY) === 'true'
    const routeName = router.currentRoute.value.name
    const onboardingRoute = routeName === 'Channels' || routeName === 'ChannelSetup'
    if ((guide.stage !== 'APPROVED' || (!done && !onboardingRoute)) && routeName !== 'EnterpriseReview') {
      await router.replace('/guide')
    }
  } catch {
    // 状态查询失败时保留页面，由具体功能页呈现错误。
  }
})

onUnmounted(() => {
  if (refreshTimer) window.clearInterval(refreshTimer)
})

async function handleCommand(command: string | number | object) {
  if (command === 'logout') {
    await userStore.logout()
    localStorage.removeItem(DONE_KEY)
    ElMessage.success('已退出登录')
    router.push('/login')
  } else if (command === 'profile') {
    ElMessage.info('个人中心页面待开发')
  }
}
</script>
<style scoped>
.admin-layout {
  height: 100%;
}

.admin-aside {
  background: #0b1220;
  display: flex;
  flex-direction: column;
}

.admin-logo {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #fff;
  font-size: 16px;
  font-weight: 700;
  border-bottom: 1px solid rgba(148, 163, 184, 0.12);
}

.admin-menu {
  border-right: none;
  flex: 1;
}

.admin-header {
  background: #fff;
  border-bottom: 1px solid #e6e8ef;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.admin-user {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 14px;
  color: #475569;
}

.admin-user-trigger {
  cursor: pointer;
  display: inline-flex;
  align-items: center;
}

.admin-main {
  background: #f5f6f8;
}
.menu-count { margin-left: auto; }
.notice { padding: 14px 0; border-bottom: 1px solid #e6e8ef; cursor: pointer; }
.notice.unread { background: #fff7ed; }
.notice p { color: #64748b; margin: 6px 0; }
.notice small { color: #94a3b8; }
</style>
