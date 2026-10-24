<template>
  <div>
    <h2 class="kz-page-title">优惠券</h2>
    <h3>领券中心</h3>
    <el-table :data="center" border>
      <el-table-column prop="name" label="名称" />
      <el-table-column prop="couponType" label="类型" width="140" />
      <el-table-column prop="remainCount" label="剩余" width="80" />
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-button size="small" type="primary" @click="claim(row)">领取</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div style="margin: 16px 0; display: flex; gap: 8px">
      <el-input v-model="code" placeholder="兑换码" style="max-width: 240px" />
      <el-button @click="redeem">兑换</el-button>
    </div>
    <h3>我的优惠券</h3>
    <el-table :data="mine" border>
      <el-table-column prop="name" label="名称" />
      <el-table-column prop="status" label="状态" width="100" />
      <el-table-column prop="validEnd" label="使用结束" min-width="180" />
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-button v-if="row.status === '可使用'" size="small" type="primary" @click="useCoupon(row)">去使用</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { couponApi } from '../api'

const router = useRouter()

const center = ref([])
const mine = ref([])
const code = ref('')

async function load() {
  center.value = await couponApi.center()
  mine.value = await couponApi.mine()
}

async function claim(row) {
  try {
    await couponApi.claim(row.id)
    await load()
  } catch (e) {
    if (!e?.notified) ElMessage.error(e?.message || '领取失败')
  }
}

function useCoupon(row) {
  if (row.status !== '可使用') return
  router.push({ path: '/courses', query: { couponId: row.id } })
}

async function redeem() {
  try {
    await couponApi.redeem(code.value.trim())
    code.value = ''
    await load()
  } catch (e) {
    if (!e?.notified) ElMessage.error(e?.message || '兑换失败')
  }
}

onMounted(load)
</script>
