<template>
  <div class="kz-chapter-nav">
    <div class="kz-chapter-nav__title">章节目录</div>
    <el-scrollbar max-height="52vh">
      <ul class="kz-chapter-nav__list">
        <li
          v-for="(ch, index) in chapters"
          :key="ch.id"
          :class="{
            'is-active': String(ch.id) === String(currentId),
            'is-locked': !ch.accessible
          }"
          @click="onSelect(ch)"
        >
          <span class="kz-chapter-nav__index">{{ index + 1 }}</span>
          <span class="kz-chapter-nav__name">{{ ch.name }}</span>
          <el-tag v-if="ch.status === 2" size="small" type="success" effect="plain">已完成</el-tag>
          <el-tag v-else-if="ch.status === 1" size="small" type="primary" effect="plain">学习中</el-tag>
          <el-tag v-if="ch.freePreview && !ch.accessible" size="small" type="warning" effect="plain">可试看</el-tag>
          <el-icon v-if="!ch.accessible" class="kz-chapter-nav__lock"><Lock /></el-icon>
        </li>
      </ul>
    </el-scrollbar>
  </div>
</template>

<script setup>
import { Lock } from '@element-plus/icons-vue'

/**
 * 章节导航组件（M5）。
 * accessible 由服务端依据学习权益与试看标识计算，前端只负责呈现，不做权限推断。
 */
const props = defineProps({
  chapters: { type: Array, default: () => [] },
  currentId: { type: [String, Number], default: null }
})
const emit = defineEmits(['select'])

function onSelect(ch) {
  if (!ch.accessible) {
    return
  }
  emit('select', ch)
}
</script>

<style scoped>
.kz-chapter-nav__title {
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 8px;
}
.kz-chapter-nav__list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.kz-chapter-nav__list li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 10px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  color: #303133;
}
.kz-chapter-nav__list li:hover {
  background: #f5f7fa;
}
.kz-chapter-nav__list li.is-active {
  background: #ecf5ff;
  color: #409eff;
  font-weight: 600;
}
.kz-chapter-nav__list li.is-locked {
  cursor: not-allowed;
  color: #c0c4cc;
}
.kz-chapter-nav__index {
  flex: none;
  width: 22px;
  height: 22px;
  line-height: 22px;
  text-align: center;
  border-radius: 50%;
  background: #f0f2f5;
  font-size: 12px;
}
.kz-chapter-nav__name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.kz-chapter-nav__lock {
  flex: none;
}
</style>
