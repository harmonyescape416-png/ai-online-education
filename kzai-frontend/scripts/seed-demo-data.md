# 测试数据隔离与演示数据准备方案（不直接删除/覆盖现有数据）

## 隔离原则
- E2E 使用随机手机号注册（139XXXXXXXX），数据打 unique 标识（requestId=e2e-*）
- 测试库 course_stack_ai_test（结构镜像业务库）供自动化测试；业务库仅人工演示

## 演示数据脚本（规划——待实施）
- scripts/seed-demo.sql：插入带「演示」前缀的课程/问题（creater=2 teacher01 名下）
- scripts/cleanup-e2e.sql：按标识清理 E2E 数据（不触碰业务数据）
