<template>
  <div class="channels-page">
    <div class="page-head">
      <div><h1>渠道接入</h1><p>管理网站、微信公众号和微信小程序客户入口。</p></div>
      <el-button type="primary" @click="router.push('/channels/setup/create')">新增渠道</el-button>
    </div>
    <el-card shadow="never">
      <el-table v-loading="loading" :data="channels" empty-text="还没有渠道，请先创建">
        <el-table-column label="类型" min-width="130">
          <template #default="{ row }">{{ typeText(row.channelType) }}</template>
        </el-table-column>
        <el-table-column prop="name" label="渠道名称" min-width="150" />
        <el-table-column prop="channelId" label="渠道 ID" min-width="225" />
        <el-table-column prop="creatorName" label="创建人" min-width="95" />
        <el-table-column label="创建时间" min-width="160">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }"><el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '已启用' : '已禁用' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="190">
          <template #default="{ row }">
            <el-button link type="primary" @click="router.push(`/channels/setup/key/${row.id}`)">查看密钥</el-button>
            <el-button link :type="row.status === 1 ? 'danger' : 'success'" @click="toggle(row)">
              {{ row.status === 1 ? '禁用' : '启用' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listChannels, updateChannelStatus, type ChannelResult } from '../../api/customer/channel'

const router = useRouter()
const loading = ref(false)
const channels = ref<ChannelResult[]>([])

onMounted(load)

async function load() {
  loading.value = true
  try {
    channels.value = await listChannels()
    if (channels.value.some((channel) => channel.status === 1)) {
      localStorage.setItem('yunti_onboard_done', 'true')
    }
  } finally { loading.value = false }
}

function typeText(type: number) {
  return type === 1 ? '网站在线客服' : type === 2 ? '微信公众号' : '微信小程序'
}

function formatTime(time?: string) {
  return time ? time.replace('T', ' ').slice(0, 19) : '—'
}

async function toggle(channel: ChannelResult) {
  const enabled = channel.status !== 1
  try {
    await ElMessageBox.confirm(`确认${enabled ? '启用' : '禁用'}渠道「${channel.name}」？`, '渠道状态', { type: 'warning' })
  } catch { return }
  await updateChannelStatus(channel.id, enabled)
  ElMessage.success(enabled ? '渠道已启用' : '渠道已禁用')
  await load()
}
</script>

<style scoped>
.channels-page { width: 100%; }
.page-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; margin-bottom: 18px; }
.page-head h1 { margin: 0 0 6px; font-size: 23px; color: #172033; }
.page-head p { margin: 0; color: #768197; }
@media (max-width: 650px) { .page-head { flex-direction: column; } }
</style>
