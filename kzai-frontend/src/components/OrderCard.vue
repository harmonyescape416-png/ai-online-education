<template>
  <div class="order-card">
    <div class="order-card__head">
      <span class="order-card__title">待支付订单</span>
      <el-tag type="warning" size="small" effect="dark">{{ statusText }}</el-tag>
    </div>
    <div class="order-card__no">订单号：<span class="mono">{{ orderId }}</span></div>
    <div v-for="item in items" :key="item.courseId" class="order-card__item">
      <span class="order-card__name">{{ item.name }}</span>
      <span class="order-card__price">¥{{ item.priceYuan || '0.00' }}</span>
    </div>
    <div class="order-card__total">
      合计：<b class="order-card__total-num">¥{{ totalAmountYuan }}</b>
      <span class="order-card__cents">（{{ totalAmount }} 分）</span>
    </div>
    <div class="order-card__note">订单尚未支付，请在订单详情页完成支付；本卡片不表示购买成功。</div>
    <el-button size="small" type="warning" plain class="order-card__btn" @click="goOrder">
      查看订单
    </el-button>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { safeText } from '../utils/safeUrl'

const props = defineProps({
  /** 1003 ORDER_CARD 载荷（全部来自交易服务真实返回） */
  payload: { type: Object, required: true }
})

const router = useRouter()

// 订单 ID 按字符串处理（后端全局 Long→字符串），仅数字校验
const orderId = computed(() => {
  const id = props.payload?.orderId
  return /^\d{1,20}$/.test(String(id)) ? String(id) : ''
})
const statusText = computed(() => safeText(props.payload?.statusText, 10) || '待支付')
const totalAmount = computed(() => Number(props.payload?.totalAmount) || 0)
const totalAmountYuan = computed(() => safeText(props.payload?.totalAmountYuan, 12) || '0.00')
const items = computed(() => {
  const raw = Array.isArray(props.payload?.items) ? props.payload.items : []
  return raw.slice(0, 20).map((it) => ({
    courseId: String(it?.courseId ?? ''),
    name: safeText(it?.name, 100) || '课程',
    priceYuan: safeText(it?.priceYuan, 12) || '0.00'
  }))
})

const goOrder = () => {
  if (!orderId.value) return
  router.push({ name: 'order-detail', params: { id: orderId.value } })
}
</script>

<style scoped>
.order-card {
  border: 1px solid #f3d19e;
  background: #fdf6ec;
  border-radius: 8px;
  padding: 10px 12px;
  margin-top: 8px;
  max-width: 460px;
  font-size: 13px;
}
.order-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.order-card__title {
  font-weight: 600;
}
.order-card__no {
  color: #667085;
  margin-top: 4px;
}
.order-card__item {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 4px 0;
  border-bottom: 1px dashed #f3d19e;
}
.order-card__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.order-card__price {
  color: #f56c6c;
  flex-shrink: 0;
}
.order-card__total {
  margin-top: 6px;
}
.order-card__total-num {
  color: #f56c6c;
  font-size: 15px;
}
.order-card__cents {
  color: #98a2b3;
  font-size: 12px;
}
.order-card__note {
  color: #b88230;
  font-size: 12px;
  margin-top: 4px;
}
.order-card__btn {
  margin-top: 6px;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
</style>
