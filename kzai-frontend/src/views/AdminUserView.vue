<template>
  <div>
    <h2 class="kz-page-title">用户管理</h2>

    <el-card shadow="never" style="margin-bottom: 16px">
      <el-form :inline="true" :model="query">
        <el-form-item label="账号">
          <el-input v-model="query.username" clearable placeholder="登录账号" style="width: 150px" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="query.cellPhone" clearable placeholder="手机号" style="width: 150px" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="query.type" clearable placeholder="全部" style="width: 130px">
            <el-option label="员工/管理员" :value="1" />
            <el-option label="学员" :value="2" />
            <el-option label="教师" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="query.roleCode" clearable placeholder="全部" style="width: 120px">
            <el-option v-for="r in ROLE_OPTIONS" :key="r.value" :label="r.label" :value="r.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部" style="width: 100px">
            <el-option label="正常" :value="1" />
            <el-option label="禁用" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="reload">查询</el-button>
          <el-button @click="reset">重置</el-button>
          <el-button type="success" @click="openCreate">新增账号</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column show-overflow-tooltip prop="id" label="ID" width="180" />
      <el-table-column prop="username" label="登录账号" width="140" />
      <el-table-column prop="cellPhone" label="手机号" width="140" />
      <el-table-column label="类型" width="110">
        <template #default="{ row }">{{ row.typeName }}</template>
      </el-table-column>
      <el-table-column label="角色" width="180">
        <template #default="{ row }">
          <el-tag v-for="r in row.roleCodes" :key="r" size="small" style="margin-right: 4px">
            {{ roleLabel(r) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" min-width="120">
        <template #default="{ row }">
          <div style="white-space: nowrap">
          <el-switch
            v-model="row.status"
            :active-value="1"
            :inactive-value="0"
            active-text="启用"
            inactive-text="禁用"
            @change="(val) => onToggleStatus(row, val)"
          />
          </div>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="180">
          <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
        </el-table-column>
      <el-table-column label="操作" width="280" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="primary" @click="openRoles(row)">角色</el-button>
          <el-button link type="warning" @click="openResetPwd(row)">重置密码</el-button>
          <el-button link type="danger" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div style="margin-top: 16px; display: flex; justify-content: flex-end">
      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        @current-change="load"
        @size-change="reload"
      />
    </div>

    <!-- 新增 / 编辑 -->
    <el-dialog v-model="editVisible" :title="editForm.id ? '编辑用户' : '新增账号'" width="420px">
      <el-form :model="editForm" label-width="90px">
        <el-form-item label="登录账号">
          <el-input v-model="editForm.username" :disabled="Boolean(editForm.id)" placeholder="登录账号" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="editForm.cellPhone" placeholder="手机号" />
        </el-form-item>
        <template v-if="!editForm.id">
          <el-form-item label="用户类型">
            <el-select v-model="editForm.type" style="width: 100%">
              <el-option label="员工/管理员" :value="1" />
              <el-option label="学员" :value="2" />
              <el-option label="教师" :value="3" />
            </el-select>
          </el-form-item>
          <el-form-item label="初始密码">
            <el-input v-model="editForm.password" placeholder="留空则使用默认初始密码" show-password />
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 角色分配 -->
    <el-dialog v-model="roleVisible" title="分配角色" width="420px">
      <el-checkbox-group v-model="selectedRoles">
        <el-checkbox v-for="r in ROLE_OPTIONS" :key="r.value" :label="r.value">{{ r.label }}</el-checkbox>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="roleVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitRoles">保存</el-button>
      </template>
    </el-dialog>

    <!-- 重置密码 -->
    <el-dialog v-model="pwdVisible" title="重置密码" width="420px">
      <el-form label-width="90px">
        <el-form-item label="目标账号">{{ pwdTarget.username }}</el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="newPassword" placeholder="6~32 位" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pwdVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitResetPwd">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { fmtTime } from '../utils/format'
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '../api'

const ROLE_OPTIONS = [
  { value: 'ADMIN', label: '管理员' },
  { value: 'STAFF', label: '员工' },
  { value: 'TEACHER', label: '教师' },
  { value: 'STUDENT', label: '学员' }
]

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const submitting = ref(false)

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  username: '',
  cellPhone: '',
  type: null,
  roleCode: null,
  status: null
})

const editVisible = ref(false)
const editForm = reactive({ id: null, username: '', cellPhone: '', type: 2, password: '' })

const roleVisible = ref(false)
const selectedRoles = ref([])
const roleTarget = ref(null)

const pwdVisible = ref(false)
const pwdTarget = ref({})
const newPassword = ref('')

async function load() {
  loading.value = true
  try {
    const page = await adminApi.list({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      username: query.username || undefined,
      cellPhone: query.cellPhone || undefined,
      type: query.type ?? undefined,
      roleCode: query.roleCode || undefined,
      status: query.status ?? undefined
    })
    rows.value = page.records
    total.value = Number(page.total) || 0
  } finally {
    loading.value = false
  }
}

function reload() {
  query.pageNum = 1
  load()
}

function reset() {
  Object.assign(query, {
    username: '',
    cellPhone: '',
    type: null,
    roleCode: null,
    status: null
  })
  reload()
}

function roleLabel(code) {
  return ROLE_OPTIONS.find((r) => r.value === code)?.label || code
}

function openCreate() {
  Object.assign(editForm, { id: null, username: '', cellPhone: '', type: 2, password: '' })
  editVisible.value = true
}

function openEdit(row) {
  Object.assign(editForm, { id: row.id, username: row.username, cellPhone: row.cellPhone || '' })
  editVisible.value = true
}

async function submitEdit() {
  submitting.value = true
  try {
    if (editForm.id) {
      await adminApi.update(editForm.id, { cellPhone: editForm.cellPhone })
      ElMessage.success('已保存')
    } else {
      await adminApi.create({
        username: editForm.username,
        cellPhone: editForm.cellPhone,
        type: editForm.type,
        password: editForm.password || undefined
      })
      ElMessage.success('账号已创建')
    }
    editVisible.value = false
    reload()
  } finally {
    submitting.value = false
  }
}

function openRoles(row) {
  roleTarget.value = row
  selectedRoles.value = [...(row.roleCodes || [])]
  roleVisible.value = true
}

async function submitRoles() {
  if (!selectedRoles.value.length) {
    ElMessage.warning('至少选择一个角色')
    return
  }
  submitting.value = true
  try {
    await adminApi.assignRoles(roleTarget.value.id, selectedRoles.value)
    ElMessage.success('角色已更新')
    roleVisible.value = false
    reload()
  } finally {
    submitting.value = false
  }
}

function openResetPwd(row) {
  pwdTarget.value = row
  newPassword.value = ''
  pwdVisible.value = true
}

async function submitResetPwd() {
  if (newPassword.value.length < 6) {
    ElMessage.warning('密码至少 6 位')
    return
  }
  submitting.value = true
  try {
    await adminApi.resetPassword(pwdTarget.value.id, newPassword.value)
    ElMessage.success('密码已重置，该用户需重新登录')
    pwdVisible.value = false
  } finally {
    submitting.value = false
  }
}

async function onToggleStatus(row, val) {
  try {
    await adminApi.updateStatus(row.id, val)
    ElMessage.success(val === 1 ? '账号已启用' : '账号已禁用，其登录状态已失效')
    load()
  } catch {
    load()
  }
}

async function onDelete(row) {
  await ElMessageBox.confirm(`确认删除账号「${row.username}」？`, '危险操作', {
    type: 'warning',
    confirmButtonText: '删除',
    cancelButtonText: '取消'
  })
  await adminApi.remove(row.id)
  ElMessage.success('已删除')
  reload()
}

onMounted(load)
</script>
