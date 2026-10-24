# 运维手册（M15）

## 1. 服务状态检查
```bash
cd deploy
docker compose ps                      # 全部组件应为 healthy
docker compose logs --tail=100 gateway # 单服务日志
curl http://localhost:8080/api/auth/login -X POST -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}'   # 冒烟
```
非容器环境使用 `source scripts/dev-env.sh && bash scripts/run-services.sh status`，同时检查各 `logs/kzai-*.log`。
脚本会检测 HTTP 响应；`LISTEN` 只能证明端口打开，不能证明接口可用。

本地更新服务：先 `mvn package`，再执行 `bash scripts/run-services.sh restart learning`
或 `restart user`（也接受 `kzai-learning` 等完整名称）。未指定服务时操作全部服务。
启动时将 JAR 复制到 `.runtime/services/<service>/` 的独立快照，并以独立进程会话启动，
标准输出和错误追加到 `logs/<service>.log`。不要直接从正在被 Maven 改写的 `target` 归档启动。
运行期间不要删除正在使用的快照；停止服务并确认没有进程引用后，才可清理历史快照。
若本机 `java` 是会自行后台化的包装器，可通过 `KZAI_JAVA_BIN` 指定真实 Java 可执行文件。
启动探针对不存在的内部路径发无签名请求，正常返回 403；该请求不绕过内部鉴权。

## 2. 数据备份与恢复（必须演练过才算可用）
```bash
# 备份（逻辑备份；容器：docker compose exec mysql；原生：直接 mysqldump）
mysqldump -uroot -p --single-transaction --routines course_stack_ai > backup_$(date +%F).sql
# Redis：AOF 已开启（redis-data 卷）；冷备可 COPY 数据卷
# ES：索引可由 MySQL 权威数据全量重建（见 §5），无需单独备份

# 恢复演练（M15 已在隔离库验证「空库→全量迁移」路径；恢复=同路径+数据回灌）
mysql -uroot -p -e "CREATE DATABASE course_stack_ai_restored"
mysql -uroot -p course_stack_ai_restored < sql/01_schema.sql   # 先结构
mysql -uroot -p course_stack_ai_restored < backup_xxx.sql --force  # 再数据（结构已含则跳过 DDL 冲突）
# 验证：SELECT COUNT(*) FROM user/course/order 与备份前一致；最后改名切换
```
**恢复演练状态**：结构初始化路径已实测（隔离库）；带业务数据的备份回灌演练未执行（无 Docker 环境下的生产级备份样本）——上线前必须演练一次。

## 3. 数据库迁移
- 空库按 `sql/` 文件名字典序执行到 `17_m17_learning_plan.sql`，其中包含 `14a_pre_m16_extensions.sql`、两份 `15_`、`16_` 和 `17_`。
- 已有库执行 `deploy/migrations/` 里尚未跑过的文件。当前最后一份是 `V20261025_learning_plan.sql`。不要改写已经执行过的脚本。
- 再新增迁移时使用新的文件名，接在上述文件之后。脚本保持幂等。禁止 DROP 或 TRUNCATE 业务表。
- 已有数据卷不会自动重跑 `sql/`。容器里对已有库执行新迁移：`docker compose exec -T mysql mysql -ukzai -p course_stack_ai <` 对应的新文件。

## 4. Nacos 配置管理
- 控制台 `http://<nacos>:8848/nacos`（默认无鉴权=仅限内网；生产开 NACOS_AUTH_ENABLE=true）
- Prompt 配置 dataId：`kzai-prompts.json` / `tj.ai`；系统参数：`kzai-ai-params.json`
- 发布失败保留最后有效配置（M13 机制）；Prompt 未配置时服务内置默认模板兜底
- Nacos 短暂不可用：已注册服务继续运行（客户端缓存），新实例无法注册

## 5. Elasticsearch 重建
- 管理端：知识库管理 → 单文档「重建」或全量重建（版本化别名，旧版本保留可回滚）
- 重建失败旧版本自动保持可用（版本切换前校验）；孤儿切片清理任务自动执行
- 完全重建索引：管理端触发，期间旧别名持续服务

## 6. Redis 故障
- 影响：会话历史降级（内存，重启丢失+明确提示 persisted=false）、Prompt/参数缓存不可刷新（用内置默认）、每日限额 fail-open（**该窗口无严格付费额度保障**——M13 已批准语义，报告已声明）
- 不影响：JWT 已发 token 校验（本地签名验证）、MySQL 业务
- 恢复后自动重连；会话历史从降级时刻起新写

## 7. Outbox FAILED 重投
- 管理端「事件运维」页：FAILED 列表 → 重投（eventKey 不变、Worker 真实投递、幂等恰好一条通知、审计留痕）
- 或 API：`POST /admin/outbox/{id}/retry`

## 8. 模型限流与语音异常
- DashScope 429/超时 → 统一受控 SSE 终态（不产生空白成功回复）；检查 `ai_model_call_log.error_kind`
- 语音：`voice.enabled` 参数一键关闭；并发护栏 `voice.max-concurrent` 独立于 Redis

## 9. 安全密钥配置
- 生产必须更换：`KZAI_JWT_SECRET`、`KZAI_INTERNAL_SECRET`、MySQL/Redis 密码、开启 Nacos 鉴权
- `DASHSCOPE_API_KEY` 仅经环境变量注入（不入库/日志/Git）

## 10. 停止与重启
```bash
docker compose down          # 保留数据卷
docker compose down -v       # 危险：删除全部数据（仅限销毁测试环境）
docker compose restart aigc  # 单服务重启
```
M15 实测：aigc 重启后 MySQL 学习记录、Redis 会话历史均完整保留。
