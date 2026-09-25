<template>
  <div class="auth-page">
    <el-card class="auth-card" :body-style="{ padding: '34px 38px 30px' }">
      <!-- 品牌 -->
      <div class="auth-brand">
        <img class="auth-logo" src="/yunti-mark.svg" alt="云梯" />
        <div>
          <div class="auth-title">云梯智能客服管理平台</div>
          <div class="auth-subtitle">YUNTI CUSTOMER SERVICE</div>
        </div>
      </div>
      <div class="auth-head">
        <div class="auth-head-title">登录</div>
        <div class="auth-head-sub">登录管理后台，继续为客户提供优质服务</div>
      </div>
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        size="large"
        @keyup.enter="handleLogin"
      >
        <el-form-item label="账号" prop="account">
          <el-input
            v-model.trim="form.account"
            placeholder="手机号 / 邮箱 / 用户编号"
            :prefix-icon="User"
            clearable
          />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="请输入密码"
            :prefix-icon="Lock"
          />
        </el-form-item>
        <el-form-item label="验证码" prop="captchaCode">
          <div class="captcha-row">
            <el-input
              v-model.trim="form.captchaCode"
              placeholder="输入右侧字符"
              maxlength="4"
              :prefix-icon="Key"
            />
            <img
              class="captcha-img"
              :src="captchaImage"
              alt="验证码"
              title="看不清？点击刷新"
              @click="refreshCaptcha"
            />
          </div>
        </el-form-item>
        <el-button
          type="primary"
          class="block-btn"
          size="large"
          :loading="loading"
          @click="handleLogin"
        >
          登 录
        </el-button>
      </el-form>
      <div class="auth-foot">
        还没有账号？
        <router-link class="auth-link" to="/register">注册企业账号</router-link>
      </div>
    </el-card>
  </div>
</template>
<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Key, Lock, User } from '@element-plus/icons-vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { captchaApi } from '../../api/user'
import { listChannels } from '../../api/customer/channel'
import { useUserStore } from '../../stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const formRef = ref<FormInstance>()
const loading = ref(false)

const captchaId = ref('')
const captchaImage = ref('')

const form = reactive({
  account: '',
  password: '',
  captchaCode: '',
})

const rules: FormRules = {
  account: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  captchaCode: [{ required: true, message: '请输入验证码', trigger: 'blur' }],
}

onMounted(refreshCaptcha)

async function refreshCaptcha() {
  try {
    const res = await captchaApi()
    captchaId.value = res.captchaId
    captchaImage.value = res.imageBase64
  } catch {
    captchaImage.value = ''
  }
}

async function handleLogin() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    await userStore.login({
      account: form.account,
      password: form.password,
      captchaId: captchaId.value,
      captchaCode: form.captchaCode,
    })
    localStorage.removeItem('yunti_onboard_done')
    ElMessage.success('登录成功')
    if (userStore.userType === 2 && userStore.tenantCode !== 'PLATFORM') {
      try {
        const channels = await listChannels()
        if (channels.some((channel) => channel.status === 1)) {
          localStorage.setItem('yunti_onboard_done', 'true')
        }
      } catch {
        // 渠道列表异常不影响登录；进入页面后可以重试。
      }
    }
    const redirect = userStore.userType === 1 ? '/admin/reviews' : (route.query.redirect as string) || '/dashboard'
    router.push(redirect)
  } catch {
    refreshCaptcha()
    form.captchaCode = ''
  } finally {
    loading.value = false
  }
}
</script>
<style scoped>
.auth-page {
  height: 100%;
  min-height: 640px;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow-y: auto;
  background:
    radial-gradient(900px 500px at 12% -10%, rgba(59, 130, 246, 0.28), transparent 60%),
    radial-gradient(800px 500px at 105% 10%, rgba(14, 165, 233, 0.22), transparent 55%),
    #f6f7f9;
}

.auth-card {
  width: 440px;
  margin: 24px auto;
  border-radius: 16px;
  box-shadow: 0 18px 50px rgba(15, 23, 42, 0.08);
}

.auth-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 24px;
  color: #2563eb;
}

.auth-logo {
  width: 38px;
  height: 38px;
  border-radius: 10px;
  box-shadow: 0 6px 16px rgba(37, 99, 235, 0.22);
}

.auth-title {
  font-size: 19px;
  font-weight: 700;
  color: #0f172a;
}

.auth-subtitle {
  font-size: 11px;
  color: #94a3b8;
  letter-spacing: 1.5px;
  margin-top: 2px;
}

.auth-head {
  margin-bottom: 18px;
}

.auth-head-title {
  font-size: 22px;
  font-weight: 700;
  color: #0f172a;
}

.auth-head-sub {
  font-size: 13px;
  color: #94a3b8;
  margin-top: 6px;
}

.captcha-row {
  display: flex;
  gap: 10px;
  width: 100%;
}

.captcha-img {
  width: 118px;
  height: 40px;
  flex-shrink: 0;
  border-radius: 8px;
  border: 1px solid #d8dee6;
  cursor: pointer;
  object-fit: cover;
  background: #f1f5f9;
}

.block-btn {
  width: 100%;
}

.auth-foot {
  text-align: center;
  margin-top: 18px;
  font-size: 13px;
  color: #94a3b8;
}

.auth-link {
  color: #2563eb;
  font-weight: 600;
}
</style>
