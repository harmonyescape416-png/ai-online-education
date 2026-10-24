<template>
  <div class="kz-progress">
    <div class="kz-progress__head">
      <span class="kz-progress__label">{{ label }}</span>
      <span class="kz-progress__value">{{ display }}%</span>
    </div>
    <el-progress
      :percentage="display"
      :status="display >= 100 ? 'success' : undefined"
      :stroke-width="strokeWidth"
      :show-text="false"
    />
    <div v-if="chapterText" class="kz-progress__meta">{{ chapterText }}</div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

/**
 * 学习进度展示组件（M5）。
 * 进度值一律来自服务端（已完成章节数 / 实际章节数），组件不做任何本地推算或伪造。
 */
const props = defineProps({
  progress: { type: Number, default: 0 },
  finished: { type: Number, default: 0 },
  total: { type: Number, default: 0 },
  label: { type: String, default: '学习进度' },
  strokeWidth: { type: Number, default: 8 },
  showChapterText: { type: Boolean, default: true }
})

const display = computed(() => {
  const v = Number(props.progress)
  if (!Number.isFinite(v) || v < 0) return 0
  return Math.min(100, Math.round(v))
})

const chapterText = computed(() => {
  if (!props.showChapterText) return ''
  return `已完成 ${props.finished} / ${props.total} 章`
})
</script>

<style scoped>
.kz-progress__head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 6px;
}
.kz-progress__label {
  font-size: 13px;
  color: #606266;
}
.kz-progress__value {
  font-size: 13px;
  font-weight: 600;
  color: #409eff;
}
.kz-progress__meta {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}
</style>
