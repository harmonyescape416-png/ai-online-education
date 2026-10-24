<template>
  <div>
    <el-card shadow="never">
      <template #header>
        <div class="kz-card-header">
          <span>课程审核</span>
        </div>
      </template>

      <el-form :inline="true" :model="query" @submit.prevent>
        <el-form-item label="课程名称">
          <el-input v-model="query.name" placeholder="支持模糊查询" clearable style="width: 180px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="reload">查询</el-button>
          <el-button @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="records" v-loading="loading" border stripe>
        <el-table-column prop="name" label="课程名称" min-width="180" />
        <el-table-column prop="categoryName" label="分类" min-width="140" />
        <el-table-column label="价格" width="110">
          <template #default="{ row }">
            <span>{{ row.free === 1 ? '免费' : `¥${row.priceYuan}` }}</span>
          </template>
        </el-table-column>
        <el-table-column label="课程状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 2 ? 'success' : 'info'">{{ row.statusText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="审核状态" width="110">
          <template #default="{ row }">
            <el-tag :type="row.auditStatus === 3 ? 'danger' : 'warning'" effect="plain">
              {{ row.auditStatusText }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="sectionNum" label="章节数" width="90" />
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="onPreview(row)">预览</el-button>
            <el-button link type="success" @click="onApprove(row)">通过</el-button>
            <el-button link type="danger" @click="onHandle(row, 'reject')">驳回</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="kz-pager"
        background
        layout="total, prev, pager, next"
        :total="total"
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        @current-change="load"
      />
    </el-card>

    <el-drawer v-model="drawer" title="课程审核预览" size="60%">
      <div v-if="preview">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="课程名称">{{ preview.name }}</el-descriptions-item>
          <el-descriptions-item label="分类">{{ preview.categoryName }}</el-descriptions-item>
          <el-descriptions-item label="类型">
            {{ preview.courseType === 1 ? '直播' : '录播' }}
          </el-descriptions-item>
          <el-descriptions-item label="价格">
            {{ preview.free === 1 ? '免费' : `¥${preview.priceYuan}` }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">{{ preview.statusText }}</el-descriptions-item>
          <el-descriptions-item label="审核状态">{{ preview.auditStatusText }}</el-descriptions-item>
          <el-descriptions-item label="适用人群">{{ preview.usePeople || '-' }}</el-descriptions-item>
          <el-descriptions-item label="有效期">{{ preview.validDuration || '-' }} 个月</el-descriptions-item>
          <el-descriptions-item label="课程简介" :span="2">
            {{ preview.courseIntroduce || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="课程详情" :span="2">
            {{ preview.courseDetail || '-' }}
          </el-descriptions-item>
        </el-descriptions>

        <h4 style="margin: 16px 0 8px">章节（{{ preview.chapters?.length || 0 }}）</h4>
        <el-table :data="preview.chapters || []" border size="small">
          <el-table-column prop="chapterOrder" label="#" width="60" />
          <el-table-column prop="name" label="章节名称" min-width="160" />
          <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
        </el-table>

        <h4 style="margin: 16px 0 8px">资料（{{ preview.resources?.length || 0 }}）</h4>
        <el-table :data="preview.resources || []" border size="small">
          <el-table-column prop="fileName" label="文件名" min-width="180" />
          <el-table-column prop="kindText" label="类型" width="110" />
          <el-table-column prop="fileSizeText" label="大小" width="100" />
        </el-table>

        <div style="margin-top: 20px">
          <el-button type="success" @click="onApprove(preview)">审核通过</el-button>
          <el-button type="danger" @click="onHandle(preview, 'reject')">驳回</el-button>
          <el-button
            v-if="preview.status === 2"
            type="warning"
            @click="onHandle(preview, 'offline')"
          >
            强制下架
          </el-button>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { auditApi } from '../api'

const loading = ref(false)
const records = ref([])
const total = ref(0)
const drawer = ref(false)
const preview = ref(null)
const query = reactive({ pageNum: 1, pageSize: 10, name: '' })

async function load() {
  loading.value = true
  try {
    const data = await auditApi.pendingList({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      name: query.name || undefined
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
  query.pageNum = 1
  load()
}

async function onPreview(row) {
  preview.value = await auditApi.preview(row.id)
  drawer.value = true
}

async function onApprove(row) {
  try {
    await ElMessageBox.confirm(`确认通过「${row.name}」的审核？通过后课程将上架，学员可见。`, '审核通过', {
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await auditApi.approve(row.id)
    ElMessage.success('审核已通过，课程已上架')
    drawer.value = false
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '操作失败')
  }
}

async function onHandle(row, type) {
  const title = type === 'reject' ? '驳回课程' : '强制下架'
  let reason = ''
  try {
    const res = await ElMessageBox.prompt(
      type === 'reject' ? '请填写驳回原因' : '请填写下架原因',
      title,
      {
        inputType: 'textarea',
        inputPlaceholder: '原因将展示给教师',
        inputValidator: (v) => (v && v.trim().length > 0) || '原因不能为空'
      }
    )
    reason = res.value.trim()
  } catch {
    return
  }
  try {
    if (type === 'reject') {
      await auditApi.reject(row.id, reason)
      ElMessage.success('已驳回，课程保持待上架')
    } else {
      await auditApi.offline(row.id, reason)
      ElMessage.success('已强制下架，学员端不再可见')
    }
    drawer.value = false
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '操作失败')
  }
}

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
</style>
