<template>
  <el-card v-if="visible" class="guide-card" shadow="never">
    <div class="guide-content">
      <div>
        <strong>完成企业开通</strong>
        <p>{{ stageText }}</p>
      </div>
      <div class="guide-actions">
        <el-button type="primary" plain @click="router.push('/guide')">查看开通进度</el-button>
        <el-button text aria-label="关闭引导" @click="closeGuide">关闭</el-button>
      </div>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getEnterpriseGuide } from '../api/enterprise'
import { fetchMemberAccess } from '../api/member'
import { useUserStore } from '../stores/user'
import { getTenantCode } from '../utils/auth'

const router = useRouter()
const userStore = useUserStore()
const hideKey = () => `yunti_onboard_guide_hidden_${getTenantCode() || 'default'}`
const hidden = ref(localStorage.getItem(hideKey()) === 'true'
  || localStorage.getItem('yunti_onboard_guide_hidden') === 'true')
const allowed = ref(false)
const stage = ref('')
const visible = computed(() => !hidden.value && allowed.value && stage.value !== 'APPROVED')
const stageText = computed(() => stage.value === 'PENDING_REVIEW'
  ? '企业资料正在审核中，审核通过后即可使用完整功能。'
  : '完善企业资料并提交审核，开通在线客服。')

function closeGuide() {
  hidden.value = true
  localStorage.setItem(hideKey(), 'true')
}

onMounted(async () => {
  if (userStore.userType !== 2) return
  try {
    const access = await fetchMemberAccess()
    allowed.value = access.canManage
    if (!allowed.value) return
    stage.value = (await getEnterpriseGuide()).stage
  } catch {
    // 无法获取状态时不显示引导卡片。
  }
})
</script>

<style scoped>
.guide-card { margin-bottom: 16px; }
.guide-content { display: flex; justify-content: space-between; align-items: center; gap: 16px; }
.guide-content p { margin: 6px 0 0; color: #64748b; }
.guide-actions { display: flex; gap: 8px; flex-shrink: 0; }
</style>
