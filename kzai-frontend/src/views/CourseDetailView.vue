<template>
  <div v-if="detail">
    <el-button link type="primary" @click="router.back()">← 返回课程列表</el-button>
    <h2 class="kz-page-title" style="margin-top: 12px">{{ detail.name }}</h2>

    <el-row :gutter="20">
      <el-col :span="16">
        <el-card shadow="never">
          <template #header>
            <span>课程介绍</span>
          </template>
          <!-- M15.13：介绍内容来自 course_content（教师端维护）；缺失时如实说明 -->
          <template v-if="detail.courseIntroduce || detail.courseDetail || detail.usePeople">
            <p style="line-height: 1.8">{{ detail.courseIntroduce || '课程介绍完善中' }}</p>
            <p v-if="detail.usePeople" class="kz-meta">适合人群：{{ detail.usePeople }}</p>
            <el-divider />
            <p style="white-space: pre-wrap; line-height: 1.8">{{ detail.courseDetail || '' }}</p>
          </template>
          <el-empty v-else description="课程介绍正在完善中，可先查看章节目录了解课程内容" :image-size="72" />
        </el-card>

        <el-card shadow="never" style="margin-top: 16px">
          <template #header>
            <span>课程目录（{{ (detail.chapters || []).length }} 章）</span>
          </template>
          <el-empty v-if="!detail.chapters?.length" description="该课程正在完善章节内容" :image-size="72" />
          <el-timeline v-else>
            <el-timeline-item
              v-for="(ch, idx) in detail.chapters"
              :key="ch.id"
              :type="idx === 0 ? 'primary' : 'info'"
              :timestamp="'第' + (idx + 1) + '章'"
            >
              <div style="font-weight: 600">{{ ch.name }}</div>
              <div class="kz-meta" v-if="ch.description">{{ ch.description }}</div>
              <el-tag v-if="ch.tryWatch" size="small" type="success" effect="plain">可试看</el-tag>
            </el-timeline-item>
          </el-timeline>
        </el-card>
      </el-col>

      <el-col :span="8">
        <el-card shadow="never">
          <!-- M15.13：统一封面（真实上传优先，本地主题 SVG 降级） -->
          <img :src="coverUrlOf(detail)" style="width: 100%; aspect-ratio: 16 / 9; object-fit: cover; border-radius: 8px" loading="lazy" alt="课程封面" @error="onCoverError" />
          <div style="margin-top: 12px">
            <template v-if="couponPrice">
              <span class="price-origin">¥{{ couponPrice.originalYuan }}</span>
              <span class="kz-price">¥{{ couponPrice.payableYuan }}</span>
              <span class="price-off">{{ couponPrice.tag }}</span>
            </template>
            <span v-else :class="detail.free === 1 ? 'kz-price-free' : 'kz-price'">
              {{ detail.free === 1 ? '免费' : `¥${detail.priceYuan}` }}
            </span>
            <div v-if="couponStatus && couponStatus !== '可使用'" class="kz-tip">{{ couponStatus }}</div>
          </div>
          <el-descriptions :column="1" size="small" border style="margin-top: 12px">
            <el-descriptions-item label="课程形式">{{ detail.courseType === 1 ? '直播' : '录播' }}</el-descriptions-item>
            <el-descriptions-item label="总节数">{{ detail.sectionNum }} 节</el-descriptions-item>
            <el-descriptions-item v-if="detail.scoreStar" label="评分">{{ detail.scoreStar }}</el-descriptions-item>
            <el-descriptions-item label="有效期">{{ detail.validDuration }} 个月</el-descriptions-item>
          </el-descriptions>
          <el-button
            v-if="access?.enrolled"
            type="primary"
            style="width: 100%; margin-top: 16px"
            @click="router.push(`/learn/${route.params.id}`)"
          >
            继续学习
          </el-button>
          <el-button
            v-else
            type="primary"
            style="width: 100%; margin-top: 16px"
            :disabled="!store.isStudent"
            @click="onEnter"
          >
            {{ detail.free === 1 ? '免费加入学习' : '立即购买' }}
          </el-button>
          <div v-if="!store.isStudent" class="kz-tip">仅学员账号可加入学习或下单</div>
          <div v-else-if="access && !access.allowed && detail.free !== 1" class="kz-tip">
            {{ access.reason || '尚未取得该课程的学习权益' }}
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
  <div v-else class="kz-empty">加载中...</div>
</template>

<script setup>
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { courseApi, couponApi, learningApi } from '../api'
import { useUserStore } from '../store/user'
import { coverUrlOf, onCoverError } from '../utils/courseCover'

const route = useRoute()
const router = useRouter()
const store = useUserStore()
const detail = ref(null)
const access = ref(null)
const couponPrice = ref(null)
const couponStatus = ref('')

function priceTag(data, price) {
  if (!price) return ''
  if (data?.couponType === 'DISCOUNT') {
    const fold = Number(data.payRatio) / 1000
    const text = Number.isInteger(fold) ? `${fold}折` : `${fold.toFixed(1)}折`
    return `${text} -¥${price.discountYuan}`
  }
  return `-¥${price.discountYuan}`
}

function onBuy() {
  const query = { courseId: String(route.params.id) }
  if (route.query.couponId && couponPrice.value) query.couponId = String(route.query.couponId)
  router.push({ path: '/orders/confirm', query })
}

/** M5：免费课程直接入课（真实落库，不生成伪造订单）；付费课程走正常购买流程 */
async function onEnter() {
  if (detail.value.free !== 1) {
    onBuy()
    return
  }
  try {
    await learningApi.enroll(String(route.params.id))
    ElMessage.success('已加入学习')
    router.push(`/learn/${route.params.id}`)
  } catch (e) {
    ElMessage.error(e?.message || '加入学习失败')
  }
}

async function loadCouponPrice() {
  couponPrice.value = null
  couponStatus.value = ''
  if (!route.query.couponId || detail.value?.free === 1) return
  try {
    const data = await couponApi.prices(String(route.query.couponId), [String(route.params.id)])
    couponStatus.value = data?.status || ''
    const price = (data?.courses || [])[0]
    couponPrice.value = price ? { ...price, tag: priceTag(data, price) } : null
  } catch (e) {
    couponStatus.value = e?.message || '优惠券不可用'
  }
}

async function load() {
  access.value = null
  try {
    detail.value = await courseApi.detail(route.params.id)
  } catch (e) {
    ElMessage.error(e?.message || '课程加载失败')
    return
  }
  await loadCouponPrice()
  if (store.isStudent) {
    try {
      access.value = await learningApi.access(String(route.params.id))
    } catch (e) {
      access.value = null
    }
  }
}

onMounted(load)
// 路由参数变化（课程间跳转）时组件被复用，必须重新拉取详情与权益，避免显示上一门课的状态
watch(() => [route.params.id, route.query.couponId], () => load())
</script>

<style scoped>
.kz-tip {
  margin-top: 8px;
  font-size: 12px;
  color: #909399;
}
.price-origin { margin-right: 8px; color: #98a2b3; text-decoration: line-through; }
.price-off { margin-left: 8px; color: #e11d48; font-weight: 600; }
</style>
