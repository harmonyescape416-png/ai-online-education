<template>
  <div>
    <h2 class="kz-page-title">个人中心</h2>

    <el-row :gutter="20">
      <el-col :span="14">
        <el-card shadow="never">
          <template #header>
            <span>基本资料</span>
          </template>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="登录账号">{{ store.profile?.username }}</el-descriptions-item>
            <el-descriptions-item label="角色">{{ store.roleName }}</el-descriptions-item>
            <el-descriptions-item label="权限">
              <el-tag v-for="r in store.roles" :key="r" size="small" style="margin-right: 4px">{{ roleLabel(r) }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="手机号">{{ store.profile?.cellPhone || '-' }}</el-descriptions-item>
            <el-descriptions-item label="账号状态">
              {{ store.profile?.status === 1 ? '正常' : '禁用' }}
            </el-descriptions-item>
            <el-descriptions-item label="注册时间">{{ fmtTime(store.profile?.createTime) }}</el-descriptions-item>
          </el-descriptions>

          <el-form :model="profileForm" label-width="90px" style="margin-top: 16px">
            <!-- M15.15 方案 A：昵称/头像（扩展表 user_profile_ext，批准实施） -->
            <el-form-item label="头像">
              <div class="avatar-row">
                <img v-if="avatarPreview || store.profile?.avatarUrl" :src="avatarPreview || withApiBase(store.profile?.avatarUrl)"
                     class="avatar-img" alt="头像" />
                <div v-else class="avatar-img avatar-placeholder">{{ (store.profile?.username || '?').slice(0,1).toUpperCase() }}</div>
                <input ref="avatarInput" type="file" accept=".jpg,.jpeg,.png,.webp" style="display:none"
                       @change="onAvatarChange" />
                <el-button size="small" :loading="uploadingAvatar" @click="avatarInput?.click()">上传头像</el-button>
                <span class="kz-meta">支持 jpg/png/webp，≤2MB</span>
              </div>
            </el-form-item>
            <el-form-item label="昵称">
              <el-input v-model="profileForm.nickname" maxlength="30" show-word-limit placeholder="昵称（最长 30 字）" aria-label="昵称"
                        style="width: 240px" />
            </el-form-item>
            <!-- M15.17：手机号修改（新号+短信验证码确认；占用拒绝「该号码已注册」） -->
            <el-form-item label="手机号">
              <div class="phone-change-row">
                <el-input v-model="phoneChange.newPhone" placeholder="新手机号" maxlength="11" style="width: 180px" clearable />
                <el-input v-model="phoneChange.smsCode" placeholder="短信验证码" maxlength="6" style="width: 120px; margin-left: 8px" />
                <el-button size="small" :disabled="phoneCountdown > 0 || !phoneChange.newPhone || phoneChange.newPhone === (store.profile?.cellPhone || '')"
                           :loading="sendingPhoneCode" style="margin-left: 8px" @click="onSendPhoneCode">
                  {{ phoneCountdown > 0 ? phoneCountdown + 's' : '获取验证码' }}
                </el-button>
                <el-button size="small" type="primary" :disabled="!phoneChange.smsCode || !phoneChange.newPhone"
                           :loading="changingPhone" style="margin-left: 8px" @click="onChangePhone">
                  确认更换
                </el-button>
              </div>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="savingProfile" @click="saveProfile">保存资料</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <el-col :span="10">
        <el-card shadow="never">
          <template #header>
            <span>安全设置</span>
          </template>
          <el-form :model="pwdForm" :rules="pwdRules" ref="pwdFormRef" label-width="90px">
            <el-form-item label="原密码" prop="oldPassword">
              <el-input v-model="pwdForm.oldPassword" type="password" show-password />
            </el-form-item>
            <el-form-item label="新密码" prop="newPassword">
              <el-input v-model="pwdForm.newPassword" type="password" show-password @input="onPwdInput" />
              <!-- M15.14-R6 P2：强度提示（弱/中/强：长度+字符种类） -->
              <div v-if="pwdForm.newPassword" class="pwd-strength">
                <div class="pwd-strength__bar">
                  <span :class="'pwd-strength__seg is-' + pwdStrength.level" v-for="i in 3" :key="i" />
                </div>
                <span class="pwd-strength__text">{{ pwdStrength.text }}</span>
              </div>
            </el-form-item>
            <el-form-item label="确认密码" prop="confirmPassword">
              <el-input v-model="pwdForm.confirmPassword" type="password" show-password @blur="onConfirmBlur" />
              <div v-if="pwdForm.confirmPassword && pwdForm.confirmPassword !== pwdForm.newPassword"
                   class="pwd-mismatch">两次输入的密码不一致</div>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="changingPwd" @click="changePassword">修改密码</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { fmtTime, roleLabel, withApiBase } from '../utils/format'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../store/user'
import { profileApi, authApi } from '../api'

const store = useUserStore()
const router = useRouter()

const profileForm = reactive({ cellPhone: '', nickname: '' })
// M15.17：手机号修改（短信验证码确认；各角色一致）
const phoneChange = reactive({ newPhone: '', smsCode: '' })
const phoneCountdown = ref(0)
const sendingPhoneCode = ref(false)
const changingPhone = ref(false)
let phoneTimer = null

async function onSendPhoneCode() {
  if (sendingPhoneCode.value || phoneCountdown.value > 0) return
  if (!/^1[3-9]\d{9}$/.test(phoneChange.newPhone)) { ElMessage.error('请输入正确的新手机号'); return }
  if (phoneChange.newPhone === (store.profile?.cellPhone || '')) { ElMessage.error('新手机号与当前一致'); return }
  sendingPhoneCode.value = true
  try {
    // M15.18：MOCK 模式响应带 devCode——自动填入验证码框（REAL 模式无此字段，用户收短信）
    const d = await authApi.sendSms({ phone: phoneChange.newPhone, scene: 'CHANGE_PHONE' })
    if (d?.devCode) {
      phoneChange.smsCode = String(d.devCode)
      ElMessage.success('验证码已发送并自动填入（开发环境）')
    } else {
      ElMessage.success('验证码已发送至新手机号')
    }
    phoneCountdown.value = 60
    phoneTimer = setInterval(() => { phoneCountdown.value--; if (phoneCountdown.value <= 0 && phoneTimer) { clearInterval(phoneTimer); phoneTimer = null } }, 1000)
  } catch (e) {
    ElMessage.error(e?.message || '验证码发送失败')
  } finally {
    sendingPhoneCode.value = false
  }
}

async function onChangePhone() {
  if (changingPhone.value) return
  changingPhone.value = true
  try {
    await authApi.changePhone({ newPhone: phoneChange.newPhone, smsCode: phoneChange.smsCode })
    ElMessage.success('手机号已更新')
    phoneChange.newPhone = ''; phoneChange.smsCode = ''
    if (phoneTimer) { clearInterval(phoneTimer); phoneTimer = null }
    phoneCountdown.value = 0
    await store.fetchProfile()
  } catch (e) {
    ElMessage.error(e?.message || '手机号修改失败')
  } finally {
    changingPhone.value = false
  }
}
const avatarPreview = ref('')
const uploadingAvatar = ref(false)
const avatarInput = ref(null)

// M15.15 方案 A：回读扩展资料（昵称/头像）
function fillExt() {
  profileForm.nickname = store.profile?.nickname || ''
}

async function onAvatarChange(e) {
  const f = e.target.files && e.target.files[0]
  if (!f) return
  if (f.size > 2 * 1024 * 1024) { ElMessage.error('头像文件不能超过 2MB'); return }
  avatarPreview.value = URL.createObjectURL(f)
  uploadingAvatar.value = true
  try {
    const fd = new FormData()
    fd.append('file', f)
    // M15.15：不手动设置 multipart Content-Type——axios 对 FormData 自动生成含 boundary 的头
    const resp = await profileApi.uploadAvatar(fd)
    // M15.14-R11：上传响应直更 store（单点数据源即时生效）——顶栏与预览同步更新，
    // 不依赖 fetchProfile 时序；响应带版本参数破除覆盖式同 URL 的浏览器缓存
    const ext = resp?.data || resp
    if (ext?.avatarUrl) {
      store.profile.avatarUrl = ext.avatarUrl
      store.profile.avatarVersion = Date.now()
    }
    await store.fetchProfile(); fillExt()
    ElMessage.success('头像已更新')
  } catch (err) {
    // M15.15：失败状态回退——清除本地预览，回退显示已持久化的旧头像
    avatarPreview.value = ''
    URL.revokeObjectURL(avatarPreview.value)
    ElMessage.error(err?.message || '头像上传失败')
  } finally {
    uploadingAvatar.value = false
    e.target.value = ''
  }
}
const savingProfile = ref(false)

const pwdFormRef = ref()
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
const changingPwd = ref(false)

const pwdRules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 32, message: '长度 6~32 位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (rule, value, callback) =>
        value === pwdForm.newPassword ? callback() : callback(new Error('两次输入不一致')),
      trigger: 'blur'
    }
  ]
}

// M15.37：昵称校验——拒绝纯数字/纯空白/可见字符 <2（表单错误；不发请求）
function nicknameError(v) {
  const raw = String(v || '')
  const visible = raw.replace(/\s+/g, '')
  if (!visible) return '昵称不能为空白'
  if (/^\d+$/.test(visible)) return '昵称不能为纯数字'
  if (visible.length < 2) return '昵称至少需要 2 个可见字符'
  return ''
}

async function saveProfile() {
  const nErr = nicknameError(profileForm.nickname)
  if (nErr) {
    ElMessage.error(nErr)
    return
  }
  savingProfile.value = true
  try {
    await profileApi.updateProfile({ nickname: profileForm.nickname })
    // M15.15：响应直更 store（顶栏即时同步）；fetchProfile 兜底一致性
    store.profile.nickname = profileForm.nickname
    await store.fetchProfile(); fillExt()
    ElMessage.success('资料已更新')
  } catch (e) {
    // M15.14-R6：失败保留用户输入并明确提示
    ElMessage.error(e?.message || '资料保存失败，请稍后重试')
  } finally {
    savingProfile.value = false
  }
}

// M15.14-R6 P2：密码强度（长度+字符种类：弱/中/强）
const pwdStrength = computed(() => {
  const v = String(pwdForm.newPassword || '')
  if (!v) return { level: 'none', text: '' }
  let kinds = 0
  if (/[a-z]/.test(v)) kinds++
  if (/[A-Z]/.test(v)) kinds++
  if (/\d/.test(v)) kinds++
  if (/[^a-zA-Z0-9]/.test(v)) kinds++
  const score = (v.length >= 10 ? 1 : 0) + (v.length >= 6 && kinds >= 2 ? 1 : 0) + (kinds >= 3 ? 1 : 0)
  if (v.length < 6) return { level: 'weak', text: '弱：长度不足 6 位' }
  if (score >= 3) return { level: 'strong', text: '强' }
  if (score === 2) return { level: 'mid', text: '中' }
  return { level: 'weak', text: '弱：建议混合大小写、数字与符号' }
})
function onPwdInput() { /* 触发 pwdStrength computed 即可 */ }
function onConfirmBlur() {
  if (pwdFormRef.value && pwdForm.confirmPassword) pwdFormRef.value.validateField('confirmPassword')
}

async function changePassword() {
  await pwdFormRef.value.validate(async (valid) => {
    if (!valid) return
    changingPwd.value = true
    try {
      await profileApi.changePassword({
        oldPassword: pwdForm.oldPassword,
        newPassword: pwdForm.newPassword
      })
      ElMessage.success('密码已修改，请使用新密码重新登录')
      await store.logout()
      router.push('/login')
    } finally {
      changingPwd.value = false
    }
  })
}

onMounted(() => {
  // M15.15：统一回填——store.profile 在路由守卫预载（含昵称/头像扩展资料），
  // 保存成功后 fetchProfile+fillExt 也会同步；此处兜底处理直接刷新进入本页的场景
  profileForm.cellPhone = store.profile?.cellPhone || ''
  fillExt()
})
</script>

<style scoped>
/* M15.29：头像统一 96×96（原无尺寸样式导致原图 899×1259 撑破首屏）；横向行不撑破卡片 */
.avatar-row { display: flex; align-items: center; gap: 14px; min-width: 0; overflow: hidden; }
.avatar-img { width: 96px; height: 96px; object-fit: cover; border-radius: 50%; flex-shrink: 0; display: block; }
.avatar-placeholder { display: flex; align-items: center; justify-content: center; background: #eef2ff; color: #4F46E5; font-size: 32px; font-weight: 600; }
@media (max-width: 768px) { .avatar-row { flex-wrap: wrap; } }
</style>
