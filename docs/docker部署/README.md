# Docker 部署

本文档说明如何使用仓库内 [`docker/`](../../docker/) 目录通过 Docker Compose 部署 **j2agent** 全栈（MySQL、Redis、Milvus、j2agent 应用）。

## 组件一览

| 服务 | 说明 | 默认宿主机端口（见 `.env`） |
|------|------|------------------------------|
| `mysql` | 业务库 `j2agent` | `MYSQL_PORT`（如 30136） |
| `redis` | 缓存 / 会话 | `REDIS_PORT`（如 30379） |
| `etcd` | Milvus 元数据（外置，先于 Milvus 启动） | `ETCD_PORT`（2379） |
| `milvus` | 向量库（standalone + 外置 etcd） | `MILVUS_PORT`（19530） |
| `j2agent` | Spring Boot 后端 + 托管前端静态资源 | `J2AGENT_PORT`（如 30111） |

前端 **不打包进镜像**：通过宿主机目录 `${J2AGENT_VOLUMES_PATH}/ui` 挂载到容器 `/opt/j2agent/volume/ui`，由 `product` profile 以 `file:` 方式提供。详见 [前端静态资源更新.md](./前端静态资源更新.md)。

## 快速开始

1. 复制环境变量模板并编辑（**勿将含真实密码的 `.env` 提交到 Git**）：

   ```bash
   cd docker
   cp .env.example .env
   # 编辑 .env：J2AGENT_VOLUMES_PATH、密码、端口等
   ```

2. 在仓库根目录打包后端（镜像构建需要 `j2agent-starter/target/*.tar.gz`）：

   ```bash
   mvn -f j2agent-sdk/pom.xml -pl j2agent-model -am -DskipTests install
   mvn -pl j2agent-starter -am -DskipTests package
   ```

3. 准备宿主机数据目录（首次部署）：

   ```bash
   export J2AGENT_VOLUMES_PATH=/opt/j2agent   # 与 .env 一致
   mkdir -p "${J2AGENT_VOLUMES_PATH}"/{dist,logs,knowledge-repo,plugins/agents,mysql/data,mysql/conf,mysql/run,redis/data,milvus/volumes/etcd,milvus/volumes/milvus}
   # 将前端构建产物放入 dist/；知识库放入 knowledge-repo/
   ```

4. 启动：

   ```bash
   cd docker
   docker compose up -d --build
   ```

5. 访问：`http://<主机>:${J2AGENT_PORT}/`（需 `dist/index.html` 已就位）。

## 文档索引

| 文档 | 内容 |
|------|------|
| [目录与数据卷.md](./目录与数据卷.md) | `J2AGENT_VOLUMES_PATH` 目录结构、卷挂载 |
| [构建与启动.md](./构建与启动.md) | Maven 打包、镜像构建、profile 与环境变量 |
| [离线镜像打包.md](./离线镜像打包.md) | `docker/package_offline.sh` 离线导出 |
| [前端静态资源更新.md](./前端静态资源更新.md) | 替换 dist 无需重启容器的 SOP 与排错 |
| [LLM 提供商配置](../LLM提供商配置/README.md) | `api_provider_config`、baseUrl 示例、`config_json` 字段说明 |

## 与知识库静态资源的区别

- **前端 SPA**：`${J2AGENT_VOLUMES_PATH}/ui` → Spring `spring.web.resources.static-locations`
- **知识库图片等**：`${J2AGENT_VOLUMES_PATH}/knowledge-repo` → `/v1/rest/j2agent/file/repo/**`，见 [静态文件展示机制](../RAG机制/静态文件展示机制.md)

二者目录与更新方式不同，请勿混淆。

## 从内嵌 etcd 迁移到外置 etcd

若此前使用内嵌 etcd（数据在 `milvus/volumes/milvus/etcd`），升级 compose 后请：

1. 停止栈：`docker compose stop milvus j2agent`（或 `down` 仅停容器）
2. 在 `.env` 中增加 `ETCD_IMAGE_TAG`、`ETCD_PORT`（可参考 `.env.example`，原 `MILVUS_ETCD_PORT` 已改为 `ETCD_PORT`）
3. 创建外置 etcd 目录：`mkdir -p "${J2AGENT_VOLUMES_PATH}/milvus/volumes/etcd"`
4. 若 Milvus 曾反复 panic，建议不再复用旧内嵌 etcd 目录；向量数据可保留 `milvus/volumes/milvus` 下除 `etcd` 外的内容，或整库重建
5. 启动：`docker compose up -d etcd`，待 healthy 后再 `docker compose up -d milvus j2agent`

外置 etcd 与 Milvus 分进程启动，可避免 `etcdserver: leader changed` 导致的 MixCoord panic 重启循环。
