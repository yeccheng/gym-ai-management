# 智慧健身管理系统（Spring AI）

基于大语言模型智能体的健身房管理系统（毕业设计作品）。  
技术栈：Spring Boot 3 · Apache Shiro + JWT · Spring AI · MySQL · Redis · Elasticsearch · RocketMQ · 自定义 AI Starter。

> **学术诚信声明**：本仓库仅供学习与参考。**禁止**将本项目（或仅改皮后的代码）直接作为他人毕业设计 / 课程作业提交。引用请注明来源。

## 功能概览

- 会员端：训练打卡、饮食/体重、课程预约、论坛、AI 教练对话（多 Agent）
- 管理端：场馆业务接口（`/api/**`，需 ADMIN）
- AI：RAG / Function Calling、异步周报复盘任务、Token 预算与并发控制

## 环境要求

| 组件 | 建议版本 |
|------|----------|
| JDK | 17+ |
| Maven | 3.8+ |
| Docker Desktop | 用于 MySQL / Redis / ES / RocketMQ |
| DeepSeek API Key | 对话模型（云端） |
| Ollama（可选） | 本地 embedding：`bge-m3`；也可用本地 chat |

## 快速开始

### 1. 克隆 / 解压后进入项目根目录

### 2. 配置密钥（不要写进仓库）

```powershell
# PowerShell 示例
$env:DEEPSEEK_API_KEY = "sk-你的密钥"
```

也可复制 `.env.example` 为 `.env`，用 IDE / 脚本自行加载。  
相关变量说明：

| 变量 | 说明 | 本地默认（docker-compose） |
|------|------|---------------------------|
| `DEEPSEEK_API_KEY` | DeepSeek 密钥，**必填**才能对话 | 无默认，必须自行设置 |
| `MYSQL_PASSWORD` | MySQL 密码 | `123456` |
| `REDIS_PASSWORD` | Redis 密码 | `redis@2026` |
| `JWT_SECRET` | JWT 签名密钥（≥32 字节） | 开发默认串 |
| `GYM_MQ_COURSE_CONSUME_MODE` | `rocketmq` / `sync` | `rocketmq` |

### 3. 启动依赖

```bash
docker compose up -d
```

将启动：MySQL `3306`、Redis `6379`、Elasticsearch `9200`、RocketMQ（namesrv/broker）等。  
若暂时不想起 MQ，可设：

```powershell
$env:GYM_MQ_COURSE_CONSUME_MODE = "sync"
```

### 4. 初始化数据库

确保库名 `gym` 已存在（compose 会创建）。执行建表脚本：

```bash
# 示例：在 MySQL 客户端执行
docs/sql/schema.sql
# 如需增量迁移，按 docs/sql/migrate_*.sql 顺序按需执行
```

### 5. 编译并启动

```bash
# 请使用 JDK 17
mvn -pl gym-web -am clean package -DskipTests
java -jar gym-web/target/gym-app.jar
```

或在 IDE 中运行 `gym-web` 启动类。

浏览器访问：<http://localhost:8080>  
接口文档（Knife4j）：<http://localhost:8080/doc.html>

## 演示账号

应用启动后会自动播种（若库中尚无）：

| 账号 | 密码 | 角色 |
|------|------|------|
| `admin` | `admin123` | 管理员 |
| `demo` | `demo123` | 会员（含样板训练数据） |

短信验证码演示模式开启时，发码与校验均可使用 `123456`（见 `gym.auth.demo-sms`）。

## 模块说明

| 模块 | 职责 |
|------|------|
| `gym-common` | 通用结果体、JWT 工具、UserContext |
| `gym-system` | 系统/业务领域与 Mapper |
| `gym-training` | 训练、饮食、演示数据 |
| `gym-ai` | Agent、工具、RAG、异步任务 |
| `gym-ai-spring-boot-starter` | 自定义 Starter（自动装配 AI 模板与额度） |
| `gym-web` | 启动入口、Shiro+JWT、Controller、静态前端 |

## 安全说明

- 认证：Apache Shiro + JWT（无 Session）+ Redis Token 黑名单  
- 授权：过滤器角色校验 + `@RequireRole` 拦截器  
- **切勿**把真实 API Key、生产库口令提交到 Git  

## License

[MIT](./LICENSE)

再次强调：开源便于学习交流，**请勿冒用为本仓库作者之外的毕业设计成果**。
