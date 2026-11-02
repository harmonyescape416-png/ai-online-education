# 课栈智伴

Spring Cloud Alibaba 和 Vue 3 做的 AI 在线教育平台。学员用对话查课程，系统给出课程卡片；说想买时先出确认提案，确认后才创建待支付订单。

## 技术栈

Spring Boot 3.3.5 · Spring Cloud Alibaba 2023.0.3.2 · Vue 3 + Vite 5 + Element Plus · MySQL 8 · Redis 7 · Nacos 2.2 · Elasticsearch 8.11

服务：`kzai-gateway`、`kzai-auth`、`kzai-user`、`kzai-course`、`kzai-trade`、`kzai-learning`、`kzai-aigc`，公共库 `kzai-common`，前端 `kzai-frontend`。

## 本地启动

本机需要 MySQL `3306`、Redis `6379`、Nacos `8848`。知识库检索才需要 Elasticsearch `9200`。

```bash
source scripts/dev-env.sh
bash scripts/run-services.sh start
cd kzai-frontend && npm install && npm run dev
```

空库按 `sql/` 文件名顺序初始化。已有库只执行 `deploy/migrations/` 里还没跑过的脚本。

演示账号只用于本地：`admin` / `admin123`，`teacher01` / `teacher123`，`student01` / `student123`。

## 现在的限制

- 支付默认是模拟渠道。支付宝沙箱要另配密钥，不代表真实扣款。
- 真实语音识别和语音合成未验收。
- 没有 Elasticsearch 时，知识库检索不是语义搜索。

## 许可证

[MIT](LICENSE)
