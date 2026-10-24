# 部署手册（M15）

## 1. 系统组成

| 组件 | 版本 | 端口 | 数据卷 |
|---|---|---|---|
| MySQL | 8.0 | 3306（容器内） | mysql-data |
| Redis | 7-alpine | 6379（容器内） | redis-data（AOF） |
| Nacos | v2.2.3 standalone | 8848 | nacos-data/logs |
| Elasticsearch | 8.11.4 single-node | 9200 | es-data |
| 7 个 Java 微服务 | Java 17 | 18080-18086（容器内） | 无状态（course/knowledge 文件挂卷） |
| 前端 Nginx | 1.27 | **8080（对外）** | 静态 dist |

## 2. 环境要求
- Docker 20+ 与 Docker Compose v2（`docker compose version` 验证）
- 内存 ≥ 6GB 空闲（ES 512m×2 + Nacos 512m + 7×Java 512m + MySQL/Redis）
- 磁盘 ≥ 10GB
- 首次构建需访问 Maven Central 与 Docker Hub

## 3. 一键部署步骤
```bash
cd deploy
cp .env.example .env        # 按需修改密码/端口/AI Key
docker compose build        # 首次 10-20 分钟（多阶段 Maven 构建×8）
docker compose up -d
docker compose ps           # 等待全部 healthy（mysql 初始化约 1 分钟）
```

## 4. 初始化说明（自动）
- **空库**：`sql/` 挂到 `/docker-entrypoint-initdb.d`，只在数据卷第一次为空时按文件名执行：`01` 到 `14`、`14a_pre_m16_extensions.sql`、`15_m14_voice_params.sql`、`15_m16_stats_and_kb_source.sql`、`16_m16_publish_generation.sql`、`17_m17_learning_plan.sql`、`18_m18_payment_refund.sql`。`14a` 补上原先只在 `deploy/migrations` 里的 `kb_status`、`replaced_by`、`replace_time` 等列，否则 `15_m16` 的 `AFTER kb_status` 会失败。
- **已有库**：不要依赖容器初始化。按时间顺序执行 `deploy/migrations/V20261021` 到 `V20261029_learning_note.sql` 中尚未执行过的文件。支付渠道密钥使用环境变量 `ALIPAY_SANDBOX_APP_ID`、`ALIPAY_SANDBOX_PRIVATE_KEY`、`ALIPAY_SANDBOX_PUBLIC_KEY`，不要写入仓库。未配置时只有模拟支付。`V20261023` 不会重写已有 `facts_since`。`V20261025` 只新增学习计划表。已有数据卷不会自动重跑 `sql/`。
- **知识库**：服务启动后 ES 索引别名自动初始化（Mock Embedding 256 维）；如需语义演示请上传文档并发布
- **Nacos Prompt 配置**：PromptManager 内置默认模板（Nacos 缺失时兜底），首次启动自动写入生效缓存

## 5. 验证部署
```bash
# 三角色登录
curl -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}'
# 浏览器
open http://localhost:8080
```
演示账号（SEED-DEMO，仅限本地演示）：admin/admin123、teacher01/teacher123、student01/student123。

## 6. 平台兼容性
| 平台 | 状态 |
|---|---|
| macOS Apple Silicon（本仓库开发/验证环境） | 已验证（原生服务 + 隔离干净库全链路） |
| Linux x64/ARM64 | 镜像均为多架构官方镜像，命令兼容；未实机测试 |
| Windows 11 | 需 Docker Desktop（WSL2 后端）；命令用 PowerShell 等价形式（`cp`→`Copy-Item`）；未实机测试 |
| macOS Intel | 同 Apple Silicon（镜像多架构）；未实机测试 |

## 7. 常见部署问题
| 现象 | 原因/处理 |
|---|---|
| 服务反复重启 | `docker compose logs <svc>` 查看依赖健康检查是否通过；mysql 未就绪时 Java 服务会等待重启 |
| ES 启动失败 | `sysctl -w vm.max_map_count=262144`（Linux 必需；Docker Desktop 一般已配置） |
| 端口冲突 | 修改 `.env` 的 GATEWAY_PORT/WEB_PORT |
| 前端能开但接口 502 | gateway 未就绪（等 healthy）；或 `.env` 改过端口未重建 web |
| 中文知识文档索引失败 | 已修复（UTF-8 body）；若复现请检查 ES mapping 与版本切换日志 |

## 8. 已知部署边界（如实声明）
- **本仓库验证环境无 Docker**：容器编排实测未执行；已用「隔离干净数据库（course_stack_ai_m15）+ 独立服务集群（namespace=m15，端口 2808x）」完成等价的从零初始化、三角色闭环、重启恢复验证（见 16-M15执行报告）。
- Compose 内 Java 服务健康检查为 TCP 端口探测（未引入 actuator 依赖）。
- 真实语音/AI 模型需在 `.env` 显式开启（DASHSCOPE_ENABLED=true + Key），默认 MOCK 零计费。


## 9. AI 课程语义推荐增量部署（M23）

升级前执行 `sql/23_m23_course_recommendation.sql`，或对应 `deploy/migrations/V20261030_course_recommendation.sql`，然后更新 course、learning、aigc、gateway 和前端。

推荐专用 `KZAI_RECOMMENDATION_EMBEDDING_PROVIDER=dashscope`、`KZAI_RECOMMENDATION_EMBEDDING_MODEL=text-embedding-v4` 与 `DASHSCOPE_API_KEY` 启用真实推荐；默认 mock 只提供明确标记的关键词结果。真实对话另外开启 `DASHSCOPE_ENABLED=true`。这些推荐配置不更改教师知识库 `KZAI_EMBEDDING_PROVIDER/MODEL`。Compose 已透传新配置。

首次启动会分页初始化独立 `kzai-course-recommend-*` 索引，之后持续对账；不是瞬时完成。用管理员 `GET /admin/ai/recommendations` 观察状态；失败可 `POST /admin/ai/recommendations/retry`，重建可 `POST /admin/ai/recommendations/rebuild`。更换模型或维度使用新命名空间，保留旧配置与旧索引供回切，不混写知识库索引。

本地服务使用 `scripts/run-services.sh` 的 JAR 快照；可将运行环境保存为仅本人可读的 `.runtime/services/<模块>/runtime-env.json`（JSON 字符串字典、权限600、不入版本管理），脚本重启时会加载。不要把密钥写进源码、文档或命令日志。完整迁移、复现测试、实际限制见 [AI课程语义推荐交付与验收](99-AI课程语义推荐.md)。


## 推荐回答策略增量修复（2026-10-24）

本次无需数据库迁移或重建课程索引。更新 aigc 与前端后，通过现有 `/admin/ai/prompts/RECOMMEND/preview`、`/publish` 正常预览发布推荐模板，保留其他四类内容。当前已发布版本为 `pub-20261024075445`，已回读且 Nacos/Redis 生效。

旧会话继续使用原 prompt_version_map，不批量改写历史绑定；推荐分支会在同一个 SystemMessage 末尾合并统一业务约束，同时由服务端校验参数来源、使用实际结果组织正文。只修改源码默认或仅发布模板，不足以替代这两个运行时处理。

回滚按现有快照启动和 Prompt 版本发布流程处理；不要删除历史模板或把全部会话重绑到新版本。复现实验脚本为 `scripts/eval-recommendation-policy-live.py`，需测试账号环境变量与真实模型环境，会自动清理临时会话。详见 [修复报告](AI推荐回答策略与无结果处理修复报告.md)。
