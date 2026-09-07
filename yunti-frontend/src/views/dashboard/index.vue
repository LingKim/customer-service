<template>
  <div>
    <h2 class="page-title">数据概览</h2>
    <el-row :gutter="16">
      <el-col v-for="item in stats" :key="item.label" :span="6">
        <el-card shadow="hover">
          <div class="stat-label">{{ item.label }}</div>
          <div class="stat-value">{{ item.value }}</div>
        </el-card>
      </el-col>
    </el-row>
    <el-card class="mt-16" shadow="never">
      <template #header>骨架说明</template>
      <p class="muted">
        当前为 Vue 3 + TypeScript + Vite + Element Plus 管理后台骨架：路由守卫、Pinia 状态、Axios
        统一请求（token / 租户头 / 统一解包）、布局组件均已就绪。业务页面按目录继续扩展。
      </p>
    </el-card>
  </div>
</template>
<script setup lang="ts">
const stats = [
  { label: '今日会话', value: '1,386' },
  { label: '机器人解决率', value: '71.4%' },
  { label: '平均首响', value: '20s' },
  { label: '客户满意度', value: '97.0%' },
]
</script>
<style scoped>
.page-title {
  margin: 0 0 16px;
  font-size: 20px;
}

.stat-label {
  color: #64748b;
  font-size: 13px;
}

.stat-value {
  font-size: 24px;
  font-weight: 700;
  color: #0f172a;
  margin-top: 6px;
}

.mt-16 {
  margin-top: 16px;
}

.muted {
  color: #64748b;
  line-height: 1.8;
  margin: 0;
}
</style>
