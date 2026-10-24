<template>
  <div>
    <el-card shadow="never">
      <template #header>
        <div class="kz-card-header">
          <span>章节管理 · {{ courseName }}</span>
          <div>
            <el-button @click="$router.push('/teacher/courses')">返回列表</el-button>
            <el-button type="primary" @click="openDialog()">新增章节</el-button>
          </div>
        </div>
      </template>

      <el-table :data="chapters" v-loading="loading" border stripe row-key="id">
        <el-table-column prop="chapterOrder" label="顺序" width="80" />
        <el-table-column prop="name" label="章节名称" min-width="180" />
        <el-table-column prop="description" label="章节描述" min-width="240" show-overflow-tooltip />
        <el-table-column label="允许试看" width="130">
          <template #default="{ row }">
            <el-switch
              v-model="row.freePreview"
              active-text="可试看"
              inactive-text="不可"
              inline-prompt
              @change="(val) => onPreviewChange(row, val)"
            />
          </template>
        </el-table-column>
        <el-table-column label="关联资源" min-width="160">
          <template #default="{ row }">
            <span v-if="!row.resources || !row.resources.length">-</span>
            <el-tag v-for="r in row.resources" :key="r.id" size="small" style="margin-right: 4px">
              {{ r.kindText }}：{{ r.fileName }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="排序" width="140">
          <template #default="{ row, $index }">
            <el-button link :disabled="$index === 0" @click="move($index, -1)">上移</el-button>
            <el-button link :disabled="$index === chapters.length - 1" @click="move($index, 1)">下移</el-button>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
            <el-button link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialog" :title="editing ? '编辑章节' : '新增章节'" width="480px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="章节名称" prop="name">
          <el-input v-model="form.name" maxlength="100" show-word-limit />
        </el-form-item>
        <el-form-item label="章节描述">
          <el-input v-model="form.description" type="textarea" :rows="3" maxlength="255" show-word-limit />
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
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { chapterApi, teacherApi } from '../api'

const route = useRoute()
const courseId = String(route.params.id)
const loading = ref(false)
const saving = ref(false)
const chapters = ref([])
const courseName = ref('')
const dialog = ref(false)
const editing = ref(null)
const formRef = ref()
const form = reactive({ name: '', description: '' })

const rules = {
  name: [{ required: true, message: '请输入章节名称', trigger: 'blur' }]
}

async function load() {
  loading.value = true
  try {
    chapters.value = await chapterApi.list(courseId)
  } finally {
    loading.value = false
  }
}

function openDialog(row) {
  editing.value = row || null
  form.name = row ? row.name : ''
  form.description = row ? row.description || '' : ''
  dialog.value = true
}

async function onSave() {
  await formRef.value.validate()
  saving.value = true
  try {
    if (editing.value) {
      await chapterApi.update(editing.value.id, { name: form.name.trim(), description: form.description })
      ElMessage.success('章节已更新')
    } else {
      await chapterApi.add(courseId, { name: form.name.trim(), description: form.description })
      ElMessage.success('章节已新增')
    }
    dialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

/**
 * M5：试看开关（教师显式设置并落库）。
 * 未购学员只能访问明确标记为可试看的章节，前端不做"第一章永远免费"的推断。
 */
async function onPreviewChange(row, val) {
  try {
    await chapterApi.preview(String(row.id), { freePreview: val === true })
    ElMessage.success(val ? '已设为可试看，未购学员可访问本章' : '已取消试看')
  } catch (e) {
    row.freePreview = !val
    ElMessage.error(e?.message || '试看设置失败')
  }
}

async function onDelete(row) {
  try {
    await ElMessageBox.confirm(`确认删除章节「${row.name}」？`, '删除章节', { type: 'warning' })
  } catch {
    return
  }
  try {
    await chapterApi.remove(row.id)
    ElMessage.success('章节已删除（逻辑删除）')
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '删除失败')
  }
}

/** 上移/下移后立即持久化顺序 */
async function move(index, delta) {
  const list = chapters.value.slice()
  const target = index + delta
  if (target < 0 || target >= list.length) return
  const temp = list[index]
  list[index] = list[target]
  list[target] = temp
  try {
    await chapterApi.reorder(courseId, list.map((c) => c.id))
    await load()
    ElMessage.success('顺序已保存')
  } catch (e) {
    ElMessage.error(e?.message || '排序保存失败')
  }
}

onMounted(async () => {
  await load()
  try {
    const detail = await teacherApi.detail(courseId)
    courseName.value = detail.name
  } catch {
    courseName.value = ''
  }
})
</script>

<style scoped>
.kz-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
</style>
