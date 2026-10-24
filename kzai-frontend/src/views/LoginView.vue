<template>
  <div class="login-wrapper">
    <el-card class="login-card" shadow="always">
      <h2 class="login-title">课栈智伴</h2>
      <p class="login-sub">AI 智能在线教育平台</p>
      <el-form ref="formRef" :model="form" :rules="rules" size="large" @keyup.enter="onSubmit">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="用户名或手机号" aria-label="用户名或手机号" :prefix-icon="User" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model="form.password" type="password" placeholder="密码" aria-label="密码" :prefix-icon="Lock" show-password />
        </el-form-item>
        <el-button type="primary" class="login-btn" :loading="store.loading" @click="onSubmit">登 录</el-button>
      </el-form>
      <div class="login-register-link">
        没有账号？<router-link to="/register" class="register-link">手机号注册</router-link>
      </div>

    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { useUserStore } from '../store/user'
import { ROLE_HOME, roleAllowed } from '../router/menuConfig'

const store = useUserStore()
const router = useRouter()
const route = useRoute()
const formRef = ref()

const form = reactive({ username: 'student01', password: 'student123' })
const rules = {
  username: [{ required: true, message: '请输入登录账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function onSubmit() {
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    const data = await store.login(form.username, form.password)
    ElMessage.success(`欢迎回来，${data.username}`)
    // M15.32：按角色进入工作区——无 redirect 用 ROLE_HOME；有 redirect 且角色允许才去 redirect
    // （避免教师/管理员被固定跳 /courses 触发守卫「无权限」误报）
    const redirect = route.query.redirect
    const roles = data.roles || store.roles
    if (redirect && typeof redirect === 'string' && roleAllowed(redirect, roles)) {
      router.push(redirect)
    } else {
      router.push(ROLE_HOME[roles?.[0]] || '/courses')
    }
  })
}
</script>

<style scoped>
.login-wrapper {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1a3f8f 0%, #2f56ad 100%);
}

.login-card {
  width: 380px;
  padding: 12px;
}

.login-title {
  margin: 8px 0 4px;
  text-align: center;
  font-size: 24px;
  color: #1a3f8f;
}

.login-sub {
  margin: 0 0 24px;
  text-align: center;
  color: #8a9099;
}

.login-btn {
  width: 100%;
}

.login-tip {
  margin-top: 16px;
  font-size: 12px;
  color: #8a9099;
  line-height: 1.6;
}
.login-register-link { text-align: center; margin-top: 10px; font-size: 13px; color: #64748b; }
.register-link { color: #4F46E5; text-decoration: none; }
</style>
