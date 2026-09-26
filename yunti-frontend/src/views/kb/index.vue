<template>
  <div class="kb-page">
    <div class="page-title-row">
      <div>
        <h2 class="page-title">企业知识库</h2>
        <p class="page-sub">
          上传文档 → 自动解析、切块、向量化 → 机器人就能查到；这里能看到每一份文档切成了什么、检索命中哪一句
        </p>
      </div>
      <div class="head-actions">
        <el-button :loading="loading" @click="loadAll">
          <el-icon class="btn-icon"><Refresh /></el-icon>
          刷新
        </el-button>
        <el-button @click="openSearch">
          <el-icon class="btn-icon"><Search /></el-icon>
          检索测试
        </el-button>
        <el-button @click="openCreate">
          <el-icon class="btn-icon"><Plus /></el-icon>
          新建文档
        </el-button>
        <el-button type="primary" @click="openUpload">
          <el-icon class="btn-icon"><UploadFilled /></el-icon>
          上传文档
        </el-button>
      </div>
    </div>
    <div class="stat-row">
      <div class="stat-card">
        <div class="stat-icon blue"><el-icon :size="18"><Document /></el-icon></div>
        <div><div class="stat-value">{{ overview?.docTotal ?? '—' }}</div><div class="stat-label">知识文档</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon green"><el-icon :size="18"><CircleCheckFilled /></el-icon></div>
        <div><div class="stat-value">{{ overview?.published ?? '—' }}</div><div class="stat-label">已发布</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon purple"><el-icon :size="18"><Grid /></el-icon></div>
        <div><div class="stat-value">{{ overview?.chunkTotal ?? '—' }}</div><div class="stat-label">知识切片</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon amber">
          <el-icon :size="18"><Connection /></el-icon>
        </div>
        <div>
          <div class="stat-value">{{ overview?.indexed ?? '—' }}</div>
          <div class="stat-label">已建索引</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon" :class="overview?.vectorReady ? 'green' : 'red'">
          <el-icon :size="18"><Cpu /></el-icon>
        </div>
        <div>
          <div class="stat-value">{{ overview?.vectorReady ? '就绪' : '不可用' }}</div>
          <div class="stat-label">向量检索（pgvector）</div>
        </div>
      </div>
    </div>
    <div class="kb-body">
      <aside class="kb-side">
        <div class="side-head">
          <span>知识分类</span>
          <el-button link type="primary" size="small" @click="openCategory">新建</el-button>
        </div>
        <div class="cat-list">
          <div class="cat-item" :class="{ active: categoryId === '' }" @click="filterCategory('')">
            <span>全部文档</span>
            <span class="cat-count">{{ overview?.docTotal ?? 0 }}</span>
          </div>
          <div
            v-for="item in categories"
            :key="item.id"
            class="cat-item"
            :class="{ active: categoryId === item.id }"
            @click="filterCategory(item.id)"
          >
            <span>{{ item.name }}</span>
            <span class="cat-count">{{ item.docCount }}</span>
          </div>
          <div v-if="!categories.length" class="cat-empty">还没有分类，先建一个吧</div>
        </div>
      </aside>
      <main class="kb-main">
        <div class="filter-row">
          <el-input
            v-model="keyword"
            class="kb-search"
            clearable
            placeholder="搜索文档编号 / 标题 / 摘要 / 文件名"
            @keyup.enter="loadDocuments"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-radio-group v-model="statusFilter" @change="loadDocuments">
            <el-radio-button :value="0">全部</el-radio-button>
            <el-radio-button :value="3">已发布</el-radio-button>
            <el-radio-button :value="1">草稿</el-radio-button>
            <el-radio-button :value="4">已下线</el-radio-button>
          </el-radio-group>
        </div>
        <el-table v-loading="loading" :data="documents" stripe>
          <el-table-column type="index" label="序号" width="70" />
          <el-table-column label="文档" min-width="260">
            <template #default="{ row }">
              <div class="doc-title">{{ row.title }}</div>
              <div class="cell-sub">
                <span class="mono">{{ row.docNo }}</span>
                <el-tag v-if="row.fileName" size="small" effect="plain" class="ml-6">{{ row.fileName }}</el-tag>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="来源" width="110">
            <template #default="{ row }">{{ row.sourceText }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="statusTag(row.status)" effect="light">{{ row.statusText }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="索引" width="150">
            <template #default="{ row }">
              <el-tooltip :content="row.indexMessage || '尚未建立索引'" placement="top">
                <el-tag size="small" :type="indexTag(row.indexStatus)" effect="light">
                  {{ row.indexStatusText }}
                </el-tag>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column label="切片" width="90">
            <template #default="{ row }">
              <el-button link type="primary" :disabled="!row.chunkCount" @click="openChunks(row)">
                {{ row.chunkCount }} 块
              </el-button>
            </template>
          </el-table-column>
          <el-table-column label="更新时间" width="165">
            <template #default="{ row }">{{ formatTime(row.updateTime) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="240" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button link type="primary" @click="reindex(row)">重新索引</el-button>
              <el-button v-if="row.status !== 3" link type="success" @click="changeStatus(row, 3)">发布</el-button>
              <el-button v-else link @click="changeStatus(row, 4)">下线</el-button>
              <el-button link type="danger" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <div class="kb-empty">
              <div class="kb-empty-title">还没有知识文档</div>
              <div class="kb-empty-sub">
                点右上角「上传文档」传一份 txt / md / docx / pdf，系统会自动解析、切块并建立向量索引；
                也可以「新建文档」直接把正文贴进来。
              </div>
            </div>
          </template>
        </el-table>
      </main>
    </div>
    <!-- 上传文档 -->
    <el-dialog v-model="uploadVisible" title="上传文档" width="560px" :close-on-click-modal="false">
      <el-upload
        drag
        class="kb-upload"
        :auto-upload="false"
        :show-file-list="false"
        accept=".txt,.md,.markdown,.csv,.json,.log,.html,.htm,.docx,.pdf"
        :on-change="onFileChange"
      >
        <el-icon class="up-icon"><UploadFilled /></el-icon>
        <div class="up-text">把文件拖到这里，或<em>点击选择</em></div>
        <div class="up-tip">支持 txt / md / csv / html / docx / pdf，单个不超过 20MB</div>
      </el-upload>
      <div v-if="uploadFile" class="up-file">
        <el-icon><Document /></el-icon>
        <span>{{ uploadFile.name }}</span>
        <span class="up-size">{{ formatSize(uploadFile.size) }}</span>
      </div>
      <el-form label-width="80px" class="mt-12">
        <el-form-item label="文档标题">
          <el-input v-model="uploadTitle" maxlength="255" placeholder="不填就用文件名" />
        </el-form-item>
        <el-form-item label="所属分类">
          <el-select v-model="uploadCategoryId" clearable placeholder="不指定" class="full-width">
            <el-option v-for="item in categories" :key="item.id" :value="item.id" :label="item.name" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="uploadVisible = false">取消</el-button>
        <el-button type="primary" :loading="uploading" :disabled="!uploadFile" @click="submitUpload">
          上传并建索引
        </el-button>
      </template>
    </el-dialog>
    <!-- 新建 / 编辑文档 -->
    <el-dialog
      v-model="formVisible"
      :title="editingId ? '编辑文档' : '新建文档'"
      width="720px"
      :close-on-click-modal="false"
    >
      <el-form label-width="80px">
        <el-form-item label="标题" required>
          <el-input v-model="form.title" maxlength="255" placeholder="例如：退款政策说明" />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="form.categoryId" clearable placeholder="不指定" class="full-width">
            <el-option v-for="item in categories" :key="item.id" :value="item.id" :label="item.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="摘要">
          <el-input v-model="form.summary" maxlength="512" placeholder="一句话说明这份文档讲什么（选填）" />
        </el-form-item>
        <el-alert
          v-if="contentSource === 'chunks'"
          class="mb-10"
          type="info"
          :closable="false"
          show-icon
          title="这段正文是从上传的文件里解析出来的"
          description="导入型文档的正文存在切片中，这里给你拼回来方便查看。只改标题/分类/摘要不会重新索引；如果改动了这段正文并保存，后续就以这段正文为准重新索引。"
        />
        <el-alert
          v-else-if="formVisible && !form.content"
          class="mb-10"
          type="warning"
          :closable="false"
          show-icon
          title="这篇文档还没有正文"
          description="可以把内容贴进来，保存后会自动切块并建立向量索引。"
        />
        <el-form-item label="正文">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="12"
            maxlength="200000"
            show-word-limit
            placeholder="粘贴正文；保存后系统会自动切块并建立向量索引"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存并索引</el-button>
      </template>
    </el-dialog>
    <!-- 新建分类 -->
    <el-dialog v-model="categoryVisible" title="新建知识分类" width="420px">
      <el-form label-width="70px">
        <el-form-item label="名称" required>
          <el-input v-model="categoryName" maxlength="64" placeholder="例如：售后政策" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="categoryVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingCategory" @click="submitCategory">创建</el-button>
      </template>
    </el-dialog>
    <!-- 切片预览 -->
    <el-drawer v-model="chunkVisible" :title="`切片预览 · ${chunkDoc?.title || ''}`" size="720px">
      <div class="chunk-tip">
        检索的最小单位就是这些切片：机器人回答问题时会先命中某几块，再把它们交给大模型。
        所以"切得合不合理"直接决定问答质量——一句话被切断、或一整篇挤成一块，都会影响召回。
      </div>
      <div v-loading="loadingChunks" class="chunk-list">
        <div v-for="item in chunks" :key="item.id" class="chunk-item">
          <div class="chunk-head">
            <span class="chunk-no">第 {{ item.chunkNo }} 块</span>
            <span class="chunk-meta">{{ item.charCount }} 字 · 约 {{ item.tokenCount }} token</span>
            <span v-if="item.vectorModel" class="chunk-model">{{ item.vectorModel }}</span>
          </div>
          <div class="chunk-content">{{ item.content }}</div>
        </div>
        <el-empty v-if="!loadingChunks && !chunks.length" description="还没有切片，先点「重新索引」" />
      </div>
    </el-drawer>
    <!-- 检索测试 -->
    <el-dialog v-model="searchVisible" title="检索测试" width="760px">
      <div class="search-tip">
        输入一句客户可能问的话，看看能不能命中正确的知识切片。命不中就说明：文档没上传、没建索引，或者切片切得不合适。
      </div>
      <div class="search-bar">
        <el-input
          v-model="searchQuery"
          placeholder="例如：退款多久能到账？"
          maxlength="500"
          @keyup.enter="doSearch"
        />
        <el-button type="primary" :loading="searching" @click="doSearch">检索</el-button>
      </div>
      <div v-if="searchResult" class="search-meta">
        共命中 {{ searchResult.results.length }} 条
        <el-tag v-if="keywordMode" size="small" type="warning" effect="light" class="ml-6">
          关键词匹配（未配向量密钥）
        </el-tag>
        <el-tag v-else-if="searchResult.results.length" size="small" type="success" effect="light" class="ml-6">
          向量语义检索
        </el-tag>
        <el-tag v-if="!searchResult.vectorReady" size="small" type="danger" effect="light" class="ml-6">
          AI 服务未就绪
        </el-tag>
        <div v-if="keywordMode" class="search-hint">
          当前没配 <code>YUNTI_AI_EMBEDDING_API_KEY</code>，只能按字面匹配（把问题拆成片段逐个找）。
          配好密钥并重新索引后，问法差几个字也能命中。
        </div>
      </div>
      <div v-loading="searching" class="hit-list">
        <div v-for="hit in searchResult?.results || []" :key="hit.chunkId" class="hit-item">
          <div class="hit-head">
            <span class="hit-doc">{{ hit.docTitle || '未命名文档' }}</span>
            <span class="hit-no">第 {{ hit.chunkNo }} 块</span>
            <span class="hit-score">{{ keywordMode ? '匹配度' : '相似度' }} {{ (hit.score ?? 0).toFixed(3) }}</span>
          </div>
          <div class="hit-content">{{ hit.content }}</div>
        </div>
        <el-empty
          v-if="searchResult && !searchResult.results.length"
          description="没有命中任何切片：确认文档已上传且索引成功"
        />
      </div>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  changeKbDocumentStatus,
  createKbCategory,
  createKbDocument,
  deleteKbDocument,
  fetchKbCategories,
  fetchKbChunks,
  fetchKbDocumentDetail,
  fetchKbDocuments,
  fetchKbOverview,
  reindexKbDocument,
  searchKb,
  updateKbDocument,
  uploadKbDocument,
  type KbCategoryItem,
  type KbChunkItem,
  type KbDocItem,
  type KbOverview,
  type KbSearchResult,
} from '../../api/customer/kb'

const loading = ref(false)
const overview = ref<KbOverview | null>(null)
const categories = ref<KbCategoryItem[]>([])
const documents = ref<KbDocItem[]>([])
const categoryId = ref('')
const statusFilter = ref(0)
const keyword = ref('')

const uploadVisible = ref(false)
const uploading = ref(false)
const uploadFile = ref<File | null>(null)
const uploadTitle = ref('')
const uploadCategoryId = ref('')

const formVisible = ref(false)
const saving = ref(false)
const editingId = ref('')
const form = reactive({ title: '', categoryId: '', summary: '', content: '' })
/** 当前编辑的正文是从哪来的：manual-手工录入、chunks-由切片拼回、null-还没有正文 */
const contentSource = ref<'manual' | 'chunks' | null>(null)
/**
 * 打开编辑框时看到的正文原文。
 * 保存时拿它和输入框对比：只有正文真的被改过才提交正文——
 * 否则"只改分类"也会把正文原样回传，触发一次没必要的重新索引。
 */
const loadedContent = ref('')

const categoryVisible = ref(false)
const savingCategory = ref(false)
const categoryName = ref('')

const chunkVisible = ref(false)
const loadingChunks = ref(false)
const chunks = ref<KbChunkItem[]>([])
const chunkDoc = ref<KbDocItem | null>(null)

const searchVisible = ref(false)
const searching = ref(false)
const searchQuery = ref('')
const searchResult = ref<KbSearchResult | null>(null)
/** 是否是关键词兜底模式：是的话分值是"匹配度"，不是余弦相似度，标签也要跟着换 */
const keywordMode = computed(() => (searchResult.value?.mode || '').includes('keyword'))

onMounted(loadAll)

async function loadAll() {
  await Promise.all([loadOverview(), loadCategories(), loadDocuments()])
}

async function loadOverview() {
  try {
    overview.value = await fetchKbOverview()
  } catch {
    overview.value = null
  }
}

async function loadCategories() {
  try {
    categories.value = await fetchKbCategories()
  } catch {
    categories.value = []
  }
}

async function loadDocuments() {
  loading.value = true
  try {
    documents.value = await fetchKbDocuments({
      categoryId: categoryId.value || undefined,
      status: statusFilter.value || undefined,
      keyword: keyword.value.trim() || undefined,
    })
  } finally {
    loading.value = false
  }
}

function filterCategory(id: string) {
  categoryId.value = id
  void loadDocuments()
}

/* ---------------- 上传 ---------------- */

function openUpload() {
  uploadFile.value = null
  uploadTitle.value = ''
  uploadCategoryId.value = categoryId.value || ''
  uploadVisible.value = true
}

function onFileChange(file: { raw?: File }) {
  uploadFile.value = file.raw || null
}

async function submitUpload() {
  if (!uploadFile.value) {
    ElMessage.warning('请先选择文件')
    return
  }
  uploading.value = true
  try {
    const doc = await uploadKbDocument(uploadFile.value, uploadCategoryId.value || undefined,
      uploadTitle.value.trim() || undefined)
    uploadVisible.value = false
    ElMessage.success(`已上传并建立索引：${doc.chunkCount} 个切片`)
    await loadAll()
  } catch {
    // 请求层已提示（索引失败的原因来自后端 index_message）
  } finally {
    uploading.value = false
  }
}

/* ---------------- 新建 / 编辑 ---------------- */

function openCreate() {
  editingId.value = ''
  form.title = ''
  form.categoryId = categoryId.value || ''
  form.summary = ''
  form.content = ''
  loadedContent.value = ''
  contentSource.value = null
  formVisible.value = true
}

async function openEdit(row: KbDocItem) {
  editingId.value = row.id
  form.title = row.title
  form.categoryId = row.categoryId || ''
  form.summary = row.summary || ''
  form.content = ''
  formVisible.value = true
  try {
    const detail = await fetchKbDocumentDetail(row.id)
    form.content = detail.content || ''
    loadedContent.value = form.content
    contentSource.value = detail.contentSource || null
  } catch {
    // 拿不到正文也不影响改标题
  }
}

async function submitForm() {
  if (!form.title.trim()) {
    ElMessage.warning('请填写文档标题')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      const contentChanged = form.content !== loadedContent.value
      await updateKbDocument(editingId.value, {
        title: form.title.trim(),
        categoryId: form.categoryId || null,
        summary: form.summary.trim(),
        // 正文没改就不传：只改标题/分类时不做无谓的重新索引
        content: contentChanged ? form.content : undefined,
      })
      ElMessage.success(contentChanged ? '已保存，正文有改动，已重新索引' : '已保存')
    } else {
      await createKbDocument({
        title: form.title.trim(),
        categoryId: form.categoryId || null,
        summary: form.summary.trim(),
        content: form.content,
      })
      ElMessage.success('文档已创建并建立索引')
    }
    formVisible.value = false
    await loadAll()
  } catch {
    // 请求层已提示
  } finally {
    saving.value = false
  }
}

async function submitCategory() {
  if (!categoryName.value.trim()) {
    ElMessage.warning('请填写分类名称')
    return
  }
  savingCategory.value = true
  try {
    await createKbCategory({ name: categoryName.value.trim() })
    categoryVisible.value = false
    categoryName.value = ''
    ElMessage.success('分类已创建')
    await loadCategories()
  } finally {
    savingCategory.value = false
  }
}

function openCategory() {
  categoryName.value = ''
  categoryVisible.value = true
}

/* ---------------- 行操作 ---------------- */

async function reindex(row: KbDocItem) {
  try {
    const doc = await reindexKbDocument(row.id)
    ElMessage.success(`已重新索引：${doc.chunkCount} 个切片`)
    await loadAll()
  } catch {
    await loadAll()
  }
}

async function changeStatus(row: KbDocItem, status: number) {
  try {
    await changeKbDocumentStatus(row.id, status)
    ElMessage.success(status === 3 ? '已发布' : '已下线')
    await loadAll()
  } catch {
    // 请求层已提示（没索引成功不允许发布）
  }
}

async function remove(row: KbDocItem) {
  await ElMessageBox.confirm(
    `确认删除「${row.title}」吗？删除后台账和向量切片都会清掉，机器人就查不到它了。`,
    '删除文档',
    { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '再想想' },
  )
  await deleteKbDocument(row.id)
  ElMessage.success('文档已删除')
  await loadAll()
}

async function openChunks(row: KbDocItem) {
  chunkDoc.value = row
  chunkVisible.value = true
  loadingChunks.value = true
  chunks.value = []
  try {
    chunks.value = await fetchKbChunks(row.id)
  } finally {
    loadingChunks.value = false
  }
}

/* ---------------- 检索测试 ---------------- */

function openSearch() {
  // 每次打开都清空上次的输入与结果：检索测试是"临时问一句"，
  // 留着上一次的问题容易让人以为是这次查出来的
  searchQuery.value = ''
  searchResult.value = null
  searchVisible.value = true
}

async function doSearch() {
  if (!searchQuery.value.trim()) {
    ElMessage.warning('请输入要检索的内容')
    return
  }
  searching.value = true
  try {
    searchResult.value = await searchKb({ query: searchQuery.value.trim(), topK: 5 })
  } finally {
    searching.value = false
  }
}

/* ---------------- 展示辅助 ---------------- */

function statusTag(status: number) {
  if (status === 3) return 'success'
  if (status === 4) return 'info'
  return 'warning'
}

function indexTag(indexStatus: number) {
  if (indexStatus === 3) return 'success'
  if (indexStatus === 2) return 'warning'
  if (indexStatus === 4) return 'danger'
  return 'info'
}

/** 后端给的是 ISO 串（带毫秒/微秒），统一显示到秒 */
function formatTime(value?: string | null) {
  if (!value) return '—'
  const text = String(value).replace('T', ' ')
  return text.length > 19 ? text.slice(0, 19) : text
}

function formatSize(size: number) {
  if (!size) return '0 B'
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}
</script>
<style scoped>
.kb-page { width: 100%; min-width: 0; }

.page-title-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.page-title { margin: 0; font-size: 20px; color: #0f172a; }
.page-sub { margin: 6px 0 0; font-size: 13px; color: #64748b; max-width: 720px; }
.head-actions { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.btn-icon { margin-right: 4px; }

.stat-row { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 12px; margin-bottom: 16px; }
.stat-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  background: #fff;
  border: 1px solid #e6ebf2;
  border-radius: 12px;
}
.stat-icon { width: 38px; height: 38px; border-radius: 10px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.stat-icon.blue { background: #eef4ff; color: #2563eb; }
.stat-icon.green { background: #ecfdf5; color: #059669; }
.stat-icon.red { background: #fef2f2; color: #dc2626; }
.stat-icon.purple { background: #f5f3ff; color: #7c3aed; }
.stat-icon.amber { background: #fff7ed; color: #d97706; }
.stat-value { font-size: 22px; font-weight: 800; color: #0f172a; line-height: 1.2; }
.stat-label { margin-top: 3px; font-size: 12px; color: #94a3b8; }

.kb-body { display: grid; grid-template-columns: 220px minmax(0, 1fr); gap: 16px; align-items: start; }

.kb-side { background: #fff; border: 1px solid #e6ebf2; border-radius: 12px; padding: 12px; }
.side-head { display: flex; align-items: center; justify-content: space-between; font-size: 13px; font-weight: 600; color: #334155; margin-bottom: 8px; }
.cat-list { display: flex; flex-direction: column; gap: 2px; }
.cat-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 7px 10px;
  border-radius: 8px;
  font-size: 13px;
  color: #475569;
  cursor: pointer;
}
.cat-item:hover { background: #f1f5f9; }
.cat-item.active { background: #eff6ff; color: #1d4ed8; font-weight: 600; }
.cat-count { font-size: 12px; color: #94a3b8; }
.cat-empty { font-size: 12px; color: #94a3b8; padding: 8px 10px; }

.kb-main { background: #fff; border: 1px solid #e6ebf2; border-radius: 12px; padding: 14px 16px; min-width: 0; }
.filter-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.kb-search { width: 320px; }

.doc-title { font-weight: 600; color: #0f172a; }
.cell-sub { margin-top: 3px; font-size: 12px; color: #94a3b8; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.ml-6 { margin-left: 6px; }
.mt-12 { margin-top: 12px; }
.mb-10 { margin-bottom: 10px; }
.full-width { width: 100%; }

.kb-empty { padding: 26px 0; }
.kb-empty-title { font-size: 15px; font-weight: 700; color: #334155; }
.kb-empty-sub { margin-top: 8px; font-size: 13px; color: #94a3b8; line-height: 1.8; }

.kb-upload :deep(.el-upload-dragger) { padding: 22px 0; border-radius: 12px; }
.up-icon { font-size: 34px; color: #94a3b8; }
.up-text { margin-top: 6px; font-size: 13px; color: #475569; }
.up-text em { color: #2563eb; font-style: normal; }
.up-tip { margin-top: 4px; font-size: 12px; color: #94a3b8; }
.up-file {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  padding: 8px 12px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  font-size: 13px;
  color: #334155;
}
.up-size { margin-left: auto; color: #94a3b8; font-size: 12px; }

.chunk-tip { font-size: 13px; color: #64748b; line-height: 1.8; margin-bottom: 12px; }
.chunk-list { display: flex; flex-direction: column; gap: 10px; }
.chunk-item { border: 1px solid #e6ebf2; border-radius: 10px; padding: 10px 12px; background: #fcfdff; }
.chunk-head { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; font-size: 12px; color: #94a3b8; }
.chunk-no { font-weight: 700; color: #2563eb; }
.chunk-model { margin-left: auto; }
.chunk-content { font-size: 13px; color: #334155; line-height: 1.75; white-space: pre-wrap; word-break: break-word; }

.search-tip { font-size: 13px; color: #64748b; line-height: 1.8; margin-bottom: 10px; }
.search-bar { display: flex; gap: 8px; }
.search-meta { margin-top: 10px; font-size: 13px; color: #64748b; }
.search-hint { margin-top: 6px; font-size: 12px; color: #b45309; line-height: 1.7; }
.search-hint code { background: #fff7ed; padding: 0 4px; border-radius: 4px; }
.hit-list { margin-top: 10px; display: flex; flex-direction: column; gap: 10px; max-height: 420px; overflow: auto; }
.hit-item { border: 1px solid #e6ebf2; border-radius: 10px; padding: 10px 12px; }
.hit-head { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; font-size: 12px; color: #94a3b8; }
.hit-doc { font-weight: 700; color: #0f172a; font-size: 13px; }
.hit-score { margin-left: auto; color: #2563eb; font-weight: 600; }
.hit-content { font-size: 13px; color: #334155; line-height: 1.75; white-space: pre-wrap; word-break: break-word; }
</style>
