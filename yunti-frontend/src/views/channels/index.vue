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
        <el-table-column label="技能组" min-width="130">
          <template #default="{ row }">{{ groupNameOf(row.skillGroupId) || '未指定' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="290">
          <template #default="{ row }">
            <el-button link type="primary" @click="openBindGroup(row)">绑定技能组</el-button>
            <el-button link type="primary" @click="router.push(`/channels/setup/key/${row.id}`)">查看密钥</el-button>
            <el-button link :type="row.status === 1 ? 'danger' : 'success'" @click="toggle(row)">
              {{ row.status === 1 ? '禁用' : '启用' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
    <el-dialog v-model="bindVisible" :title="`绑定技能组 · ${bindRow?.name || ''}`" width="460px">
      <p class="bind-tip">该渠道的会话优先分配给组内在线坐席；无人可接时按排队升级时长分配。选择不指定则不限技能组。</p>
      <el-select v-model="bindGroupId" style="width: 100%">
        <el-option value="" label="不指定（不限技能组）" />
        <el-option v-for="group in groups" :key="group.id" :value="group.id" :label="group.name" />
      </el-select>
      <template #footer>
        <el-button @click="bindVisible = false">取消</el-button>
        <el-button type="primary" :loading="binding" @click="submitBindGroup">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listChannels, updateChannelStatus, type ChannelResult } from '../../api/customer/channel'
import { bindChannelSkillGroup, listSkillGroups, type SkillGroupItem } from '../../api/customer/skillGroup'

const router = useRouter()
const loading = ref(false)
const channels = ref<ChannelResult[]>([])
const groups = ref<SkillGroupItem[]>([])
const bindVisible = ref(false)
const binding = ref(false)
const bindRow = ref<ChannelResult | null>(null)
const bindGroupId = ref('')

onMounted(load)

async function load() {
  loading.value = true
  try {
    const [channelList, groupList] = await Promise.all([listChannels(), listSkillGroups()])
    channels.value = channelList
    groups.value = groupList
    if (channels.value.some((channel) => channel.status === 1)) {
      localStorage.setItem('yunti_onboard_done', 'true')
    }
  } finally { loading.value = false }
}

function groupNameOf(groupId?: string | null) {
  return groups.value.find((group) => group.id === groupId)?.name || ''
}

function openBindGroup(row: ChannelResult) {
  bindRow.value = row
  bindGroupId.value = row.skillGroupId || ''
  bindVisible.value = true
}

async function submitBindGroup() {
  if (!bindRow.value) return
  binding.value = true
  try {
    await bindChannelSkillGroup(bindRow.value.id, bindGroupId.value || null)
    ElMessage.success('渠道绑定已更新')
    bindVisible.value = false
    await load()
  } finally { binding.value = false }
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
.bind-tip { font-size: 13px; line-height: 1.7; color: #64748b; margin-bottom: 12px; }
@media (max-width: 650px) { .page-head { flex-direction: column; } }
</style>
