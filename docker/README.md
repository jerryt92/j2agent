# docker/

本目录为 **Docker Compose 可执行配置**（`docker-compose.yml`、`.env`、`j2agent/Dockerfile`、`package_offline.sh`）。

完整部署说明、数据卷说明、前端更新 SOP 见：

**[docs/docker部署/README.md](../docs/docker部署/README.md)**

首次使用：

```bash
cp .env.example .env
# 编辑 .env 后
docker compose up -d --build
```

如果启动 MinIO 报错 `Bind for 0.0.0.0:9000 failed: port is already allocated`：

1) 通常是你同时启动过其他栈（例如 `milvus-standalone-docker-compose.yml`）也在占用 `9000/9001`
2) 修改 `docker/.env` 里的端口，换成不冲突的值，例如：
   - `MINIO_API_PORT=19000`
   - `MINIO_CONSOLE_PORT=19001`
3) 仅重启 MinIO：`docker compose up -d minio`
