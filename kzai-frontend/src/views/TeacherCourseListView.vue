<template>
  <div>
    <el-card shadow="never">
      <template #header>
        <div class="kz-card-header">
          <span>我的课程</span>
          <el-button type="primary" @click="$router.push('/teacher/courses/new')">新建课程</el-button>
        </div>
      </template>

      <el-form :inline="true" :model="query" @submit.prevent>
        <el-form-item label="课程名称">
          <el-input v-model="query.name" placeholder="支持模糊查询" clearable style="width: 180px" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 140px">
            <el-option label="待上架" :value="1" />
            <el-option label="已上架" :value="2" />
            <el-option label="已下架" :value="3" />
            <el-option label="已完结" :value="4" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="reload">查询</el-button>
          <el-button @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="records" v-loading="loading" border stripe>
        <el-table-column prop="name" label="课程名称" min-width="180" />
        <el-table-column prop="categoryName" label="分类" min-width="140" />
        <el-table-column label="价格" width="120">
          <template #default="{ row }">
            <span>{{ row.free === 1 ? '免费' : `¥${row.priceYuan}` }}</span>
          </template>
        </el-table-column>
        <el-table-column label="课程状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)">{{ row.statusText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="审核状态" width="130">
          <template #default="{ row }">
            <el-tag :type="auditTag(row.auditStatus)" effect="plain">
              {{ row.auditStatusText }}
            </el-tag>
            <div v-if="row.auditStatus === 3 && row.auditReason" class="kz-reason">
              原因：{{ row.auditReason }}
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="sectionNum" label="章节数" width="90" />
        <el-table-column label="操作" width="320" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="goEdit(row)">编辑</el-button>
            <el-button link type="primary" @click="goChapters(row)">章节</el-button>
            <el-button link type="primary" @click="goResources(row)">资料</el-button>
            <el-button
              link
              type="success"
              :disabled="row.auditStatus === 1 || row.status !== 1"
              @click="onSubmit(row)"
            >
              提交审核
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="kz-pager"
        background
        layout="total, sizes, prev, pager, next"
        :total="total"
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :page-sizes="[10, 20, 50]"
        @current-change="load"
        @size-change="reload"
      />
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { teacherApi } from '../api'

const router = useRouter()
const loading = ref(false)
const records = ref([])
const total = ref(0)
const query = reactive({ pageNum: 1, pageSize: 10, name: '', status: null })

async function load() {
  loading.value = true
  try {
    const data = await teacherApi.list({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      name: query.name || undefined,
      status: query.status || undefined
    })
    records.value = data.records || []
    total.value = Number(data.total) || 0
  } finally {
    loading.value = false
  }
}

function reload() {
  query.pageNum = 1
  return load()
}

function resetQuery() {
  query.name = ''
  query.status = null
  query.pageNum = 1
  load()
}

async function onSubmit(row) {
  try {
    await ElMessageBox.confirm(`确认提交「${row.name}」审核？提交后审核完成前不可编辑。`, '提交审核', {
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await teacherApi.submit(row.id)
    ElMessage.success('已提交审核，请等待管理员处理')
    load()
  } catch (e) {
    ElMessage.error(e?.message || '提交失败')
  }
}

const goEdit = (row) => router.push(`/teacher/courses/${row.id}/edit`)
const goChapters = (row) => router.push(`/teacher/courses/${row.id}/chapters`)
const goResources = (row) => router.push(`/teacher/courses/${row.id}/resources`)

const statusTag = (s) => (s === 2 ? 'success' : s === 3 ? 'info' : s === 4 ? 'warning' : '')
const auditTag = (s) => (s === 2 ? 'success' : s === 3 ? 'danger' : s === 4 ? 'warning' : 'info')

onMounted(load)
</script>

<style scoped>
.kz-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.kz-pager {
  margin-top: 16px;
  justify-content: flex-end;
}
.kz-reason {
  margin-top: 4px;
  font-size: 12px;
  color: #f56c6c;
}
</style>
