<p align="center">
  <h1 align="center">Tugline</h1>
  <p align="center">
    <b>Your multi-repo deployment assembly line.</b><br/>
    <sub>多个 Git 仓库 → 拉取 → 构建 → 部署 → 健康检查，一条流水线搞定，内置 AI 助手帮你管。</sub>
  </p>
  <p align="center">
    <a href="https://opensource.org/licenses/MIT"><img src="https://img.shields.io/badge/license-MIT-green" alt="License"/></a>
    <img src="https://img.shields.io/badge/JDK-21-blue" alt="JDK"/>
    <img src="https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen" alt="Spring Boot"/>
    <img src="https://img.shields.io/badge/platform-macos%20%7C%20linux-lightgrey" alt="Platform"/>
  </p>
</p>

---

## 这是什么

Tugline 是一个**单机多仓库部署流水线平台**（Java / Spring Boot 单体）。

把多个 Git 仓库接入平台后，每个仓库拥有同一条流水线：

```
FETCH 拉取代码 → BUILD 构建(Maven/Gradle) → DEPLOY 部署(java -jar) → HEALTH 健康检查
```

在 Web 控制台里点「部署」就能完成从代码到在线服务的全过程；也可以直接对内置 AI 助手说
「启动 https://github.com/xxx/yyy」「关掉 citadel」，由助手调用平台工具代你执行。

## 功能特性

### 流水线
- **一键部署**：拉取 → 构建 → 部署 → 健康检查全流程自动化，状态实时同步
- **应用管理**：启动 / 停止 / 重启，进程树级销毁（TERM → 3s → KILL），不留僵尸进程
- **运行记录**：每次流水线的步骤状态、耗时、错误信息、构建日志与应用运行日志均可追溯
- **Webhook 自动部署**：`POST /api/webhook/{repoId}`，代码推送即触发
- **健康检查**：HTTP 探针 + 就绪等待（`healthPath` 可配置）

### 资源安全监测
- **三个闸口内容扫描**：拉取后（工作副本）、构建后（构建产物）、部署后（实际上线资源），违规即阻断流水线，部署后违规则停服
- 违禁词 / 恶意脚本 / 风险外链检测，词库支持 `data/scan/*.txt` 自定义扩展
- **图片 AI 审核**：对接阿里云内容安全（可选，未配 AK 自动跳过）

### 内置 AI 助手
- 右侧栏流式对话（SSE），支持 **DashScope（通义）/ OpenAI 兼容 API**
- **个人 AK 管理**：每个用户维护自己的大模型 Key（掩码存储展示，原文不可见），未配置时回落全局
- **平台原生工具**：助手的一切平台操作走平台服务，状态自动同步

| 工具 | 能力 |
|---|---|
| `list_apps` / `app_status` / `app_log` | 应用列表 / 状态 / 运行日志 |
| `start_app` / `stop_app` | 一键部署启动 / 停止（参数可直接传 git 地址，无则自动接入） |
| `add_repo` / `update_repo` / `delete_repo` / `list_branches` | 仓库接入 / 配置 / 删除（需确认）/ 分支 |
| `run_history` / `run_detail` / `run_log` | 流水线记录 / 步骤明细 / 步骤日志 |

- **硬约束安全**：Shell、文件读写、子代理、动态技能加载等 harness 内置工具全部禁用——助手无法绕开平台接口执行 `java -jar`、`kill` 或修改平台数据

### 认证
- JWT 无状态鉴权，登录凭据持久化，支持环境变量覆盖账号密码与签名密钥

## 快速开始

### 环境要求
- JDK 21+
- git、Maven（流水线在本机构建）
- macOS / Linux

### 构建与运行

```bash
git clone https://github.com/zhangxuanchen/Tugline.git
cd Tugline
mvn clean package -DskipTests

# 必改：生产环境请通过环境变量覆盖默认账号与 JWT 密钥
export TUGLINE_AUTH_PASSWORD='你的强密码'
export TUGLINE_JWT_SECRET='至少32位的随机字符串'

java -jar target/tugline-0.1.0.jar
# 浏览器打开 http://localhost:7070，默认账号 admin（密码见上方环境变量）
```

### 接入大模型 AK（AI 助手）

任选其一：

```bash
# 方式一：通义千问（默认 provider）
export DASHSCOPE_API_KEY='sk-...'

# 方式二：任意 OpenAI 兼容 API（DeepSeek / GLM / Kimi / 自建网关）
export TUGLINE_AGENT_PROVIDER=openai
export LLM_API_KEY='sk-...'
export LLM_MODEL='deepseek-chat'
export LLM_BASE_URL='https://api.deepseek.com'
```

也可以不配全局 Key，登录后在右上角「AI 密钥管理」里维护**个人 AK**（支持通义 / DeepSeek / GLM / Kimi / OpenAI / 自定义兼容）。

## 配置说明

所有配置均可用环境变量覆盖（见 [application.yml](src/main/resources/application.yml)）：

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `SERVER_PORT` | `7070` | 服务端口 |
| `TUGLINE_AUTH_USERNAME` / `TUGLINE_AUTH_PASSWORD` | `admin` / `admin123` | 登录凭据（**生产必改**） |
| `TUGLINE_JWT_SECRET` | 见 yml | JWT 签名密钥（**生产必改**，≥32 位） |
| `TUGLINE_AUTH_TTL_HOURS` | `72` | 登录态有效期 |
| `TUGLINE_AGENT_PROVIDER` | `dashscope` | `dashscope` 或 `openai` |
| `DASHSCOPE_API_KEY` / `LLM_API_KEY` | 空 | 大模型全局 AK |
| `TUGLINE_AGENT_MODEL` / `LLM_MODEL` | `qwen3-max` / `deepseek-chat` | 模型名 |
| `LLM_BASE_URL` | `https://api.deepseek.com` | OpenAI 兼容网关地址 |
| `TUGLINE_SCAN_ENABLED` | `true` | 内容安全扫描开关 |
| `TUGLINE_SCAN_IMAGE_AK` / `TUGLINE_SCAN_IMAGE_SK` | 空 | 阿里云内容安全（图片审核）凭证 |
| `TUGLINE_DATA_DIR` | `./data` | 数据目录（仓库/会话/日志/凭据） |

## 部署到服务器

Tugline 与其部署的应用同机运行，推荐 systemd 托管：

```bash
sudo apt install -y openjdk-21-jdk git maven
sudo scp target/tugline-0.1.0.jar user@server:/opt/tugline/
```

```ini
# /etc/systemd/system/tugline.service
[Unit]
Description=Tugline Deploy Platform
After=network.target

[Service]
User=ubuntu
WorkingDirectory=/opt/tugline
ExecStart=/usr/bin/java -Xms512m -Xmx1g -jar /opt/tugline/tugline-0.1.0.jar
Environment=TUGLINE_AUTH_PASSWORD=改成强密码
Environment=TUGLINE_JWT_SECRET=改成随机长字符串
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl enable --now tugline
```

安全组放行 `7070`（以及各受管应用端口），建议再加一层 Nginx + HTTPS。内存建议 ≥ 2G。

## 目录结构

```
src/main/java/dev/tugline/
├── core/        # 流水线引擎：PipelineService / BuildService / AppRuntimeService / GitService
├── scan/        # 内容安全扫描：ContentScanService / ImageAuditClient
├── web/         # REST API：仓库 / 流水线 / 运行记录 / Webhook
├── agent/       # 内置 AI 助手
│   ├── core/    # AgentFactory / ModelFactory / SingleTurnExecutor / AgentKeyStore（个人 AK）
│   ├── tools/   # PlatformTools：12 个平台原生工具
│   └── web/     # SSE 对话接口
├── security/    # JWT 认证
└── store/       # JsonStore：仓库/流水线/运行状态持久化
src/main/resources/static/   # 原生前端（无框架）
data/                        # 运行时数据（不入库）：repos / logs / sessions / keys.json
```

## 安全说明

- `data/` 目录（含个人 AK 明文 `data/agent/keys.json`、登录凭据哈希）已在 `.gitignore` 排除，**切勿提交或随包分发**
- 个人 AK 在界面只显示掩码（前 3 后 4），原文任何接口不返回
- 公网部署请务必：改默认密码、换 JWT 密钥、加 HTTPS
- AI 助手执行面收敛于平台工具白名单，无法执行任意命令

## License

[MIT](./LICENSE) © Tugline Contributors
