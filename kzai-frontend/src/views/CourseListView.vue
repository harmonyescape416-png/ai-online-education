<template>
  <div>
    <h2 class="kz-page-title">课程浏览</h2>

    <el-card shadow="never" style="margin-bottom: 16px">
      <el-form :inline="true" :model="query">
        <el-form-item label="分类">
          <!-- M15.14-R2 C1：一级/二级分类联动（数据来自 /courses/categories，level+parentId 真实结构） -->
          <el-select v-model="firstCateId" clearable filterable placeholder="一级分类" aria-label="一级分类" style="width: 140px"
                     @change="onFirstCateChange">
            <el-option v-for="c in level1Categories" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
          <el-select v-model="query.categoryId" clearable filterable placeholder="二级分类" aria-label="二级分类" style="width: 140px; margin-left: 8px"
                     :disabled="!firstCateId" @change="reload">
            <el-option v-for="c in level2Categories" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="排序">
          <el-select v-model="query.sort" style="width: 150px">
            <el-option label="最新上架" value="latest" />
            <el-option label="价格从低到高" value="price_asc" />
            <el-option label="价格从高到低" value="price_desc" />
            <el-option label="热度" value="hot" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-input v-model="query.keyword" clearable placeholder="搜索课程名称 / 简介" style="width: 260px"
                    @keyup.enter="reload" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="reload">查询</el-button>
          <el-button @click="reset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <div v-if="loading" class="kz-card-grid">
      <!-- M15.14-R6 G4：骨架屏（异步加载期占位，避免布局跳动） -->
      <el-card v-for="i in 6" :key="'sk' + i" shadow="never">
        <el-skeleton animated style="margin: -4px">
          <template #template>
            <el-skeleton-item variant="image" style="width: 100%; aspect-ratio: 16/9; border-radius: 6px" />
            <div style="padding: 10px 2px">
              <el-skeleton-item variant="text" style="width: 70%" />
              <el-skeleton-item variant="text" style="width: 40%; margin-top: 6px" />
            </div>
          </template>
        </el-skeleton>
      </el-card>
    </div>
    <div v-else-if="couponBlocked" class="kz-empty">
      <div>{{ couponBlocked }}</div>
      <el-button style="margin-top: 12px" @click="router.push('/coupons')">返回优惠券</el-button>
    </div>
    <div v-else-if="!courses.length" class="kz-empty">{{ couponId ? '暂无适用课程' : '暂无课程' }}</div>
    <div v-else class="kz-card-grid">
      <router-link v-for="c in courses" :key="c.id" :to="courseLink(c)" class="kz-course-link" :aria-label="c.name">
        <el-card class="kz-course-card" shadow="hover">
        <!-- M15.13：统一主题封面（真实封面优先，占位/缺失映射本地 SVG），失败降级 -->
        <div class="cover-wrap">
          <img :src="coverUrlOf(c)" class="cover" loading="lazy" :alt="c.name" @error="onCoverError" />
          <el-tag v-if="c.free === 1" class="cover-tag" type="success" effect="dark" size="small">免费</el-tag>
        </div>
        <div class="course-name">{{ c.name }}</div>
        <div class="kz-meta">
          <span>{{ c.courseType === 1 ? '直播' : '录播' }}</span>
          <template v-if="c.sectionNum > 0">
            <el-divider direction="vertical" />
            <span>{{ c.sectionNum }} 节</span>
          </template>
          <span v-if="c.validDuration" class="kz-meta-dim">· 有效期 {{ c.validDuration }} 个月</span>
        </div>
        <div class="price-row">
          <template v-if="c.couponPrice">
            <span class="price-origin">¥{{ c.couponPrice.originalYuan }}</span>
            <span class="kz-price">¥{{ c.couponPrice.payableYuan }}</span>
            <span class="price-off">{{ c.couponPrice.tag }}</span>
          </template>
          <span v-else :class="c.free === 1 ? 'kz-price-free' : 'kz-price'">
            {{ c.free === 1 ? '免费' : `¥${c.priceYuan}` }}
          </span>
        </div>
        </el-card>
      </router-link>
    </div>

    <div style="margin-top: 20px; display: flex; justify-content: flex-end">
      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        :page-sizes="[8, 12, 20]"
        layout="total, sizes, prev, pager, next"
        @current-change="load"
        @size-change="reload"
      />
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { courseApi, couponApi } from '../api'
import { coverUrlOf, onCoverError } from '../utils/courseCover'

const route = useRoute()
const router = useRouter()
const couponId = computed(() => route.query.couponId ? String(route.query.couponId) : '')
const couponBlocked = ref('')
const courses = ref([])
const categories = ref([])
const total = ref(0)
const loading = ref(false)
// M15.14-R2 C1：一级/二级分类联动（数据来自 /courses/categories 的 level/parentId 真实结构）
const firstCateId = ref(null)
const level1Categories = computed(() => categories.value.filter(c => c.level === 1))
const level2Categories = computed(() =>
  firstCateId.value ? categories.value.filter(c => c.level === 2 && c.parentId === firstCateId.value) : [])
function onFirstCateChange(v) {
  // M15.14-R2 C1：一级变化即按一级筛选（后端 OR 匹配 first/second_cate_id）；
  // 二级变化按二级精确筛选；清空一级时同时清空二级
  query.categoryId = v || null
  if (!v) query.categoryId = null
  reload()
}

const query = reactive({
  pageNum: 1,
  pageSize: 12,
  categoryId: null,
  keyword: '',
  sort: 'latest'
})

async function loadCategories() {
  categories.value = await courseApi.categories()
}

function priceTag(coupon, price) {
  if (!price) return ''
  if (coupon?.couponType === 'DISCOUNT') {
    const fold = Number(coupon.payRatio) / 1000
    const text = Number.isInteger(fold) ? `${fold}折` : `${fold.toFixed(1)}折`
    return `${text} -¥${price.discountYuan}`
  }
  return `-¥${price.discountYuan}`
}

async function applyCouponPrices(records, coupon) {
  if (!couponId.value || !records.length) return records
  const data = await couponApi.prices(couponId.value, records.map((course) => String(course.id)))
  if (data?.status && data.status !== '可使用') {
    couponBlocked.value = data.status
    return []
  }
  const byId = new Map((data?.courses || []).map((item) => [String(item.courseId), item]))
  return records.filter((course) => byId.has(String(course.id))).map((course) => {
    const price = byId.get(String(course.id))
    return { ...course, couponPrice: { ...price, tag: priceTag(coupon, price) } }
  })
}

async function load() {
  loading.value = true
  couponBlocked.value = ''
  try {
    let coupon = null
    const params = {
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      categoryId: query.categoryId || undefined,
      sort: query.sort || 'latest',
      keyword: query.keyword?.trim() || undefined
    }
    if (couponId.value) {
      coupon = await couponApi.mineOne(couponId.value)
      if (coupon.status !== '可使用') {
        couponBlocked.value = coupon.status
        courses.value = []
        total.value = 0
        return
      }
      if (coupon.courseScope === 'INCLUDE') {
        if (!coupon.courseIds?.length) {
          courses.value = []
          total.value = 0
          return
        }
        params.ids = coupon.courseIds.join(',')
      }
      const minPrice = Math.max(Number(coupon.thresholdAmount) || 0, 1)
      params.minPrice = minPrice
    }
    if (!couponId.value && query.keyword && query.keyword.trim()) {
      const list = await courseApi.search(query.keyword.trim())
      courses.value = list || []
      total.value = courses.value.length
      return
    }
    const page = await courseApi.list(params)
    let records = page.records || []
    if (couponId.value) {
      records = await applyCouponPrices(records, coupon)
    }
    courses.value = records
    total.value = Number(page.total) || 0
  } catch (e) {
    courses.value = []
    couponBlocked.value = couponBlocked.value || (e?.message || '课程加载失败')
  } finally {
    loading.value = false
  }
}

function courseLink(course) {
  return couponId.value ? { path: `/courses/${course.id}`, query: { couponId: couponId.value } } : `/courses/${course.id}`
}

function reload() {
  query.pageNum = 1
  load()
}

function reset() {
  query.categoryId = null
  query.keyword = ''
  query.sort = 'latest'
  reload()
}

function goDetail(id) {
  router.push(`/courses/${id}`)
}

watch(() => [query.categoryId, query.sort], () => reload())
watch(couponId, () => {
  query.pageNum = 1
  query.sort = 'latest'
  query.keyword = ''
  query.categoryId = null
  firstCateId.value = null
  load()
})

onMounted(async () => {
  await loadCategories()
  await load()
})
</script>

<style scoped>
.kz-card-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(250px, 1fr)); gap: 18px; }
.kz-course-link { display: block; text-decoration: none; color: inherit; }
.kz-course-link:focus-visible { outline: 2px solid #4F46E5; outline-offset: 2px; border-radius: 8px; }
.kz-course-card { cursor: pointer; overflow: hidden; transition: transform .18s ease, box-shadow .18s ease; }
.kz-course-card:hover { transform: translateY(-3px); box-shadow: 0 8px 24px rgba(16,24,40,.12); }
.cover-wrap { position: relative; margin: -4px -4px 0; }
.cover { display: block; width: 100%; aspect-ratio: 16 / 9; object-fit: cover; border-radius: 6px 6px 0 0; }
.cover-tag { position: absolute; top: 8px; right: 8px; }
.course-name { margin: 10px 0 6px; font-weight: 600; line-height: 1.4; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; min-height: 2.8em; }
.kz-meta-dim { color: #98a2b3; }
.price-row { display: flex; align-items: baseline; gap: 8px; }
.price-origin { color: #98a2b3; text-decoration: line-through; font-size: 13px; }
.price-off { color: #e11d48; font-size: 13px; font-weight: 600; }
/* M15.28：390 等窄屏——课程卡单列撑满主区域（1440 四列网格不变） */
@media (max-width: 768px) {
  .kz-card-grid { grid-template-columns: 1fr; gap: 12px; }
  .kz-card-grid :deep(.el-card) { width: 100%; }
  /* 筛选表单纵向排列（控件撑满行宽） */
  .el-form--inline .el-form-item { display: flex; margin-right: 0; width: 100%; }
  .el-form--inline .el-form-item .el-select,
  .el-form--inline .el-form-item .el-input { width: 100% !important; margin-left: 0 !important; }
}
</style>
