<template>
  <el-dialog v-model="visible" title="知识助手（AI 查资料后回答）" width="820px">
    <div class="ka-tip">
      问一句客户可能会问的话，AI 会先去知识库里查资料，再照着资料回答，并在每句话后标出依据来自哪一块。
      回答里没有出处的内容，说明资料里确实没有——那就该补文档，而不是让 AI 编。
    </div>
    <div class="ka-bar">
      <el-input
        v-model="question"
        placeholder="例如：退货多久能到账？"
        maxlength="500"
        @keyup.enter="ask"
      />
      <el-button type="primary" :loading="asking" @click="ask">提问</el-button>
    </div>
    <div v-if="result" class="ka-result">
      <div class="ka-meta">
        <el-tag size="small" effect="light" type="success">编排：{{ result.engine }}</el-tag>
        <el-tag size="small" effect="light" :type="result.enough ? 'success' : 'warning'">
          {{ result.enough ? '资料充分' : '资料偏少，回答较保守' }}
        </el-tag>
        <el-tag size="small" effect="light">
          {{ result.mode === 'vector' ? '向量语义检索' : '关键词匹配' }}
        </el-tag>
        <el-button link type="primary" size="small" @click="showSteps = !showSteps">
          {{ showSteps ? '收起编排过程' : '查看编排过程' }}
        </el-button>
        <span class="ka-spacer" />
        <el-button v-if="insertable" size="small" type="primary" plain @click="emitInsert(result.answer)">
          把回答填入回复
        </el-button>
      </div>
      <div v-if="showSteps" class="ka-steps">
        <div v-for="(step, i) in result.steps" :key="i" class="ka-step">
          <span class="ka-step-no">{{ i + 1 }}</span>
          <span class="ka-step-node">{{ step.node }}</span>
          <span class="ka-step-detail">{{ step.detail }}</span>
        </div>
      </div>
      <div class="ka-answer" v-html="renderAnswer(result.answer)" />

      <div v-if="result.citations.length" class="ka-citations">
        <div class="ka-cit-title">引用来源（{{ result.citations.length }} 条）</div>
        <div v-for="cit in result.citations" :key="cit.index" class="ka-cit">
          <span class="ka-cit-no">[{{ cit.index }}]</span>
          <div class="ka-cit-main">
            <div class="ka-cit-head">
              <span class="ka-cit-doc">{{ cit.docTitle }}</span>
              <span class="ka-cit-sub">第 {{ cit.chunkNo }} 块 · 分值 {{ (cit.score ?? 0).toFixed(3) }}</span>
              <el-button
                v-if="insertable"
                link
                type="primary"
                size="small"
                @click="emitInsert(cit.content)"
              >
                引用这段
              </el-button>
            </div>
            <div class="ka-cit-content">{{ cit.content }}</div>
          </div>
        </div>
      </div>
    </div>
  </el-dialog>
</template>
<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { askKb, type KbAskResult } from '../api/customer/kb'

const props = withDefaults(defineProps<{
  /** 是否显示（配合 v-model:visible 用） */
  visible: boolean
  /** 打开时预填的问题：坐席场景下传客户最后一句，省得再打一遍 */
  defaultQuestion?: string
  /** 是否提供"填入回复/引用这段"（坐席工作台用；纯测试场景不需要） */
  insertable?: boolean
}>(), { defaultQuestion: '', insertable: false })

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  /** 把文本交给调用方（坐席场景：填进回复框） */
  (e: 'insert', text: string): void
}>()

const visible = ref(props.visible)
const question = ref('')
const asking = ref(false)
const result = ref<KbAskResult | null>(null)
const showSteps = ref(false)

watch(() => props.visible, (value) => {
  visible.value = value
  if (value) {
    // 每次打开都清空上一次的结果，并按需预填问题
    result.value = null
    showSteps.value = false
    question.value = props.defaultQuestion || ''
  }
})

watch(visible, (value) => emit('update:visible', value))

async function ask() {
  if (!question.value.trim()) {
    ElMessage.warning('请输入要问的问题')
    return
  }
  asking.value = true
  try {
    result.value = await askKb({ question: question.value.trim(), topK: 5 })
  } catch {
    // 请求层已提示
  } finally {
    asking.value = false
  }
}

function emitInsert(text: string) {
  emit('insert', text || '')
  ElMessage.success('已填入回复框，确认后再发送')
}

/** 把回答里的 [1] [2] 渲染成小标签，和下面的引用来源一一对应 */
function renderAnswer(text: string) {
  const escaped = (text || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
  return escaped.replace(/\[(\d{1,2})\]/g, '<span class="ka-ref">[$1]</span>')
}
</script>
<style scoped>
.ka-tip { font-size: 13px; color: #64748b; line-height: 1.8; margin-bottom: 10px; }
.ka-bar { display: flex; gap: 8px; }
.ka-result { margin-top: 12px; }
.ka-meta { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.ka-spacer { flex: 1; }

.ka-steps { margin: 10px 0; padding: 10px 12px; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; }
.ka-step { display: flex; align-items: baseline; gap: 8px; font-size: 12px; line-height: 1.9; }
.ka-step-no { flex: none; width: 18px; height: 18px; border-radius: 50%; background: #e2e8f0; color: #475569; text-align: center; line-height: 18px; font-size: 11px; }
.ka-step-node { flex: none; font-weight: 600; color: #334155; min-width: 62px; }
.ka-step-detail { color: #64748b; }

.ka-answer { margin-top: 10px; padding: 12px 14px; background: #f6faff; border: 1px solid #dbeafe; border-radius: 10px; font-size: 14px; line-height: 1.9; color: #0f172a; white-space: pre-wrap; }
.ka-ref { display: inline-block; margin: 0 1px; padding: 0 4px; border-radius: 4px; background: #dbeafe; color: #1d4ed8; font-size: 12px; font-weight: 600; }

.ka-citations { margin-top: 12px; }
.ka-cit-title { font-size: 13px; font-weight: 600; color: #334155; margin-bottom: 8px; }
.ka-cit { display: flex; gap: 8px; padding: 8px 10px; border: 1px solid #e6ebf2; border-radius: 8px; margin-bottom: 8px; }
.ka-cit-no { flex: none; color: #1d4ed8; font-weight: 700; font-size: 13px; }
.ka-cit-main { min-width: 0; }
.ka-cit-head { display: flex; align-items: baseline; gap: 8px; margin-bottom: 3px; }
.ka-cit-doc { font-weight: 600; font-size: 13px; color: #0f172a; }
.ka-cit-sub { font-size: 12px; color: #94a3b8; }
.ka-cit-content { font-size: 12px; color: #475569; line-height: 1.7; }
</style>
