# 本地基建（infra/）

本目录用 docker compose 管理本地开发依赖：PostgreSQL + Redis。后续 MQ、定时任务调度、对象存储等按需往 `docker-compose.yml` 里加服务。

## 前置

- Docker Desktop 已启动
- 端口 5432（Postgres）、6379（Redis）未被占用。若本地已有同端口容器，先停掉或改端口。

## 首次启动

```bash
cd infra

# 1. 复制环境变量模板，按需改值（默认值已可用）
cp .env.example .env

# 2. 拉镜像并后台启动基建
docker compose up -d

# 3. 看健康状态，两个服务都应是 healthy
docker compose ps
```

预期输出（services healthy）：

```
NAME                STATUS                     PORTS
infra-postgres-1   Up (healthy)               127.0.0.1:5432->5432/tcp
infra-redis-1       Up (healthy)               127.0.0.1:6379->6379/tcp
```

## 常用命令

```bash
# 停止容器（数据保留在 volume 里）
docker compose stop

# 再次启动
docker compose start

# 停止并删除容器（volume 保留，数据不丢）
docker compose down

# 彻底清空数据（删 volume，谨慎！会丢失所有库表数据）
docker compose down -v

# 看 Postgres 日志
docker compose logs -f postgres

# 进 Postgres 连一下
docker compose exec postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"
```

## 数据库初始化

`agent_lab` 库由 `POSTGRES_DB` 环境变量自动创建。表结构由应用 `spring.jpa.hibernate.ddl-auto=update` 在启动时自动同步，无需手动建表。字段/约束的权威说明见 `agent-dao/src/main/resources/schema.sql`。

## 环境变量

`.env` 里的值要和应用 `agent-app/src/main/resources/application.yml` 的配置一致。当前应用用硬编码 dev 值（`agent` / `123456`），所以 `.env.example` 的默认账号密码也已对齐。改了 `.env` 里的值，记得同步改 `application.yml`（或用环境变量覆盖）。

## 应用启动

基建起来后，回到项目根目录启动应用：

```bash
mvn -pl agent-app spring-boot:run
```
