<template>
  <div>
    <el-card shadow="never">
      <template #header>
        <div class="kz-card-header">
          <span>课程分类管理（两级）</span>
          <el-button type="primary" @click="openDialog(null, null)">新增一级分类</el-button>
        </div>
      </template>

      <el-table :data="tree" v-loading="loading" border row-key="id" default-expand-all>
        <el-table-column prop="name" label="分类名称" min-width="200" />
        <el-table-column label="层级" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.level === 1 ? 'success' : 'info'">
              {{ row.level === 1 ? '一级' : '二级' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="sort" label="排序" width="90" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">
              {{ row.status === 1 ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="300" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDialog(row, null)">编辑</el-button>
            <el-button
              v-if="row.level === 1"
              link
              type="primary"
              @click="openDialog(null, row)"
            >
              新增子分类
            </el-button>
            <el-button link :type="row.status === 1 ? 'warning' : 'success'" @click="toggleStatus(row)">
              {{ row.status === 1 ? '禁用' : '启用' }}
            </el-button>
            <el-button link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialog" :title="dialogTitle" width="460px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="分类名称" prop="name">
          <el-input v-model="form.name" maxlength="30" show-word-limit />
        </el-form-item>
        <el-form-item label="父分类">
          <el-select v-model="form.parentId" :disabled="Boolean(editing)" style="width: 100%">
            <el-option label="（一级分类）" :value="0" />
            <el-option v-for="c in levelOne" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sort" :min="0" :max="999" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">禁用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { categoryApi } from '../api'

const loading = ref(false)
const saving = ref(false)
const tree = ref([])
const dialog = ref(false)
const editing = ref(null)
const formRef = ref()
const form = reactive({ name: '', parentId: 0, sort: 100, status: 1 })

const rules = {
  name: [{ required: true, message: '请输入分类名称', trigger: 'blur' }]
}

const levelOne = computed(() => tree.value.filter((c) => c.level === 1))
const dialogTitle = computed(() =>
  editing.value ? '编辑分类' : form.parentId ? '新增二级分类' : '新增一级分类'
)

async function load() {
  loading.value = true
  try {
    tree.value = await categoryApi.adminTree()
  } finally {
    loading.value = false
  }
}

function openDialog(row, parent) {
  editing.value = row || null
  form.name = row ? row.name : ''
  form.parentId = row ? row.parentId || 0 : parent ? parent.id : 0
  form.sort = row ? row.sort || 100 : 100
  form.status = row ? row.status : 1
  dialog.value = true
}

async function onSave() {
  await formRef.value.validate()
  saving.value = true
  try {
    const payload = { name: form.name.trim(), parentId: form.parentId, sort: form.sort, status: form.status }
    if (editing.value) {
      await categoryApi.update(editing.value.id, payload)
      ElMessage.success('分类已更新')
    } else {
      await categoryApi.create(payload)
      ElMessage.success('分类已创建')
    }
    dialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function toggleStatus(row) {
  const next = row.status === 1 ? 0 : 1
  try {
    await categoryApi.updateStatus(row.id, next)
    ElMessage.success(next === 1 ? '已启用，学员端可见' : '已禁用，学员端不再展示')
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '操作失败')
  }
}

async function onDelete(row) {
  try {
    await ElMessageBox.confirm(`确认删除分类「${row.name}」？`, '删除分类', { type: 'warning' })
  } catch {
    return
  }
  try {
    await categoryApi.remove(row.id)
    ElMessage.success('分类已删除')
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '删除失败')
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
</style>
