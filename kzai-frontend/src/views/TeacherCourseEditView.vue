<template>
  <div>
    <el-card shadow="never">
      <template #header>
        <div class="kz-card-header">
          <span>{{ isEdit ? '编辑课程' : '新建课程' }}</span>
          <el-button @click="$router.push('/teacher/courses')">返回列表</el-button>
        </div>
      </template>

      <el-alert
        v-if="detail && detail.editable === false"
        type="warning"
        :closable="false"
        title="课程处于待审核状态，审核完成前不可编辑"
        style="margin-bottom: 16px"
      />

      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px" style="max-width: 760px">
        <el-form-item label="课程名称" prop="name">
          <el-input v-model="form.name" maxlength="100" show-word-limit placeholder="请输入课程名称" />
        </el-form-item>
        <el-form-item label="课程分类" prop="category">
          <el-alert v-if="categoryError" type="error" :closable="false" style="margin-bottom: 8px">
            分类加载失败，请重试
            <el-button size="small" type="primary" link @click="loadCategories">重新加载</el-button>
          </el-alert>
          <el-cascader
            v-model="form.category"
            :options="categoryOptions"
            :props="{ checkStrictly: true, expandTrigger: 'hover' }"
            placeholder="请选择一/二级分类"
            clearable
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="课程类型" prop="courseType">
          <el-radio-group v-model="form.courseType">
            <el-radio :value="1">直播</el-radio>
            <el-radio :value="2">录播</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="价格（元）" prop="priceYuan">
          <el-input-number v-model="form.priceYuan" :min="0" :max="99999" :precision="2" :step="1" />
          <span class="kz-tip">0 表示免费；提交时按「分」入库，不使用浮点金额</span>
        </el-form-item>
        <el-form-item label="有效期（月）">
          <el-input-number v-model="form.validDuration" :min="0" :max="120" />
        </el-form-item>
        <el-form-item label="封面地址">
          <el-input v-model="form.coverUrl" placeholder="可到「资料」页上传封面后回填，也可留空" />
        </el-form-item>
        <el-form-item label="适用人群" prop="usePeople">
          <el-input v-model="form.usePeople" type="textarea" :rows="2" maxlength="500" show-word-limit />
        </el-form-item>
        <el-form-item label="课程简介" prop="courseIntroduce">
          <el-input v-model="form.courseIntroduce" type="textarea" :rows="3" maxlength="1000" show-word-limit />
        </el-form-item>
        <el-form-item label="课程详情" prop="courseDetail">
          <el-input v-model="form.courseDetail" type="textarea" :rows="5" maxlength="4000" show-word-limit />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" :disabled="!editable" @click="onSave">保存</el-button>
          <el-button @click="$router.push('/teacher/courses')">取消</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { teacherApi, categoryApi } from '../api'

const route = useRoute()
const router = useRouter()
const formRef = ref()
const saving = ref(false)
const detail = ref(null)
const categoryOptions = ref([])

const courseId = computed(() => (route.params.id ? String(route.params.id) : ''))
const isEdit = computed(() => Boolean(courseId.value))
const editable = computed(() => detail.value?.editable !== false)

const form = reactive({
  name: '',
  category: [],
  courseType: 2,
  priceYuan: 0,
  validDuration: 12,
  coverUrl: '',
  usePeople: '',
  courseIntroduce: '',
  courseDetail: ''
})

const rules = {
  name: [{ required: true, message: '请输入课程名称', trigger: 'blur' }],
  category: [
    {
      validator: (_r, value, callback) =>
        value && value.length ? callback() : callback(new Error('请选择课程分类')),
      trigger: 'change'
    }
  ],
  courseType: [{ required: true, message: '请选择课程类型', trigger: 'change' }],
  priceYuan: [{ required: true, message: '请填写价格', trigger: 'blur' }]
}

const categoryError = ref(false)
async function loadCategories() {
  categoryError.value = false
  try {
    const tree = await categoryApi.tree()
    categoryOptions.value = (tree || []).map((lv1) => ({
      value: lv1.id,
      label: lv1.name,
      children: (lv1.children || []).map((lv2) => ({ value: lv2.id, label: lv2.name }))
    }))
  } catch (e) {
    // M15.45：分类加载失败——明确提示+允许重试（不静默；教师身份不再请求 /admin/categories）
    categoryError.value = true
    ElMessage.error('分类加载失败，请重试')
  }
}

async function loadDetail() {
  detail.value = await teacherApi.detail(courseId.value)
  const d = detail.value
  form.name = d.name || ''
  form.category = d.secondCateId ? [d.firstCateId, d.secondCateId] : [d.firstCateId]
  form.courseType = d.courseType || 2
  form.priceYuan = Number(d.priceYuan || 0)
  form.validDuration = d.validDuration || 0
  form.coverUrl = d.coverUrl || ''
  form.usePeople = d.usePeople || ''
  form.courseIntroduce = d.courseIntroduce || ''
  form.courseDetail = d.courseDetail || ''
}

async function onSave() {
  await formRef.value.validate()
  const payload = {
    name: form.name.trim(),
    firstCateId: form.category[0],
    secondCateId: form.category[1] || null,
    courseType: form.courseType,
    // 以「元」字符串提交，由服务端用 BigDecimal 精确换算成分（避免前端浮点乘法误差）
    priceYuan: String(Number(form.priceYuan || 0).toFixed(2)),
    validDuration: form.validDuration,
    coverUrl: form.coverUrl || null,
    usePeople: form.usePeople,
    courseIntroduce: form.courseIntroduce,
    courseDetail: form.courseDetail
  }
  saving.value = true
  try {
    if (isEdit.value) {
      await teacherApi.update(courseId.value, payload)
      ElMessage.success('课程已保存')
    } else {
      const id = await teacherApi.create(payload)
      ElMessage.success('课程已创建，保存为待上架状态')
      router.push(`/teacher/courses/${id}/edit`)
      return
    }
    await loadDetail()
  } catch (e) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  await loadCategories()
  if (isEdit.value) {
    try {
      await loadDetail()
    } catch (e) {
      ElMessage.error(e?.message || '课程不存在或无权访问')
      router.push('/teacher/courses')
    }
  }
})
</script>

<style scoped>
.kz-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.kz-tip {
  margin-left: 12px;
  font-size: 12px;
  color: #909399;
}
</style>
