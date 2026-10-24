# 课栈智伴——AI 智能在线教育平台

Spring Cloud Alibaba 微服务和 Vue 3 前端。正式说明书在 `docs/` 的三份 docx。当前验收口径以验收矩阵为准，不以某一次阶段报告里的“已完成”代替尚未验证的项。

## 技术栈

Spring Boot 3.3.5 · Spring Cloud Alibaba 2023.0.3.2 · Spring Cloud 2023.0.3 · MyBatis-Plus 3.5.9 · Vue 3 + Vite 5 + Element Plus · MySQL 8 · Redis 7 · Nacos 2.2 · Elasticsearch 8.11.4

## 目录

```
kzai-gateway|auth|user|course|trade|learning|aigc/   七个服务
kzai-common/                                      公共库
kzai-frontend/                                    前端。交付包排除 node_modules 和 dist
sql/                                              空库初始化，按文件名顺序，含 14a、15、16
deploy/migrations/                                已有库升级，不替代 sql/
deploy/                                           Compose、Dockerfile、Nginx
data/                                             业务文件。未核对引用前不要删除
docs/                                             当前说明书、验收、部署和运维
docs/archive/                                     历史评审、提示词、实施记录和测试证据
test-results/                                     2026-10-20 与 2026-10-21 的原始测试记录
scripts/                                          本地启动和各阶段 e2e
```

## 文档入口

- 正式说明书：`docs/需求分析说明书.docx`、`docs/系统设计说明书.docx`、`docs/数据库设计说明书.docx`。提取文本在 `docs/extracted/`。核对记录是 `docs/01-原始文档核对结果.md`。
- 桌面上的《软件测试报告》不在本仓库。对照执行记录在 `test-results/2026-10-21/测试执行总结报告.md`。
- 当前验收矩阵和已知限制：`docs/acceptance-matrix.md`。30 项需求都有对应实现，其中 Prompt 版本、评测和真实语音仍有未验证或部分项。不要把矩阵里的 ✅ 理解成真实语音和 Docker 部署已经验收。
- 需求符合性映射：`docs/67-30项需求符合性映射表.md`。它记录的是当时的四档状态，后来的修复以验收矩阵和 `docs/93-学情日期采纳率与资料入库.md` 为准。
- 接口：`docs/api-extension.md`。设计决策：`docs/decisions.md`。
- 部署：`docs/deployment-guide.md`。运维：`docs/operations-guide.md`。
- 最近一次学情、采纳率和知识库修复及尚未执行的验证：`docs/93-学情日期采纳率与资料入库.md`。
- 学员学习计划：`docs/94-学员学习计划.md`。入口在「我的学习」。
- 历史材料：`docs/archive/README.md`。

## 本地启动

```bash
source scripts/dev-env.sh
# 需要本机 MySQL 3306、Redis 6379、Nacos 8848。ES 9200 只在知识库检索时需要。
bash scripts/run-services.sh start
cd kzai-frontend && npm install && npm run dev
```

演示账号：admin/admin123、teacher01/teacher123、student01/student123。

空库初始化按 `sql/` 文件名顺序执行到 `16_m16_publish_generation.sql`。已有库只执行 `deploy/migrations/` 中尚未跑过的文件，最新一份是 `V20261024_publish_generation.sql`。不要改写已经执行过的迁移。

## Docker

```bash
cd deploy
cp .env.example .env
docker compose build && docker compose up -d
```

Compose 只在 MySQL 数据卷第一次为空时执行 `sql/`。已有卷不会自动重跑。本机这次整理没有启动 Docker，不能把脚本准备好写成容器部署已通过。历史说明见 `docs/archive/implementation/16-M15执行报告.md`。

## 测试

全量 Maven 次数以 `test-results/` 和归档报告里的当次记录为准。本次文件整理没有重跑业务测试。

针对性命令示例：

```bash
mvn -pl kzai-learning test -DforkCount=0 -Dtest=LearningServiceTest,FactConcurrencyMysqlTest
mvn -pl kzai-aigc test -DforkCount=0 -Dtest=KbFlowMysqlTest,KbRaceMysqlTest
cd kzai-frontend && npm run build
```

这些命令的最近结果写在 `docs/93-学情日期采纳率与资料入库.md`。其中 Elasticsearch 使用测试替身，Mock 向量不代表语义检索。真实 STT/TTS 仍未验收。

## 交付时排除

不要打包 `kzai-frontend/node_modules/`、`kzai-frontend/dist/`、各模块 `target/`、`logs/`、`.workbuddy/`。保留 `package.json` 和 `package-lock.json`。当前开发机上的 `target` 和 `dist` 正被已启动的服务使用，清理规则见 `docs/文件清理与交付整理报告.md`。
