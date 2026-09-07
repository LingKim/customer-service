<template>
  <main class="auth-page">
    <el-card class="auth-card" :body-style="{ padding: '28px 38px 24px' }">
      <header class="auth-brand"><img class="auth-logo" src="/yunti-mark.svg" alt="云梯" /><div><div class="auth-title">云梯智能客服管理平台</div><div class="auth-subtitle">YUNTI CUSTOMER SERVICE</div></div></header>
      <section v-if="registered" class="register-success">
        <el-icon :size="44" color="#10b981"><CircleCheckFilled /></el-icon>
        <h1>注册成功 🎉</h1><p>企业账号已创建，提交审核通过后即可开通云梯客服</p>
        <ol><li><strong>1. 账号注册成功</strong><span>企业账号已创建</span></li><li><strong>2. 完善企业信息</strong><span>营业执照、注册地址、法人等专业信息</span></li><li><strong>3. 平台审核</strong><span>审核通过后自动开通，可开始接待客户</span></li></ol>
        <el-button type="primary" class="block-btn" size="large" @click="goLogin">返回登录</el-button>
      </section>
      <section v-else>
        <h1 class="auth-head-title">注册企业账号</h1><p class="auth-head-sub">注册后将进入运营审核，审核通过即可开通云梯客服</p>
        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" size="large">
          <h2>企业信息</h2>
          <el-form-item label="企业名称" prop="companyName"><el-input v-model.trim="form.companyName" placeholder="例：杭州云智网络科技有限公司" maxlength="128" /></el-form-item>
          <div class="form-grid">
            <el-form-item label="所属行业" prop="industry"><el-select v-model="form.industry" placeholder="请选择行业"><el-option v-for="item in industryOptions" :key="item" :label="item" :value="item" /></el-select></el-form-item>
            <el-form-item label="团队规模" prop="scale"><el-select v-model="form.scale" placeholder="请选择规模"><el-option v-for="item in scaleOptions" :key="item" :label="item" :value="item" /></el-select></el-form-item>
          </div>
          <h2>管理员账号</h2>
          <el-form-item label="联系人姓名" prop="contactName"><el-input v-model.trim="form.contactName" placeholder="姓名" maxlength="64" /></el-form-item>
          <div class="form-grid">
            <el-form-item label="手机号" prop="phone"><el-input v-model.trim="form.phone" placeholder="11 位手机号" maxlength="11" /></el-form-item>
            <el-form-item label="邮箱" prop="email"><el-input v-model.trim="form.email" placeholder="name@company.com" maxlength="128" /></el-form-item>
          </div>
          <el-form-item label="设置密码" prop="password"><el-input v-model="form.password" type="password" show-password placeholder="不少于 8 位，需同时包含字母与数字" :prefix-icon="Lock" /></el-form-item>
          <el-form-item label="验证码" prop="captchaCode"><div class="captcha-row"><el-input v-model.trim="form.captchaCode" placeholder="输入右侧字符" maxlength="4" :prefix-icon="Key" /><img class="captcha-img" :src="captchaImage" alt="验证码" title="看不清？点击刷新" @click="refreshCaptcha" /></div></el-form-item>
          <el-checkbox v-model="agreed" class="agreement">我已阅读并同意 <a href="/legal.html?t=terms" target="_blank" rel="noopener" @click.stop>《服务协议》</a> 与 <a href="/legal.html?t=privacy" target="_blank" rel="noopener" @click.stop>《隐私政策》</a></el-checkbox>
          <el-button type="primary" class="block-btn" size="large" :loading="loading" @click="handleRegister">提交注册申请</el-button>
        </el-form>
        <footer>已有账号？<router-link to="/login">直接登录</router-link></footer>
      </section>
    </el-card>
  </main>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { CircleCheckFilled, Key, Lock } from '@element-plus/icons-vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { captchaApi, registerApi } from '../../api/user'

const router = useRouter()
const formRef = ref<FormInstance>()
const loading = ref(false)
const registered = ref(false)
const agreed = ref(false)
const captchaId = ref('')
const captchaImage = ref('')
const form = reactive({ companyName: '', industry: '', scale: '', contactName: '', phone: '', email: '', password: '', captchaCode: '' })
const industryOptions = ['电商零售', '企业服务', '金融保险', '教育', '智能硬件', '其他']
const scaleOptions = ['10 人以内', '10~50 人', '50~200 人', '200 人以上']
const rules: FormRules = {
  companyName: [{ required: true, message: '请输入企业名称', trigger: 'blur' }], industry: [{ required: true, message: '请选择所属行业', trigger: 'change' }], scale: [{ required: true, message: '请选择团队规模', trigger: 'change' }], contactName: [{ required: true, message: '请输入联系人姓名', trigger: 'blur' }],
  phone: [{ required: true, message: '请输入手机号', trigger: 'blur' }, { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }],
  email: [{ required: true, message: '请输入邮箱', trigger: 'blur' }, { type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
  password: [{ required: true, message: '请设置密码', trigger: 'blur' }, { pattern: /^(?=.*[A-Za-z])(?=.*\d).{8,64}$/, message: '密码需不少于 8 位，且同时包含字母和数字', trigger: 'blur' }], captchaCode: [{ required: true, message: '请输入验证码', trigger: 'blur' }],
}
onMounted(refreshCaptcha)
async function refreshCaptcha() { try { const result = await captchaApi(); captchaId.value = result.captchaId; captchaImage.value = result.imageBase64 } catch { captchaImage.value = '' } }
async function handleRegister() {
  if (!agreed.value) { ElMessage.warning('请先阅读并同意《服务协议》与《隐私政策》'); return }
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try { await registerApi({ ...form, captchaId: captchaId.value }); registered.value = true } catch { await refreshCaptcha(); form.captchaCode = '' } finally { loading.value = false }
}
function goLogin() { router.push('/login') }
</script>

<style scoped>
.auth-page { min-height: 100%; display: flex; justify-content: center; padding: 24px; background: #f6f7f9; }.auth-card { width: min(500px, 100%); border-radius: 16px; box-shadow: 0 18px 50px rgb(15 23 42 / 8%); }.auth-brand { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }.auth-logo { width: 38px; height: 38px; border-radius: 10px; }.auth-title, h1 { color: #0f172a; font-weight: 700; }.auth-title { font-size: 19px; }.auth-subtitle, .auth-head-sub, footer, p, li span { color: #94a3b8; font-size: 12px; }.auth-head-title { margin: 0; font-size: 22px; }.auth-head-sub { margin: 6px 0 14px; }.auth-card h2 { font-size: 13px; border-left: 3px solid #2563eb; padding-left: 8px; }.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }.form-grid :deep(.el-select) { width: 100%; }.captcha-row { display: flex; width: 100%; gap: 10px; }.captcha-img { width: 118px; height: 40px; border: 1px solid #d8dee6; border-radius: 8px; cursor: pointer; object-fit: cover; }.agreement { margin-bottom: 12px; white-space: normal; line-height: 1.6; }.agreement a, footer a { color: #2563eb; font-weight: 600; }.block-btn { width: 100%; } footer { margin-top: 12px; text-align: center; }.register-success { text-align: center; }.register-success ol { padding: 0; text-align: left; list-style: none; }.register-success li { display: grid; gap: 3px; padding: 11px 0; border-bottom: 1px dashed #e2e8f0; } @media (max-width: 520px) { .auth-page { padding: 0; }.form-grid { grid-template-columns: 1fr; gap: 0; } }
</style>
