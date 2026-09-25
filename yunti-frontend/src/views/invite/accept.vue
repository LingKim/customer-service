<template>
  <div class="invite-page">
    <div class="invite-top">
      <div class="brand">
        <img class="brand-logo" src="/yunti-mark.svg" alt="云梯" />
        <span class="brand-name">云梯智能客服</span>
      </div>
    </div>
    <div class="invite-center">
      <div class="invite-card">
        <template v-if="loading">
          <div class="card-loading">
            <el-icon class="is-loading" :size="28"><Loading /></el-icon>
            <div class="loading-text">正在读取邀请信息…</div>
          </div>
        </template>
        <template v-else-if="invalid">
          <div class="status-icon invalid"><el-icon :size="30"><CircleCloseFilled /></el-icon></div>
          <div class="status-title">邀请链接不可用</div>
          <div class="status-sub">该邀请可能已被撤销、已过期，或链接地址有误。请联系企业管理员重新发送邀请。</div>
          <el-button type="primary" class="full-btn" @click="router.push('/login')">前往登录</el-button>
        </template>
        <template v-else-if="!accepted">
          <div class="card-head">
            <div class="head-badge">成员邀请</div>
            <div class="head-title">加入客服团队</div>
            <div class="head-sub">
              {{ preview?.inviterName ? `${preview.inviterName} 邀请你加入企业客服团队` : '你收到了企业客服团队邀请' }}
            </div>
            <div class="role-tag">
              <el-icon :size="14"><Avatar /></el-icon>
              受邀角色：{{ preview?.roleName || '客服专员' }}
            </div>
          </div>
          <el-form label-position="top" class="accept-form">
            <el-form-item label="姓名" required>
              <el-input v-model="form.name" maxlength="64" placeholder="请输入你的真实姓名" />
            </el-form-item>
            <div class="grid-2">
              <el-form-item label="手机号" :required="hasPhone">
                <el-input v-model="form.phone" maxlength="20" placeholder="用于登录与身份校验" />
              </el-form-item>
              <el-form-item label="邮箱" :required="hasEmail">
                <el-input v-model="form.email" maxlength="128" placeholder="用于登录与接收通知" />
              </el-form-item>
            </div>
            <div v-if="hasPhone || hasEmail" class="match-tip">
              <el-icon :size="14"><InfoFilled /></el-icon>
              <span>请填写管理员邀请时使用的{{ hasPhone ? '手机号' : '' }}{{ hasPhone && hasEmail ? '与' : '' }}{{ hasEmail ? '邮箱' : '' }}，填写不一致将无法完成加入。</span>
            </div>
            <el-form-item label="设置登录密码" required>
              <el-input v-model="form.password" type="password" show-password maxlength="64" placeholder="不少于 8 位，需同时包含字母和数字" />
            </el-form-item>
            <el-form-item label="确认密码" required>
              <el-input v-model="form.confirm" type="password" show-password maxlength="64" placeholder="再次输入密码" />
            </el-form-item>
          </el-form>
          <el-button type="primary" class="full-btn submit-btn" :loading="submitting" @click="submit">
            接受邀请并加入
          </el-button>
          <div class="expire-line">
            <el-icon :size="13"><Timer /></el-icon>
            <span>邀请有效期至 {{ formatTime(preview?.expireTime) }}</span>
          </div>
        </template>
        <template v-else>
          <div class="status-icon success"><el-icon :size="30"><CircleCheckFilled /></el-icon></div>
          <div class="status-title">加入成功 🎉</div>
          <div class="success-card">
            <div class="success-row"><span>登录账号</span><b class="mono">{{ result?.email || result?.phone || result?.userNo }}</b></div>
            <div class="success-row"><span>成员编号</span><b class="mono">{{ result?.userNo }}</b></div>
            <div class="success-row"><span>角色</span><b>{{ result?.roleName }}</b></div>
            <div class="success-row"><span>所属企业</span><b>{{ result?.tenantCode }}</b></div>
          </div>
          <div class="status-sub">账号已创建，请使用上方手机号 / 邮箱和你设置的密码登录客服工作台。</div>
          <el-button type="primary" class="full-btn" @click="goLogin">前往登录</el-button>
        </template>
      </div>
    </div>
    <div class="invite-foot">© 2026 云梯智能客服 · 多租户 SaaS 客服平台</div>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { acceptMemberInvite, fetchInvitePreview, type AcceptResult, type InvitePreview } from '../../api/member'

const route = useRoute()
const router = useRouter()

const code = String(route.params.code || '')
const loading = ref(true)
const invalid = ref(false)
const accepted = ref(false)
const submitting = ref(false)
const preview = ref<InvitePreview | null>(null)
const result = ref<AcceptResult | null>(null)

const form = reactive({
  name: '',
  phone: '',
  email: '',
  password: '',
  confirm: '',
})

const hasPhone = computed(() => !!preview.value?.phoneMasked)
const hasEmail = computed(() => !!preview.value?.emailMasked)

onMounted(async () => {
  try {
    preview.value = await fetchInvitePreview(code)
  } catch {
    invalid.value = true
  } finally {
    loading.value = false
  }
})

async function submit() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写姓名')
    return
  }
  if (hasPhone.value && !form.phone.trim()) {
    ElMessage.warning('请填写邀请时使用的手机号')
    return
  }
  if (hasEmail.value && !form.email.trim()) {
    ElMessage.warning('请填写邀请时使用的邮箱')
    return
  }
  if (!hasPhone.value && !hasEmail.value && !form.phone.trim() && !form.email.trim()) {
    ElMessage.warning('请填写手机号或邮箱')
    return
  }
  if (form.phone.trim() && !/^1[3-9]\d{9}$/.test(form.phone.trim())) {
    ElMessage.warning('手机号格式不正确')
    return
  }
  if (form.email.trim() && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email.trim())) {
    ElMessage.warning('邮箱格式不正确')
    return
  }
  if (!/^(?=.*[A-Za-z])(?=.*\d).{8,64}$/.test(form.password)) {
    ElMessage.warning('密码需不少于 8 位，且同时包含字母和数字')
    return
  }
  if (form.password !== form.confirm) {
    ElMessage.warning('两次输入的密码不一致')
    return
  }
  submitting.value = true
  try {
    result.value = await acceptMemberInvite(code, {
      name: form.name.trim(),
      password: form.password,
      phone: form.phone.trim() || undefined,
      email: form.email.trim() || undefined,
    })
    accepted.value = true
  } finally {
    submitting.value = false
  }
}

function goLogin() {
  router.push('/login')
}

function formatTime(time?: string) {
  return time ? time.replace('T', ' ').slice(0, 19) : '—'
}
</script>
<style scoped>
.invite-page { min-height: 100vh; display: flex; flex-direction: column; background: linear-gradient(180deg, #f5f8ff 0%, #eef4ff 42%, #f8fbff 100%); }
.invite-top { padding: 24px 36px; }
.brand { display: flex; align-items: center; gap: 10px; }
.brand-logo { width: 34px; height: 34px; }
.brand-name { font-size: 18px; font-weight: 800; color: #0b1220; letter-spacing: .2px; }
.invite-center { flex: 1; display: flex; align-items: flex-start; justify-content: center; padding: 18px 16px 30px; }
.invite-card { width: 460px; max-width: 100%; background: #fff; border-radius: 18px; box-shadow: 0 20px 60px rgba(30, 64, 175, .12); border: 1px solid #e3ebfa; padding: 30px 34px 24px; }
.card-head { text-align: center; margin-bottom: 22px; }
.head-badge { display: inline-block; color: #2563eb; background: #eff6ff; border: 1px solid #dbeafe; border-radius: 999px; font-size: 12px; padding: 4px 12px; }
.head-title { font-size: 22px; font-weight: 800; color: #0f172a; margin-top: 12px; }
.head-sub { margin-top: 6px; color: #64748b; font-size: 13px; }
.role-tag { display: inline-flex; align-items: center; gap: 6px; margin-top: 14px; color: #1d4ed8; background: #eef4ff; border: 1px solid #dbeafe; border-radius: 8px; font-size: 13px; font-weight: 600; padding: 6px 12px; }
.accept-form { text-align: left; }
.grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.match-tip { display: flex; align-items: flex-start; gap: 6px; background: #fffbeb; border: 1px solid #fde68a; border-radius: 8px; color: #92400e; font-size: 12px; padding: 8px 10px; margin: -4px 0 16px; line-height: 1.6; }
.full-btn { width: 100%; }
.submit-btn { height: 42px; margin-top: 4px; }
.expire-line { display: flex; align-items: center; justify-content: center; gap: 6px; margin-top: 14px; color: #94a3b8; font-size: 12px; }
.status-icon { width: 64px; height: 64px; border-radius: 50%; display: flex; align-items: center; justify-content: center; margin: 14px auto 16px; }
.status-icon.success { color: #16a34a; background: #f0fdf4; border: 1px solid #bbf7d0; }
.status-icon.invalid { color: #dc2626; background: #fef2f2; border: 1px solid #fecaca; }
.status-title { font-size: 20px; font-weight: 800; color: #0f172a; text-align: center; }
.status-sub { margin: 10px auto 20px; max-width: 360px; color: #64748b; font-size: 13px; line-height: 1.8; text-align: center; }
.success-card { margin: 18px 0; background: #f8fafc; border: 1px solid #e6e8ef; border-radius: 12px; padding: 6px 16px; }
.success-row { display: flex; justify-content: space-between; padding: 10px 0; color: #475569; font-size: 13px; }
.success-row + .success-row { border-top: 1px dashed #e2e8f0; }
.success-row b { color: #0f172a; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.card-loading { text-align: center; padding: 50px 0; color: #2563eb; }
.loading-text { margin-top: 12px; color: #64748b; font-size: 13px; }
.invite-foot { text-align: center; padding: 16px; color: #94a3b8; font-size: 12px; }
</style>
