<template>
  <el-collapse class="recommend-panel" v-model="expanded" @change="onExpand">
    <el-collapse-item title="按学习目标选课" name="recommend">
      <el-form :inline="true" size="small" @submit.prevent="recommend">
        <el-form-item label="学习目标"><el-input v-model="form.goal" placeholder="例如：做一个能上线的 Java 后端服务" maxlength="500" /></el-form-item>
        <el-form-item label="基础"><el-select v-model="form.foundation" style="width:120px"><el-option label="暂不限定" value="ANY" /><el-option label="零基础" value="BEGINNER" /><el-option label="有基础进阶" value="INTERMEDIATE" /></el-select></el-form-item>
        <el-form-item label="单门预算（元）"><el-input-number v-model="form.budget" :min="0" :max="1000000" :precision="2" :controls="false" placeholder="可跳过" /></el-form-item>
        <el-form-item label="每周小时"><el-input-number v-model="form.hours" :min="0.5" :max="168" :controls="false" placeholder="可跳过" /></el-form-item>
        <el-form-item label="计划周数"><el-input-number v-model="form.weeks" :min="1" :max="520" :precision="0" :controls="false" placeholder="可跳过" /></el-form-item>
        <el-form-item><el-checkbox v-model="form.includeOwned">包含已购、已学课程</el-checkbox></el-form-item>
        <el-form-item><el-button type="primary" :loading="loading" :disabled="!sessionId" @click="recommend">{{ result ? '调整后重新推荐' : '查找课程' }}</el-button></el-form-item>
      </el-form>
      <el-alert v-if="error" type="error" :title="error" :closable="false" />
      <template v-if="result">
        <el-tag size="small">{{ modeLabel }}</el-tag>
        <p class="recommend-message">{{ result.message }}</p>
        <p v-if="result.nextAction" class="recommend-message">{{ result.nextAction }}</p>
        <div class="recommend-courses"><CourseCard v-for="course in result.courses || []" :key="course.courseId" :payload="course" @ask="$emit('ask', $event)" /></div>
        <template v-if="result.ownedCourses?.length">
          <p class="recommend-message">你已购或已加入学习的相关课程</p>
          <div class="recommend-courses"><CourseCard v-for="course in result.ownedCourses" :key="course.courseId" :payload="course" @ask="$emit('ask', $event)" /></div>
        </template>
      </template>
    </el-collapse-item>
  </el-collapse>
</template>
<script setup>
import { computed, reactive, ref, watch } from 'vue'
import request from '../api'
import CourseCard from './CourseCard.vue'
const props = defineProps({ sessionId: { type: String, default: '' } })
const emit = defineEmits(['ask', 'start-session'])
function onExpand(names) {
  if (names.includes('recommend') && !props.sessionId) emit('start-session')
}
const expanded = ref([])
const loading = ref(false)
const result = ref(null)
const error = ref('')
const form = reactive({ goal: '', foundation: 'ANY', budget: undefined, hours: undefined, weeks: undefined, includeOwned: false })
const modeLabel = computed(() => result.value?.status === 'SEARCH_INCOMPLETE' ? '查询未完整完成' :
  ({ HYBRID: '语义与关键词综合查询', SEMANTIC: result.value?.courses?.length ? '语义推荐' : '已完成语义查询', KEYWORD_ONLY: '关键词查课', KEYWORD_FALLBACK: '关键词查课（已降级）', NONE: '补充学习目标', UNAVAILABLE: '暂不可用' })[result.value?.retrievalMode] || '推荐结果')
let sequence = 0
watch(() => props.sessionId, async (session) => {
  const seq = ++sequence
  result.value = null
  error.value = ''
  Object.assign(form, { goal: '', foundation: 'ANY', budget: undefined, hours: undefined, weeks: undefined, includeOwned: false })
  if (!session) return
  try {
    const p = await request.get(`/chat/recommendations/${encodeURIComponent(session)}`)
    if (seq !== sequence) return
    Object.assign(form, { goal: p.goal || '', foundation: p.foundation || 'ANY', budget: p.budgetCents == null ? undefined : p.budgetCents / 100, hours: p.hoursPerWeek ?? undefined, weeks: p.weeks ?? undefined, includeOwned: p.includeOwned === true })
  } catch (e) { if (seq === sequence) error.value = e.message || '推荐条件加载失败' }
}, { immediate: true })
async function recommend() {
  if (loading.value || !props.sessionId) return
  const session = props.sessionId
  const seq = sequence
  loading.value = true
  result.value = null
  error.value = ''
  try {
    const preferences = { goal: form.goal.trim() || undefined, foundation: form.foundation, includeOwned: form.includeOwned,
      budgetCents: form.budget == null ? undefined : Math.round(form.budget * 100), clearBudget: form.budget == null,
      hoursPerWeek: form.hours ?? undefined, weeks: form.weeks ?? undefined, clearTime: true }
    const response = await request.post('/chat/recommendations', { sessionId: session, preferences })
    if (seq === sequence) result.value = response
  } catch (e) { if (seq === sequence) error.value = e.message || '推荐失败，请重试' }
  finally { loading.value = false }
}
</script>
<style scoped>
.recommend-panel { padding: 0 12px; flex: 0 0 auto; max-height: 55vh; overflow-y: auto; background: #fff; }
.recommend-courses { display: flex; flex-wrap: wrap; gap: 8px; }
.recommend-message { font-size: 13px; color: #475569; }
</style>
