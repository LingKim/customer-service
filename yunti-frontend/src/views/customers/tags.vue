<template>
  <div class="tag-page">
    <div class="page-title-row">
      <div>
        <h2 class="page-title">标签体系</h2>
        <p class="page-sub">
          标签先定义、再打标：分组 / 颜色 / 手工或规则 / 排序都在这里维护，客户 360 里打的就是这套标签
        </p>
        <p v-if="access" class="role-line">
          当前角色：<b>{{ access.roleName }}</b>
          <span v-if="access.hint">· {{ access.hint }}</span>
        </p>
      </div>
      <div class="head-actions">
        <el-button @click="router.push('/modules/customers')">
          <el-icon class="btn-icon"><Back /></el-icon>
          客户 360
        </el-button>
        <el-button :loading="loading" @click="load">
          <el-icon class="btn-icon"><Refresh /></el-icon>
          刷新
        </el-button>
        <el-button v-if="access?.canManageTag" :loading="recalculating" @click="recalculate">
          <el-icon class="btn-icon"><MagicStick /></el-icon>
          重算全部客户
        </el-button>
        <el-button v-if="access?.canManageTag" type="primary" @click="openCreate">
          <el-icon class="btn-icon"><Plus /></el-icon>
          新建标签
        </el-button>
      </div>
    </div>
    <div class="stat-row">
      <div class="stat-card">
        <div class="stat-icon blue"><el-icon :size="18"><PriceTag /></el-icon></div>
        <div><div class="stat-value">{{ rows.length }}</div><div class="stat-label">标签总数</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon green"><el-icon :size="18"><Select /></el-icon></div>
        <div><div class="stat-value">{{ enabledCount }}</div><div class="stat-label">启用中</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon purple"><el-icon :size="18"><MagicStick /></el-icon></div>
        <div><div class="stat-value">{{ autoCount }}</div><div class="stat-label">规则自动</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon amber"><el-icon :size="18"><UserFilled /></el-icon></div>
        <div><div class="stat-value">{{ usedCount }}</div><div class="stat-label">已使用</div></div>
      </div>
    </div>
    <el-empty v-if="!loading && !rows.length" description="还没有标签，先建几个（例如：高价值、待回访、风险客户）" />

    <div v-for="group in groups" :key="group.name" class="group-block">
      <div class="group-head">
        <span class="group-name">{{ group.name }}</span>
        <span class="group-count">{{ group.tags.length }} 个标签</span>
      </div>
      <div class="tag-grid">
        <div v-for="tag in group.tags" :key="tag.id" class="tag-card" :class="{ disabled: !tag.enabled }">
          <div class="tc-top">
            <el-tag size="small" effect="dark" :type="tagTypeOf(tag.color)">{{ tag.tagName }}</el-tag>
            <el-tag v-if="!tag.enabled" size="small" type="info" effect="plain">已停用</el-tag>
            <span class="tc-code mono">{{ tag.tagCode }}</span>
          </div>
          <div class="tc-desc">{{ tag.description || tag.ruleHint || '—' }}</div>
          <div class="tc-meta">
            <span>{{ tag.tagTypeText }}</span>
            <span class="dot">·</span>
            <span>已打 {{ tag.customerCount }} 位客户</span>
            <span class="dot">·</span>
            <span>排序 {{ tag.sortNo }}</span>
          </div>
          <div v-if="tag.ruleText" class="tc-rule">
            <el-icon :size="12"><MagicStick /></el-icon>
            命中条件：{{ tag.ruleText }}
          </div>
          <div v-else-if="tag.tagType === 2" class="tc-rule muted">
            还没配可执行条件（引擎不会自动跑这个标签）
          </div>
          <div v-if="access?.canManageTag" class="tc-actions">
            <el-button link type="primary" @click="openEdit(tag)">编辑</el-button>
            <el-button link type="primary" @click="toggle(tag)">{{ tag.enabled ? '停用' : '启用' }}</el-button>
            <el-button link type="danger" @click="remove(tag)">删除</el-button>
          </div>
          <div v-else class="tc-actions muted">只读：标签体系由企业管理员维护</div>
        </div>
      </div>
    </div>
    <el-dialog v-model="formVisible" :title="form.id ? '编辑标签' : '新建标签'" width="560px">
      <el-form label-width="96px">
        <el-form-item label="标签名称" required>
          <el-input v-model="form.tagName" maxlength="32" show-word-limit placeholder="例如：高价值客户" />
        </el-form-item>
        <el-form-item label="标签编码">
          <el-input v-model="form.tagCode" maxlength="32" placeholder="留空自动生成，例如：VIP_HIGH" />
        </el-form-item>
        <el-form-item label="分组" required>
          <el-select v-model="form.tagGroup" class="full-width">
            <el-option v-for="item in groupNames" :key="item" :value="item" :label="item" />
          </el-select>
        </el-form-item>
        <el-form-item label="展示颜色">
          <el-select v-model="form.color" class="full-width">
            <el-option v-for="item in colors" :key="item" :value="item" :label="item" />
          </el-select>
        </el-form-item>
        <el-form-item label="标签类型">
          <el-radio-group v-model="form.tagType">
            <el-radio-button :value="1">手工打标</el-radio-button>
            <el-radio-button :value="2">规则自动</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <!-- 规则标签的四件套：指标 + 比较符 + 阈值 + 统计窗口。配了它引擎才会真的自己跑 -->
        <template v-if="form.tagType === 2">
          <el-form-item label="规则指标">
            <el-select v-model="form.ruleMetric" class="full-width" clearable placeholder="选择一个指标（选完引擎才会跑）">
              <el-option v-for="item in metrics" :key="item.value" :value="item.value" :label="item.label" />
            </el-select>
          </el-form-item>
          <el-form-item label="比较条件">
            <div class="rule-row">
              <el-select v-model="form.ruleOp" class="w-120" placeholder="比较符">
                <el-option value="GT" label="大于 >" />
                <el-option value="GTE" label="大于等于 ≥" />
                <el-option value="LT" label="小于 <" />
                <el-option value="LTE" label="小于等于 ≤" />
                <el-option value="EQ" label="等于 =" />
              </el-select>
              <el-input-number v-model="form.ruleValue" :precision="2" :step="1" class="w-160" />
              <el-input-number
                v-if="isSessionMetric"
                v-model="form.ruleWindowDays"
                :min="0"
                :max="3650"
                class="w-160"
              />
              <span v-if="isSessionMetric" class="rule-unit">天统计窗口（0 = 全周期）</span>
            </div>
          </el-form-item>
          <el-form-item label="规则预览">
            <span class="rule-preview">{{ rulePreview }}</span>
          </el-form-item>
        </template>
        <el-form-item v-if="form.tagType === 2" label="命中口径">
          <el-input
            v-model="form.ruleHint"
            maxlength="255"
            placeholder="例如：近 30 天投诉类会话 ≥ 2 次（写给人看的说明；真正的条件是上面的指标 + 比较符 + 阈值）"
          />
        </el-form-item>
        <el-form-item label="标签说明">
          <el-input v-model="form.description" maxlength="255" show-word-limit placeholder="这个标签用来干什么" />
        </el-form-item>
        <el-form-item label="排序号">
          <el-input-number v-model="form.sortNo" :min="0" :max="9999" />
        </el-form-item>
        <el-form-item label="是否启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <div class="dialog-tip">
        停用后不能再打这个标签，但已经打上的客户仍然展示——历史数据不会因为停用而消失。
      </div>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createTagDef,
  deleteTagDef,
  fetchCustomerAccess,
  fetchMetricOptions,
  fetchTagDefs,
  recalculateAllTags,
  tagTypeOf,
  toggleTagDef,
  updateTagDef,
  type CustomerAccess,
  type MetricOption,
  type TagDef,
} from '../../api/customer/customer'

const router = useRouter()

const loading = ref(false)
const saving = ref(false)
const recalculating = ref(false)
const rows = ref<TagDef[]>([])
const access = ref<CustomerAccess | null>(null)
const metrics = ref<MetricOption[]>([])

const groupNames = ['价值', '服务', '风险', '偏好', '来源', '其它']
const colors = ['blue', 'green', 'orange', 'red', 'purple', 'gray']

const formVisible = ref(false)
const form = reactive({
  id: '',
  tagCode: '',
  tagName: '',
  tagGroup: '价值',
  color: 'blue',
  tagType: 1,
  ruleHint: '',
  ruleMetric: '',
  ruleOp: 'GTE',
  ruleValue: 0,
  ruleWindowDays: 0,
  description: '',
  sortNo: 100,
  enabled: true,
})

const enabledCount = computed(() => rows.value.filter((row) => row.enabled).length)
const autoCount = computed(() => rows.value.filter((row) => row.tagType === 2).length)
const usedCount = computed(() => rows.value.filter((row) => row.customerCount > 0).length)

/** 会话类指标才有"统计窗口"（档案类指标是全周期的，窗口对它没意义） */
const SESSION_METRICS = ['REFUND_SESSIONS', 'COMPLAINT_SESSIONS', 'NIGHT_SESSIONS']
const isSessionMetric = computed(() => SESSION_METRICS.includes(form.ruleMetric))

/** 规则预览：把四件套拼成人话，配错了当场能看出来 */
const rulePreview = computed(() => {
  if (!form.ruleMetric) {
    return '选一个指标，引擎才会自动跑这个标签'
  }
  const metric = metrics.value.find((item) => item.value === form.ruleMetric)?.label ?? form.ruleMetric
  const opText: Record<string, string> = { GT: ' > ', GTE: ' ≥ ', LT: ' < ', LTE: ' ≤ ', EQ: ' = ' }
  const scope = isSessionMetric.value && form.ruleWindowDays > 0
    ? `（近 ${form.ruleWindowDays} 天）` : '（全周期）'
  return `${metric}${opText[form.ruleOp] ?? ' ? '}${form.ruleValue}${scope}`
})

/** 按分组排开：标签体系页的"分组"要能一眼看全，而不是一长条列表 */
const groups = computed(() => {
  const map = new Map<string, TagDef[]>()
  for (const row of rows.value) {
    map.set(row.tagGroup, [...(map.get(row.tagGroup) ?? []), row])
  }
  return [...map.entries()].map(([name, tags]) => ({ name, tags }))
})

onMounted(() => {
  void load()
})

async function load() {
  loading.value = true
  try {
    const [defs, currentAccess, metricOptions] = await Promise.all([
      fetchTagDefs(),
      fetchCustomerAccess(),
      fetchMetricOptions(),
    ])
    rows.value = defs
    access.value = currentAccess
    metrics.value = metricOptions
  } finally {
    loading.value = false
  }
}

/** 全量重算：改了阈值之后必须按一次，存量客户才会回到新口径 */
async function recalculate() {
  recalculating.value = true
  try {
    const result = await recalculateAllTags()
    ElMessage.success(`已重算 ${result.scanned} 位客户：新打标 ${result.tagged} 次、摘标 ${result.untagged} 次`)
    await load()
  } finally {
    recalculating.value = false
  }
}

function openCreate() {
  form.id = ''
  form.tagCode = ''
  form.tagName = ''
  form.tagGroup = '价值'
  form.color = 'blue'
  form.tagType = 1
  form.ruleHint = ''
  form.ruleMetric = ''
  form.ruleOp = 'GTE'
  form.ruleValue = 0
  form.ruleWindowDays = 0
  form.description = ''
  form.sortNo = 100
  form.enabled = true
  formVisible.value = true
}

function openEdit(tag: TagDef) {
  form.id = tag.id
  form.tagCode = tag.tagCode
  form.tagName = tag.tagName
  form.tagGroup = tag.tagGroup
  form.color = tag.color
  form.tagType = tag.tagType
  form.ruleHint = tag.ruleHint ?? ''
  form.ruleMetric = tag.ruleMetric ?? ''
  form.ruleOp = tag.ruleOp ?? 'GTE'
  form.ruleValue = Number(tag.ruleValue ?? 0)
  form.ruleWindowDays = tag.ruleWindowDays ?? 0
  form.description = tag.description ?? ''
  form.sortNo = tag.sortNo
  form.enabled = tag.enabled
  formVisible.value = true
}

async function submit() {
  if (!form.tagName.trim()) {
    ElMessage.warning('标签名称不能为空')
    return
  }
  saving.value = true
  try {
    const body = {
      tagCode: form.tagCode.trim() || undefined,
      tagName: form.tagName.trim(),
      tagGroup: form.tagGroup,
      color: form.color,
      tagType: form.tagType,
      ruleHint: form.ruleHint,
      ruleMetric: form.tagType === 2 && form.ruleMetric ? form.ruleMetric : undefined,
      ruleOp: form.tagType === 2 && form.ruleMetric ? form.ruleOp : undefined,
      ruleValue: form.tagType === 2 && form.ruleMetric ? form.ruleValue : undefined,
      ruleWindowDays: form.tagType === 2 && form.ruleMetric ? form.ruleWindowDays : undefined,
      description: form.description,
      sortNo: form.sortNo,
      enabled: form.enabled,
    }
    if (form.id) {
      await updateTagDef(form.id, body)
      ElMessage.success('标签已更新')
    } else {
      await createTagDef(body)
      ElMessage.success('标签已创建')
    }
    formVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function toggle(tag: TagDef) {
  await toggleTagDef(tag.id, !tag.enabled)
  ElMessage.success(tag.enabled ? `已停用「${tag.tagName}」` : `已启用「${tag.tagName}」`)
  await load()
}

async function remove(tag: TagDef) {
  if (tag.customerCount > 0) {
    ElMessage.warning(`还有 ${tag.customerCount} 位客户打着「${tag.tagName}」，先摘掉标签或改成停用`)
    return
  }
  await ElMessageBox.confirm(
    `确定删除标签「${tag.tagName}」吗？删除后不能再给客户打这个标签。`,
    '删除标签',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
  )
  await deleteTagDef(tag.id)
  ElMessage.success('标签已删除')
  await load()
}
</script>
<style scoped>
.tag-page { width: 100%; }
.page-title-row { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 16px; gap: 12px; }
.page-title { margin: 0; font-size: 20px; color: #0f172a; }
.page-sub { margin: 6px 0 0; font-size: 13px; color: #94a3b8; }
.role-line { margin: 6px 0 0; font-size: 12px; color: #94a3b8; }
.role-line b { color: #2563eb; }
.head-actions { display: flex; align-items: center; gap: 12px; }
.btn-icon { margin-right: 4px; }
.stat-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 16px; }
.stat-card { display: flex; align-items: center; gap: 12px; background: #fff; border: 1px solid #e6e8ef; border-radius: 14px; padding: 14px 16px; }
.stat-icon { width: 38px; height: 38px; border-radius: 10px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.stat-icon.blue { background: #eef4ff; color: #2563eb; }
.stat-icon.amber { background: #fff7ed; color: #d97706; }
.stat-icon.green { background: #ecfdf5; color: #059669; }
.stat-icon.purple { background: #f5f3ff; color: #7c3aed; }
.stat-value { font-size: 22px; font-weight: 800; color: #0f172a; line-height: 1.2; }
.stat-label { margin-top: 3px; font-size: 12px; color: #94a3b8; }
.group-block { margin-bottom: 20px; }
.group-head { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.group-name { font-size: 15px; font-weight: 700; color: #0f172a; }
.group-count { font-size: 12px; color: #94a3b8; }
.tag-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px; }
.tag-card { background: #fff; border: 1px solid #e6e8ef; border-radius: 12px; padding: 14px 16px; }
.tag-card.disabled { background: #fafafa; }
.tc-top { display: flex; align-items: center; gap: 8px; }
.tc-code { color: #94a3b8; margin-left: auto; }
.tc-desc { margin-top: 10px; font-size: 13px; color: #475569; line-height: 1.7; min-height: 22px; }
.tc-meta { margin-top: 8px; font-size: 12px; color: #94a3b8; }
.tc-meta .dot { margin: 0 4px; }
.tc-actions { margin-top: 10px; display: flex; align-items: center; gap: 4px; }
.tc-actions.muted { font-size: 12px; }
.tc-rule { margin-top: 8px; display: flex; align-items: center; gap: 4px; padding: 6px 8px; border-radius: 6px; background: #f5f3ff; color: #6d28d9; font-size: 12px; }
.rule-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.rule-unit { font-size: 12px; color: #94a3b8; }
.rule-preview { font-size: 13px; color: #6d28d9; }
.w-120 { width: 120px; }
.w-160 { width: 160px; }
.muted { color: #94a3b8; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
.full-width { width: 100%; }
.dialog-tip { margin-top: 4px; padding: 9px 12px; border-radius: 8px; background: #f8fafc; border: 1px solid #eef2f7; color: #64748b; font-size: 12px; line-height: 1.7; }
</style>
