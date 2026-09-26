<template>
  <div class="sg-page">
    <div class="page-title-row">
      <div>
        <h2 class="page-title">技能组</h2>
        <p class="page-sub">按业务线把客服分组（售前 / 售后 / VIP），渠道绑定组后，会话只找组内在线且接得下的坐席</p>
      </div>
      <el-button type="primary" @click="openCreate">新建技能组</el-button>
    </div>
    <el-table :data="groups" v-loading="loading" stripe>
      <el-table-column prop="name" label="技能组" min-width="160">
        <template #default="{ row }">
          <span class="sg-name">{{ row.name }}</span>
          <el-tag v-if="row.isDefault" size="small" effect="light" class="sg-tag">默认</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="说明" min-width="200">
        <template #default="{ row }">{{ row.description || '—' }}</template>
      </el-table-column>
      <el-table-column label="组内坐席" min-width="240">
        <template #default="{ row }">
          <template v-if="row.members.length">
            <el-tag v-for="member in row.members" :key="member.userId" size="small" effect="plain" class="sg-chip">
              {{ member.name }}
            </el-tag>
          </template>
          <span v-else class="muted">未配置</span>
          <!-- 组里没人、却已经被渠道绑定：会话派不到人。
               （现在路由遇到"空组"会当场放宽到全部在线坐席，但配置还是应该改对） -->
          <el-tag
            v-if="!row.members.length && row.channels.length"
            size="small"
            type="warning"
            effect="light"
            class="sg-chip"
            title="这个组没有成员，却有渠道绑着它：该渠道的会话找不到组内坐席（路由会在超时后放宽到全部在线坐席）"
          >
            有渠道无成员
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="绑定渠道" min-width="200">
        <template #default="{ row }">
          <template v-if="row.channels.length">
            <el-tag v-for="channel in row.channels" :key="channel.id" size="small" effect="plain" class="sg-chip">
              {{ channel.name }}
            </el-tag>
          </template>
          <span v-else class="muted">未绑定</span>
        </template>
      </el-table-column>
      <el-table-column label="排队升级" width="130">
        <template #default="{ row }">
          {{ row.overflowAfterSeconds > 0 ? `${row.overflowAfterSeconds} 秒` : '不升级' }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openMembers(row)">配置坐席</el-button>
          <el-button link type="primary" @click="openBind(row)">绑定渠道</el-button>
          <el-button link @click="openOverflow(row)">排队升级</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div v-if="!loading && !groups.length" class="sg-empty">
      <el-empty description="还没有技能组，新建一个再把同事拉进来" />
    </div>
    <!-- 新建 -->
    <el-dialog v-model="createVisible" title="新建技能组" width="460px" :close-on-click-modal="false">
      <el-form label-position="top">
        <el-form-item label="名称">
          <el-input v-model="createForm.name" maxlength="64" placeholder="例如：售后支持" />
        </el-form-item>
        <el-form-item label="说明（选填）">
          <el-input v-model="createForm.description" maxlength="255" placeholder="这个组主要处理什么类型的咨询" />
        </el-form-item>
        <el-form-item label="排队升级（秒，0 表示不升级）">
          <el-input-number v-model="createForm.overflowAfterSeconds" :min="0" :max="3600" :step="10" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>
    <!-- 配置坐席 -->
    <el-dialog v-model="memberVisible" :title="`配置坐席 · ${current?.name || ''}`" width="520px">
      <div class="dialog-tip">勾选后保存，组内坐席才可能接到该渠道的会话。</div>
      <el-checkbox-group v-model="selectedMembers" class="member-picker">
        <el-checkbox v-for="member in colleagues" :key="member.userId" :value="String(member.userId)">
          {{ member.name }}
        </el-checkbox>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="memberVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitMembers">保存</el-button>
      </template>
    </el-dialog>
    <!-- 绑定渠道 -->
    <el-dialog v-model="bindVisible" :title="`绑定渠道 · ${current?.name || ''}`" width="520px">
      <div class="dialog-tip">一个渠道只能绑一个技能组；取消勾选表示解绑（回到默认组）。</div>
      <el-checkbox-group v-model="selectedChannels" class="member-picker">
        <el-checkbox v-for="channel in channels" :key="channel.id" :value="String(channel.id)">
          {{ channel.name }}（{{ channelTypeText[channel.channelType] || '渠道' }}）
        </el-checkbox>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="bindVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitBind">保存</el-button>
      </template>
    </el-dialog>
    <!-- 排队升级 -->
    <el-dialog v-model="overflowVisible" :title="`排队升级 · ${current?.name || ''}`" width="460px">
      <div class="dialog-tip">
        组内没人能接时，超过这个时长还没分出去，就把会话交给其它在线坐席（避免客户一直等）。
      </div>
      <el-input-number v-model="overflowSeconds" :min="0" :max="3600" :step="10" />
      <span class="muted"> 秒（0 表示不升级）</span>
      <template #footer>
        <el-button @click="overflowVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitOverflow">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { channelTypeText, listChannels, type ChannelResult } from '../../api/customer/channel'
import { listColleagues, listMembers, type ColleagueOption } from '../../api/member'
import {
  bindChannelSkillGroup,
  createSkillGroup,
  listSkillGroups,
  setSkillGroupMembers,
  updateSkillGroup,
  type SkillGroupItem,
} from '../../api/customer/skillGroup'

const loading = ref(true)
const saving = ref(false)
const groups = ref<SkillGroupItem[]>([])
const colleagues = ref<ColleagueOption[]>([])
const channels = ref<ChannelResult[]>([])
const current = ref<SkillGroupItem | null>(null)

const createVisible = ref(false)
const createForm = ref({ name: '', description: '', overflowAfterSeconds: 60 })

const memberVisible = ref(false)
const selectedMembers = ref<string[]>([])

const bindVisible = ref(false)
const selectedChannels = ref<string[]>([])

const overflowVisible = ref(false)
const overflowSeconds = ref(60)

onMounted(async () => {
  await Promise.all([load(), loadRefs()])
})

async function load() {
  loading.value = true
  try {
    groups.value = await listSkillGroups()
  } finally {
    loading.value = false
  }
}

async function loadRefs() {
  // 成员列表只有管理员能看；普通客服看不到就直接用同事列表兜底
  try {
    const members = await listMembers()
    colleagues.value = members.map((item) => ({
      // 保持字符串：Number() 会把 19 位 ID 四舍五入，导致保存进库的成员是错的
      userId: item.userId,
      name: item.name,
      userNo: item.userNo,
    }))
  } catch {
    colleagues.value = await listColleagues().catch(() => [])
  }
  channels.value = await listChannels().catch(() => [])
}

function openCreate() {
  createForm.value = { name: '', description: '', overflowAfterSeconds: 60 }
  createVisible.value = true
}

async function submitCreate() {
  if (!createForm.value.name.trim()) {
    ElMessage.warning('请填写技能组名称')
    return
  }
  saving.value = true
  try {
    await createSkillGroup({
      name: createForm.value.name.trim(),
      description: createForm.value.description.trim() || undefined,
      overflowAfterSeconds: createForm.value.overflowAfterSeconds,
    })
    ElMessage.success('技能组已创建')
    createVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

function openMembers(group: SkillGroupItem) {
  current.value = group
  selectedMembers.value = group.members.map((item) => item.userId)
  memberVisible.value = true
}

async function submitMembers() {
  if (!current.value) {
    return
  }
  saving.value = true
  try {
    await setSkillGroupMembers(current.value.id, selectedMembers.value)
    ElMessage.success('组内坐席已更新')
    memberVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

function openBind(group: SkillGroupItem) {
  current.value = group
  selectedChannels.value = group.channels.map((item) => item.id)
  bindVisible.value = true
}

async function submitBind() {
  if (!current.value) {
    return
  }
  saving.value = true
  try {
    const wanted = new Set(selectedChannels.value)
    for (const channel of channels.value) {
      const shouldBind = wanted.has(String(channel.id))
      const isBound = current.value.channels.some((item) => item.id === String(channel.id))
      if (shouldBind && !isBound) {
        await bindChannelSkillGroup(String(channel.id), current.value.id)
      } else if (!shouldBind && isBound) {
        await bindChannelSkillGroup(String(channel.id), null)
      }
    }
    ElMessage.success('渠道绑定已更新')
    bindVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

function openOverflow(group: SkillGroupItem) {
  current.value = group
  overflowSeconds.value = group.overflowAfterSeconds
  overflowVisible.value = true
}

async function submitOverflow() {
  if (!current.value) {
    return
  }
  saving.value = true
  try {
    await updateSkillGroup(current.value.id, { overflowAfterSeconds: overflowSeconds.value })
    ElMessage.success('排队升级已保存')
    overflowVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}
</script>
<style scoped>
.sg-page {
  width: 100%;
  min-width: 0;
}

.page-title-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.page-title {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  color: #0f172a;
}

.page-sub {
  margin: 6px 0 0;
  font-size: 13px;
  color: #64748b;
}

.sg-name {
  font-weight: 600;
  color: #0f172a;
}

.sg-tag {
  margin-left: 6px;
}

.sg-chip {
  margin: 2px 6px 2px 0;
}

.muted {
  color: #94a3b8;
  font-size: 13px;
}

.sg-empty {
  margin-top: 12px;
  background: #fff;
  border: 1px solid #e6ebf2;
  border-radius: 12px;
}

.dialog-tip {
  font-size: 13px;
  color: #64748b;
  margin-bottom: 12px;
}

.member-picker {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 18px;
}
</style>
