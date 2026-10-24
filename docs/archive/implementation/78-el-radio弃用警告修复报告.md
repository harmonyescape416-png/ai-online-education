# 78-el-radio label 弃用警告修复报告

> 执行依据：用户指令（「我的订单」控制台连续 7 条 el-radio label 弃用警告；「全部」在无障碍树 value=on；只改点名文件）。**完成后暂停等待验收。**

## 一、修复内容

| 文件 | 修复 |
|---|---|
| **OrderListView.vue** | **7 处** el-radio-button `:label` → `:value`（文案留标签内）——**「全部」保持 `:value="null"`**（原生 radio 不再变 on） |
| NotificationCenterView.vue | 检查——已是 `:value`（无需修改） |
| AdminDashboardView.vue | 检查——已是 `:value`（无需修改） |

**全项目搜索**：仅 OrderListView 存在 `:label` 用法（另两文件已合规）——**一并核查完毕**。

## 二、验收实测（agent-browser）

| 项 | 结果 |
|---|---|
| **radio 渲染** | 7 个 radio ✅ |
| **「全部」无障碍树 value** | **anyOn=false**（原生 radio 不再 on）✅ |
| **筛选功能** | 待支付/已支付/全部 切换正常（各 10 行）✅ |
| **控制台弃用警告** | **0 条**（干净导航后）✅ |

## 三、回归

lint 0 errors / npm test 11 passed / test:e2e 9 通过 / build ✅
