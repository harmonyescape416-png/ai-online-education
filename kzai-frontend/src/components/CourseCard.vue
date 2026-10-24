<template>
  <div class="course-card">
    <img v-if="cover" class="course-card__cover" :src="cover" loading="lazy" alt="课程封面" @error="onCoverError" />
    <div v-else class="course-card__cover course-card__cover--empty">课程</div>
    <div class="course-card__info">
      <div class="course-card__name">{{ name }}</div>
      <div v-if="introduce" class="course-card__intro">{{ introduce }}</div>
      <ul v-if="payload.recommendationEvidence?.length" class="course-card__evidence">
        <li v-for="(item, i) in payload.recommendationEvidence" :key="i">{{ item }}</li>
      </ul>
      <div v-for="(warning, i) in payload.recommendationWarnings || []" :key="i" class="course-card__warning">{{ warning }}</div>
      <div class="course-card__meta">
        <span class="course-card__price">
          {{ free ? '免费' : '¥' + priceYuan }}
        </span>
        <span v-if="chapterCount > 0" class="course-card__chapters">{{ chapterCount }} 章</span>
        <span v-if="payload.validDuration != null">有效期 {{ payload.validDuration }} 个月</span>
        <el-tag v-if="payload.owned" type="success" size="small">{{ payload.completed ? '已完成' : '已购或已加入学习' }}</el-tag>
        <el-tag v-if="!purchasable" type="info" size="small">{{ reason || '暂不可购买' }}</el-tag>
      </div>
      <el-button size="small" type="primary" plain class="course-card__btn" @click="goDetail">
        {{ payload.owned ? '查看学习入口' : '查看详情' }}
      </el-button>
      <template v-if="payload.recommendationEvidence?.length">
        <el-button size="small" :disabled="!courseId" @click="ask(false)">咨询课程</el-button>
        <el-button size="small" :disabled="!courseId || !purchasable || payload.owned" @click="ask(true)">选择课程</el-button>
      </template>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { safeText } from '../utils/safeUrl'
import { coverUrlOf, onCoverError } from '../utils/courseCover'

const props = defineProps({
  /** 1003 COURSE_CARD 载荷（全部来自后端真实课程数据） */
  payload: { type: Object, required: true }
})

const router = useRouter()
const emit = defineEmits(['ask'])
const ask = (buy) => emit('ask', `${buy ? '我想购买' : '请介绍'}课程「${name.value}」（ID：${courseId.value}）${buy ? '，请先核对当前价格并展示购买确认。' : '的适用人群与章节安排。'}`)

// 课程 ID 一律按字符串处理（防 Long 精度丢失），并做基本合法性校验
const courseId = computed(() => {
  const id = props.payload?.courseId
  return /^\d{1,20}$/.test(String(id)) ? String(id) : ''
})
const cover = computed(() => safeText(coverUrlOf(props.payload), 200) || '')
const name = computed(() => safeText(props.payload?.name, 100) || '未命名课程')
const introduce = computed(() => safeText(props.payload?.introduce, 120))
const priceYuan = computed(() => safeText(props.payload?.priceYuan, 12) || '0.00')
const free = computed(() => Number(props.payload?.free) === 1)
const chapterCount = computed(() => Number(props.payload?.chapterCount) || 0)
const purchasable = computed(() => props.payload?.purchasable !== false)
const reason = computed(() => safeText(props.payload?.reason, 30))

const goDetail = () => {
  if (!courseId.value) return
  router.push({ name: 'course-detail', params: { id: courseId.value } })
}
</script>

<style scoped>
.course-card {
  display: flex;
  gap: 10px;
  padding: 10px;
  border: 1px solid #e6e8eb;
  border-radius: 8px;
  background: #fff;
  margin-top: 8px;
  max-width: 460px;
}
.course-card__cover {
  width: 96px;
  height: 64px;
  border-radius: 6px;
  object-fit: cover;
  flex-shrink: 0;
}
.course-card__cover--empty {
  display: flex;
  align-items: center;
  justify-content: center;
  background: #eef1f5;
  color: #98a2b3;
  font-size: 13px;
}
.course-card__info {
  flex: 1;
  min-width: 0;
}
.course-card__name {
  font-weight: 600;
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.course-card__intro {
  font-size: 12px;
  color: #667085;
  margin-top: 2px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.course-card__meta {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 4px;
  font-size: 13px;
}
.course-card__price {
  color: #f56c6c;
  font-weight: 600;
}
.course-card__chapters {
  color: #98a2b3;
  font-size: 12px;
}
.course-card__btn {
  margin-top: 6px;
}
.course-card__evidence { padding-left: 18px; font-size: 12px; line-height: 1.6; color: #475569; }
.course-card__warning { font-size: 12px; color: #92400e; }
</style>
