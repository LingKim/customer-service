<template>
  <el-dialog
    :model-value="modelValue"
    width="420px"
    align-center
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="dialog-body">
      <div class="dialog-icon"><el-icon :size="26"><SwitchButton /></el-icon></div>
      <div>
        <h3>退出登录</h3>
        <p>确定要退出当前登录账号吗？</p>
        <div class="account"><strong>{{ name || '—' }}</strong><span>{{ account }}</span></div>
      </div>
    </div>
    <div class="note">退出后需要重新登录，未保存的页面操作可能丢失。</div>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" @click="confirm">确认退出</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
defineProps<{ modelValue: boolean; name: string; account: string }>()
const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
  (event: 'confirm'): void
}>()

function confirm() {
  emit('confirm')
  emit('update:modelValue', false)
}
</script>

<style scoped>
.dialog-body { display: flex; gap: 14px; }
.dialog-icon { width: 50px; height: 50px; border-radius: 14px; background: #eff6ff; color: #2563eb; display: grid; place-items: center; flex: none; }
h3 { margin: 2px 0 6px; color: #0f172a; }
p { margin: 0; color: #64748b; font-size: 13px; }
.account { display: flex; gap: 8px; margin-top: 10px; padding: 9px 12px; border: 1px solid #eef2f7; border-radius: 10px; background: #f8fafc; font-size: 13px; }
.account span { color: #94a3b8; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.note { margin-top: 14px; padding: 9px 12px; border: 1px solid #dbeafe; border-radius: 10px; background: #eff6ff; color: #1d4ed8; font-size: 12px; line-height: 1.7; }
</style>
