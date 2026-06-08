# docker/

本目录为 **Docker Compose 可执行配置**（`docker-compose.yml`、`.env`、`j2agent/Dockerfile`、`package_offline.sh`）。

完整部署说明、数据卷说明、前端更新 SOP 见：

**[j2agent-docs/基础设施/docker部署/README.md](../../j2agent-docs/基础设施/docker部署/README.md)**

首次使用：

```bash
cp .env.example .env
# 编辑 .env 后
docker compose up -d --build
```

构建 `j2agent` 镜像时，Dockerfile 会**优先**使用 `j2agent/*.tar.gz`（与本目录 `Dockerfile` 同级），否则回退到 `j2agent-starter/target/*.tar.gz`。离线部署建议将 Maven 产出的 `j2agent-*.tar.gz` 放到 `j2agent/` 再构建，详见 [构建与启动 §1.1](../../j2agent-docs/基础设施/docker部署/构建与启动.md#11-镜像构建时如何找到-targz)。

如果启动 MinIO 报错 `Bind for 0.0.0.0:9000 failed: port is already allocated`：

1) 通常是你同时启动过其他栈（例如 `milvus-standalone-docker-compose.yml`）也在占用 `9000/9001`
2) 修改 `docker/.env` 里的端口，换成不冲突的值，例如：
   - `MINIO_API_PORT=19000`
   - `MINIO_CONSOLE_PORT=19001`
3) 仅重启 MinIO：`docker compose up -d minio`
