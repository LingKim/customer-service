<template>
  <div class="settings-page">
    <div class="page-title-row">
      <div>
        <h2 class="page-title">成员管理</h2>
        <p class="page-sub">客服团队成员 · 邀请加入 · 角色与状态管理</p>
      </div>
      <el-button type="primary" @click="openInviteDialog">
        <el-icon class="btn-icon"><Plus /></el-icon>
        邀请成员
      </el-button>
    </div>

    <div class="stat-row">
      <div class="stat-card">
        <div class="stat-value">{{ members.length }}</div>
        <div class="stat-label">成员总数</div>
      </div>
      <div class="stat-card">
        <div class="stat-value">{{ invitePendingCount }}</div>
        <div class="stat-label">邀请中</div>
      </div>
      <div class="stat-card">
        <div class="stat-value">{{ invites.filter((i) => i.status === 2).length }}</div>
        <div class="stat-label">已加入</div>
      </div>
    </div>
    <el-card shadow="never" class="main-card">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="成员列表" name="members">
          <el-table :data="members" v-loading="loadingMembers" stripe>
            <el-table-column type="index" label="序号" width="70" />
            <el-table-column label="成员" min-width="220">
              <template #default="{ row }">
                <div class="member-cell">
                  <div class="member-avatar">{{ row.name.slice(0, 1) }}</div>
                  <div>
                    <div class="member-name">
                      {{ row.name }}
                      <el-tag v-if="row.roleCode === 'ADMIN'" size="small" type="danger" effect="light">管理员</el-tag>
                    </div>
                    <div class="member-sub mono">{{ row.userNo }}</div>
                  </div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="角色" width="150">
              <template #default="{ row }">
                <el-tag :type="roleTagType(row.roleCode)" effect="light">{{ row.roleName }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="联系方式" min-width="220">
              <template #default="{ row }">
                <div class="contact-line">{{ row.email || '—' }}</div>
                <div class="member-sub">{{ row.phone || '未绑定手机号' }}</div>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag :type="row.status === 1 ? 'success' : 'info'" effect="light">
                  {{ row.status === 1 ? '在职' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="最近活跃" width="170">
              <template #default="{ row }">{{ formatTime(row.lastLoginTime) }}</template>
            </el-table-column>
            <el-table-column label="加入时间" width="170">
              <template #default="{ row }">{{ formatTime(row.joinTime) }}</template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="邀请记录" name="invites">
          <div class="invite-tip">
            <el-icon :size="16"><Bell /></el-icon>
            <span>邀请链接默认 7 天有效，对方打开链接完成注册后将自动加入本企业并绑定所选角色。</span>
          </div>
          <el-table :data="invites" v-loading="loadingInvites" stripe>
            <el-table-column type="index" label="序号" width="70" />
            <el-table-column label="邀请对象" min-width="200">
              <template #default="{ row }">
                <div>{{ row.email || row.phone || '—' }}</div>
                <div class="member-sub mono">{{ row.inviteCode }}</div>
              </template>
            </el-table-column>
            <el-table-column label="角色" width="130">
              <template #default="{ row }">{{ row.roleName || '—' }}</template>
            </el-table-column>
            <el-table-column label="邀请人" width="130">
              <template #default="{ row }">{{ row.inviterName || '—' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag :type="inviteTagType(row.status)" effect="light">{{ row.statusText }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="创建时间" width="170">
              <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
            </el-table-column>
            <el-table-column label="有效期至" width="170">
              <template #default="{ row }">{{ formatTime(row.expireTime) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="280" fixed="right">
              <template #default="{ row }">
                <el-button v-if="row.status === 1" link type="primary" @click="copyInviteUrl(row.inviteCode)">
                  复制链接
                </el-button>
                <el-button
                  v-if="row.status === 1 && row.email"
                  link
                  type="primary"
                  :loading="resendingCode === row.inviteCode"
                  @click="resend(row)"
                >
                  重发邮件
                </el-button>
                <el-button v-if="row.status === 1" link type="danger" @click="revoke(row)">
                  撤销
                </el-button>
                <span v-else class="member-sub">—</span>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>
    <el-dialog
      v-model="inviteVisible"
      title="邀请客服成员"
      width="560px"
      :close-on-click-modal="false"
    >
      <el-form label-position="top">
        <div class="field-tip">填写对方邮箱后，系统会发送一封专业邀请邮件；对方点击邮件按钮即可进入邀请页完成注册。</div>
        <el-form-item label="受邀邮箱" required>
          <el-input v-model="inviteForm.email" maxlength="128" placeholder="例如：agent@example.com" clearable />
        </el-form-item>
        <el-form-item label="绑定手机号（可选）">
          <el-input v-model="inviteForm.phone" maxlength="20" placeholder="用于登录与身份校验" clearable />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="inviteForm.roleCode" class="full-width" placeholder="请选择成员角色">
            <el-option v-for="role in roleOptions" :key="role.code" :label="role.name" :value="role.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="邀请有效期">
          <el-radio-group v-model="inviteForm.expireDays">
            <el-radio-button :value="7">7 天</el-radio-button>
            <el-radio-button :value="30">30 天</el-radio-button>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="inviteVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitInvite">生成邀请链接</el-button>
      </template>
    </el-dialog>
    <el-dialog
      v-model="inviteResultVisible"
      :title="createdInvite?.emailSent ? '邀请邮件已发送' : '邀请链接已生成'"
      width="600px"
      :close-on-click-modal="false"
      @closed="createdInvite = null"
    >
      <div v-if="createdInvite" class="result-wrap">
        <div class="ok-icon"><el-icon :size="26"><CircleCheckFilled /></el-icon></div>
        <div class="ok-title">{{ createdInvite.emailSent ? '邀请邮件已发送至 ' + (createdInvite.email || '') : createdInvite.roleName + '邀请链接已生成' }}</div>
        <div class="ok-sub">
          {{
            createdInvite.emailSent
              ? `对方收到邮件后点击“接受邀请并加入”即可进入邀请页，链接有效期至 ${formatTime(createdInvite.expireTime)}。`
              : `当前未配置发信邮箱，邮件未发送。请把链接发给被邀请人，对方完成注册后将自动加入企业。链接有效期至 ${formatTime(createdInvite.expireTime)}。`
          }}
        </div>
        <div v-if="createdInvite.emailSent" class="field-tip center-tip">如果对方未收到邮件，可在“邀请记录”中点击“重发邮件”。</div>
        <div class="link-box">
          <div class="mono link-text">{{ inviteUrl(createdInvite.inviteCode) }}</div>
          <el-button type="primary" plain size="small" @click="copyInviteUrl(createdInvite.inviteCode)">复制链接</el-button>
        </div>
      </div>
      <template #footer>
        <el-button @click="inviteResultVisible = false">完成</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createMemberInvite,
  listMemberInvites,
  listMemberRoles,
  listMembers,
  resendMemberInvite,
  revokeMemberInvite,
  type CreateInviteResult,
  type InviteItem,
  type MemberItem,
  type MemberRoleOption,
} from '../../api/member'

const activeTab = ref('members')
const members = ref<MemberItem[]>([])
const invites = ref<InviteItem[]>([])
const roleOptions = ref<MemberRoleOption[]>([])
const loadingMembers = ref(false)
const loadingInvites = ref(false)
const saving = ref(false)
const resendingCode = ref('')
const inviteVisible = ref(false)
const inviteResultVisible = ref(false)
const createdInvite = ref<CreateInviteResult | null>(null)

const inviteForm = reactive({
  phone: '',
  email: '',
  roleCode: 'AGENT',
  expireDays: 7 as number,
})

const invitePendingCount = computed(() => invites.value.filter((i) => i.status === 1).length)

onMounted(async () => {
  await Promise.all([loadMembers(), loadInvites(), loadRoles()])
})

async function loadRoles() {
  try {
    roleOptions.value = await listMemberRoles()
    if (roleOptions.value.length && !inviteForm.roleCode) {
      inviteForm.roleCode = roleOptions.value[0].code
    }
  } catch {
    roleOptions.value = []
  }
}

async function loadMembers() {
  loadingMembers.value = true
  try {
    members.value = await listMembers()
  } finally {
    loadingMembers.value = false
  }
}

async function loadInvites() {
  loadingInvites.value = true
  try {
    invites.value = await listMemberInvites()
  } finally {
    loadingInvites.value = false
  }
}

function openInviteDialog() {
  inviteForm.phone = ''
  inviteForm.email = ''
  inviteForm.expireDays = 7
  if (roleOptions.value.length) {
    inviteForm.roleCode = roleOptions.value[0].code
  }
  inviteVisible.value = true
}

async function submitInvite() {
  if (!inviteForm.email.trim()) {
    ElMessage.warning('请填写受邀邮箱')
    return
  }
  if (inviteForm.phone.trim() && !/^1[3-9]\d{9}$/.test(inviteForm.phone.trim())) {
    ElMessage.warning('手机号格式不正确')
    return
  }
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(inviteForm.email.trim())) {
    ElMessage.warning('邮箱格式不正确')
    return
  }
  saving.value = true
  try {
    createdInvite.value = await createMemberInvite({
      phone: inviteForm.phone.trim() || undefined,
      email: inviteForm.email.trim() || undefined,
      roleCode: inviteForm.roleCode,
      expireDays: inviteForm.expireDays,
    })
    inviteVisible.value = false
    inviteResultVisible.value = true
    await loadInvites()
    ElMessage.success(createdInvite.value?.emailSent ? '邀请邮件已发送' : '邀请链接已生成')
  } finally {
    saving.value = false
  }
}

async function resend(row: InviteItem) {
  resendingCode.value = row.inviteCode
  try {
    const sent = await resendMemberInvite(row.inviteCode)
    if (sent) {
      ElMessage.success(`邀请邮件已重新发送至 ${row.email || '受邀邮箱'}，请提醒对方查收`)
    } else {
      ElMessage.warning('邮件服务未启用，请先在“邀请记录”中复制链接发送给成员')
    }
  } catch {
    // 错误提示已由请求层统一处理
  } finally {
    resendingCode.value = ''
  }
}

function inviteUrl(code: string) {
  return `${window.location.origin}/invite/accept/${code}`
}

async function copyInviteUrl(code: string) {
  const text = inviteUrl(code)
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('邀请链接已复制')
  } catch {
    ElMessage.warning('请手动复制链接')
  }
}

async function revoke(row: InviteItem) {
  await ElMessageBox.confirm(
    `撤销后链接将立即失效，「${row.email || row.phone || '该成员'}」将无法再加入，确认撤销吗？`,
    '撤销邀请',
    { type: 'warning', confirmButtonText: '确认撤销' },
  )
  await revokeMemberInvite(row.inviteCode)
  ElMessage.success('邀请已撤销')
  await loadInvites()
}

function roleTagType(code?: string) {
  if (code === 'SUPERVISOR') return 'warning'
  if (code === 'QUALITY' || code === 'AI_OPERATOR') return 'success'
  if (code === 'SENIOR_AGENT') return 'primary'
  return 'info'
}

function inviteTagType(status: number) {
  if (status === 1) return 'primary'
  if (status === 2) return 'success'
  return 'info'
}

function formatTime(time?: string) {
  return time ? time.replace('T', ' ').slice(0, 19) : '—'
}
</script>
<style scoped>
.settings-page { width: 100%; }
.page-title-row { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 16px; }
.page-title { margin: 0; font-size: 20px; color: #0f172a; }
.page-sub { margin: 6px 0 0; font-size: 13px; color: #94a3b8; }
.btn-icon { margin-right: 4px; }

.stat-row { display: grid; grid-template-columns: repeat(3, minmax(160px, 240px)); gap: 14px; margin-bottom: 14px; }
.stat-card { background: #fff; border: 1px solid #e6e8ef; border-radius: 14px; padding: 16px 18px; }
.stat-value { font-size: 26px; font-weight: 800; color: #1d4ed8; line-height: 1.2; }
.stat-label { margin-top: 4px; font-size: 12px; color: #94a3b8; }

.main-card { width: 100%; }
.member-cell { display: flex; align-items: center; gap: 10px; }
.member-avatar { width: 36px; height: 36px; border-radius: 10px; color: #fff; display: flex; align-items: center; justify-content: center; font-weight: 700; flex-shrink: 0; background: linear-gradient(135deg, #1d4ed8, #3b82f6); box-shadow: 0 6px 14px rgba(29, 78, 216, .2); }
.member-name { display: flex; align-items: center; gap: 6px; font-weight: 600; color: #1e293b; }
.member-sub { color: #94a3b8; font-size: 12px; margin-top: 2px; }
.contact-line { color: #475569; font-size: 13px; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.full-width { width: 100%; }

.invite-tip { display: flex; align-items: center; gap: 8px; color: #475569; font-size: 13px; background: #f8fafc; border: 1px solid #eef2f7; border-radius: 10px; padding: 10px 14px; margin-bottom: 14px; }
.field-tip { font-size: 12px; color: #64748b; background: #f0f6ff; border: 1px solid #dbeafe; border-radius: 8px; padding: 8px 12px; margin-bottom: 14px; }

.result-wrap { text-align: center; padding: 4px 0 10px; }
.ok-icon { color: #16a34a; font-size: 36px; }
.ok-title { margin-top: 10px; font-size: 18px; font-weight: 700; color: #0f172a; }
.ok-sub { margin: 8px auto 16px; max-width: 460px; font-size: 13px; color: #64748b; line-height: 1.7; }
.link-box { display: flex; align-items: center; gap: 10px; background: #f8fafc; border: 1px solid #e6e8ef; border-radius: 10px; padding: 10px 12px; }
.link-text { flex: 1; text-align: left; color: #1e40af; font-size: 13px; word-break: break-all; }
.center-tip { text-align: center; margin: -4px 0 12px; }
</style>
