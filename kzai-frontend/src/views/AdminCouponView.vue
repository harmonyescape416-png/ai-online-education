<template>
  <div>
    <h2 class="kz-page-title">优惠券</h2>
    <el-button type="primary" @click="openCreate">新建草稿</el-button>
    <el-table :data="rows" border style="margin-top: 12px">
      <el-table-column prop="name" label="名称" min-width="140" />
      <el-table-column prop="couponType" label="类型" width="140" />
      <el-table-column prop="status" label="状态" width="120" />
      <el-table-column prop="claimedCount" label="领取量" width="90" />
      <el-table-column prop="usedCount" label="使用量" width="90" />
      <el-table-column prop="remainCount" label="剩余" width="90" />
      <el-table-column label="操作" width="220">
        <template #default="{ row }">
          <el-button v-if="row.status === 'DRAFT'" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="row.status === 'DRAFT'" size="small" type="primary" @click="publish(row)">发布</el-button>
          <el-button v-if="row.status === 'PUBLISHED'" size="small" @click="stop(row)">停止发放</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-dialog v-model="visible" title="优惠券草稿" width="640px">
      <el-form label-width="120px">
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="form.couponType">
            <el-radio value="FULL_REDUCTION">满减</el-radio>
            <el-radio value="DISCOUNT">折扣</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="最低消费(分)"><el-input v-model="form.thresholdAmount" /></el-form-item>
        <el-form-item v-if="form.couponType === 'FULL_REDUCTION'" label="减免(分)"><el-input v-model="form.reduceAmount" /></el-form-item>
        <el-form-item v-else label="支付万分比"><el-input v-model="form.payRatio" /></el-form-item>
        <el-form-item label="发行量"><el-input v-model="form.totalStock" /></el-form-item>
        <el-form-item label="每人限领"><el-input v-model="form.perUserLimit" /></el-form-item>
        <el-form-item label="领取开始">
          <el-date-picker v-model="form.claimStart" type="datetime" placeholder="领取开始" format="YYYY-MM-DD HH:mm:ss" value-format="YYYY-MM-DDTHH:mm:ss" style="width: 100%" />
        </el-form-item>
        <el-form-item label="领取结束">
          <el-date-picker v-model="form.claimEnd" type="datetime" placeholder="领取结束" format="YYYY-MM-DD HH:mm:ss" value-format="YYYY-MM-DDTHH:mm:ss" style="width: 100%" />
        </el-form-item>
        <el-form-item label="使用开始">
          <el-date-picker v-model="form.validStart" type="datetime" placeholder="使用开始" format="YYYY-MM-DD HH:mm:ss" value-format="YYYY-MM-DDTHH:mm:ss" style="width: 100%" />
        </el-form-item>
        <el-form-item label="使用结束">
          <el-date-picker v-model="form.validEnd" type="datetime" placeholder="使用结束" format="YYYY-MM-DD HH:mm:ss" value-format="YYYY-MM-DDTHH:mm:ss" style="width: 100%" />
        </el-form-item>
        <el-form-item label="范围">
          <el-radio-group v-model="form.courseScope">
            <el-radio value="ALL">全部课程</el-radio>
            <el-radio value="INCLUDE">指定课程</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.courseScope === 'INCLUDE'" label="课程名称">
          <el-select
            v-model="selectedCourseIds"
            multiple
            filterable
            remote
            reserve-keyword
            :remote-method="searchCourses"
            :loading="courseLoading"
            loading-text="课程加载中"
            placeholder="搜索并选择课程"
            style="width: 100%"
            @visible-change="onCourseVisible"
          >
            <el-option v-for="course in courseOptions" :key="course.id" :label="course.label" :value="course.id" />
            <template #empty>
              <div class="coupon-course-empty">
                <span v-if="courseLoading || !courseLoaded">课程加载中</span>
                <template v-else-if="courseError">
                  课程加载失败
                  <el-button link type="primary" @click="fetchCourses(courseKeyword)">重试</el-button>
                </template>
                <span v-else>没有匹配的课程</span>
              </div>
            </template>
          </el-select>
          <div v-if="courseKeyword.trim() && !courseLoading && !courseError && !searchHits.length" class="coupon-course-empty">没有匹配的课程</div>
          <div v-else-if="courseError" class="coupon-course-empty">
            课程加载失败
            <el-button link type="primary" @click="fetchCourses(courseKeyword)">重试</el-button>
          </div>
        </el-form-item>
        <el-form-item label="兑换码"><el-input v-model="form.redeemCode" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { couponAdminApi, courseApi } from '../api'

const rows = ref([])
const visible = ref(false)
const editingId = ref('')
const selectedCourseIds = ref([])
const selectedCourseMap = ref({})
const searchHits = ref([])
const courseLoading = ref(false)
const courseError = ref('')
const courseKeyword = ref('')
const courseLoaded = ref(false)
let fetchSeq = 0
let searchTimer = 0
const form = reactive(blank())

const courseOptions = computed(() => {
  const map = new Map()
  for (const id of selectedCourseIds.value) {
    map.set(id, selectedCourseMap.value[id] || { id, name: '课程已不可用', missing: true })
  }
  for (const course of searchHits.value) {
    map.set(course.id, course)
  }
  const all = [...map.values()]
  return all.map((course) => ({
    ...course,
    label: course.missing || !all.some((item) => item.id !== course.id && item.name === course.name)
      ? course.name
      : `${course.name}（${String(course.id).slice(-6)}）`
  }))
})

function pad(value) {
  return String(value).padStart(2, '0')
}

/** 后端 LocalDateTime.parse 只接受 2026-10-23T09:00:00。 */
function toApiTime(value) {
  if (value == null || value === '') return ''
  if (Array.isArray(value)) {
    const [year, month, day, hour = 0, minute = 0, second = 0] = value
    return `${year}-${pad(month)}-${pad(day)}T${pad(hour)}:${pad(minute)}:${pad(second)}`
  }
  const text = String(value).trim().replace(' ', 'T')
  const matched = text.match(/^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})/)
  return matched ? matched[1] : ''
}

function defaultRange() {
  const start = new Date()
  start.setMinutes(0, 0, 0)
  const claimEnd = new Date(start.getTime() + 7 * 24 * 60 * 60 * 1000)
  const validEnd = new Date(start.getTime() + 30 * 24 * 60 * 60 * 1000)
  const format = (date) => `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  return {
    claimStart: format(start),
    claimEnd: format(claimEnd),
    validStart: format(start),
    validEnd: format(validEnd)
  }
}

function blank() {
  return {
    name: '', couponType: 'FULL_REDUCTION', thresholdAmount: '0', reduceAmount: '100', payRatio: '8500',
    totalStock: '10', perUserLimit: '1', ...defaultRange(),
    courseScope: 'ALL', redeemCode: ''
  }
}

async function load() {
  rows.value = await couponAdminApi.list()
}

function resetCourses() {
  selectedCourseIds.value = []
  selectedCourseMap.value = {}
  searchHits.value = []
  courseError.value = ''
  courseKeyword.value = ''
  courseLoaded.value = false
}

function openCreate() {
  editingId.value = ''
  Object.assign(form, blank())
  resetCourses()
  visible.value = true
}

function openEdit(row) {
  editingId.value = row.id
  Object.assign(form, row, {
    claimStart: toApiTime(row.claimStart),
    claimEnd: toApiTime(row.claimEnd),
    validStart: toApiTime(row.validStart),
    validEnd: toApiTime(row.validEnd)
  })
  resetCourses()
  selectedCourseIds.value = (row.courseIds || []).map((id) => String(id))
  for (const id of selectedCourseIds.value) {
    courseApi.detail(id).then((course) => {
      if (!course?.id) return
      selectedCourseMap.value = {
        ...selectedCourseMap.value,
        [id]: { id, name: course.name || '未命名课程' }
      }
    }).catch(() => {
      selectedCourseMap.value = {
        ...selectedCourseMap.value,
        [id]: { id, name: '课程已不可用', missing: true }
      }
    })
  }
  visible.value = true
}

function payload() {
  const claimStart = toApiTime(form.claimStart)
  const claimEnd = toApiTime(form.claimEnd)
  const validStart = toApiTime(form.validStart)
  const validEnd = toApiTime(form.validEnd)
  if (!claimStart || !claimEnd || !validStart || !validEnd) {
    throw new Error('请选择领取开始、领取结束、使用开始和使用结束时间')
  }
  return {
    ...form,
    claimStart,
    claimEnd,
    validStart,
    validEnd,
    courseIds: form.courseScope === 'INCLUDE' ? selectedCourseIds.value : []
  }
}

async function fetchCourses(keyword) {
  const seq = ++fetchSeq
  courseLoading.value = true
  courseError.value = ''
  try {
    const trimmed = (keyword || '').trim()
    let list = []
    if (trimmed) {
      const data = await courseApi.search(trimmed)
      list = Array.isArray(data) ? data : []
    } else {
      const data = await courseApi.list({ pageNum: 1, pageSize: 20 })
      list = data?.records || []
    }
    if (seq !== fetchSeq) return
    searchHits.value = list.map((course) => ({
      id: String(course.id),
      name: course.name || '未命名课程'
    }))
    courseLoaded.value = true
  } catch (e) {
    if (seq !== fetchSeq) return
    courseError.value = e?.message || '课程加载失败'
    searchHits.value = []
    if (!e?.notified) ElMessage.error('课程加载失败，请重试')
  } finally {
    if (seq === fetchSeq) courseLoading.value = false
  }
}

function searchCourses(keyword) {
  courseKeyword.value = keyword || ''
  window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => fetchCourses(courseKeyword.value), 300)
}

function onCourseVisible(open) {
  if (open) fetchCourses(courseKeyword.value)
}

watch(() => form.courseScope, (scope) => {
  if (scope === 'INCLUDE' && !courseLoaded.value) fetchCourses('')
})

watch(selectedCourseIds, (ids) => {
  const next = { ...selectedCourseMap.value }
  for (const id of ids) {
    const hit = searchHits.value.find((course) => course.id === id)
    if (hit) next[id] = hit
  }
  for (const key of Object.keys(next)) {
    if (!ids.includes(key)) delete next[key]
  }
  selectedCourseMap.value = next
})

async function save() {
  try {
    const body = payload()
    if (editingId.value) await couponAdminApi.update(editingId.value, body)
    else await couponAdminApi.create(body)
    visible.value = false
    await load()
  } catch (e) {
    if (!e?.notified) ElMessage.error(e?.message || '保存失败')
  }
}

async function publish(row) {
  try {
    await couponAdminApi.publish(row.id)
    await load()
  } catch (e) {
    if (!e?.notified) ElMessage.error(e?.message || '发布失败')
  }
}

async function stop(row) {
  try {
    await couponAdminApi.stop(row.id)
    await load()
  } catch (e) {
    if (!e?.notified) ElMessage.error(e?.message || '停止失败')
  }
}

onMounted(load)
</script>

<style scoped>
.coupon-course-empty {
  padding: 10px 12px;
  color: #909399;
  font-size: 13px;
}
</style>
