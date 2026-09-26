# yunti-ai · AI 编排中心骨架

对应系统架构文档中的 **ai-center（Python AI 服务集群）**：

| 子服务 | 职责 | 关键依赖 |
| --- | --- | --- |
| Agent 服务 | 对话编排（LangGraph 状态机）、意图/情绪、转人工 | langgraph / langchain |
| RAG 服务 | 知识检索（LlamaIndex）、向量库 | llama-index |
| 质检/训练服务 | 全量质检流水线、模型微调任务 | langgraph（批处理） |
| LLM 网关 | 千问 / DeepSeek 统一接入、降级与计量 | openai / 厂商 SDK |

## 快速运行（健康检查即可用）

```bash
cd yunti-ai
bash scripts/bootstrap.sh     # 一键创建 .venv 并安装依赖
./start.sh                   # 一键启动（检查端口与依赖）
```

验证： `curl http://localhost:9100/api/ai/health`

## 在 IntelliJ IDEA 中直接运行（参考企业知识库 Python 后端的方案）

项目自带两个共享运行配置（用 IDEA 打开 `yunti-ai` 目录后，右上角运行配置下拉即可看到）：

| 运行配置 | 类型 | 说明 |
| --- | --- | --- |
| `yunti-ai (Shell)` | Shell 脚本 | **推荐** 。无需安装 Python 插件，脚本自动管理.venv/依赖/端口，与 IDEA 解释器设置完全解耦 |
| `yunti-ai (Python)` | Python | 备选。需要安装并启用 Python 插件，且项目解释器指向 `.venv` |

### 方式一：yunti-ai (Shell)——零插件、零配置（推荐）

1. 用 IntelliJ IDEA 打开 `yunti-ai` 目录；
2. 右上角运行配置选择 **yunti-ai (Shell)** ，点击 ▶ 启动；
3. 脚本会自动完成：检查 9100 端口 → 校验/创建 `.venv` → 校验/安装依赖 → 打印启动信息并运行服务；端口被其他进程占用时会明确报错而不会停止它；
4. 控制台出现 `Uvicorn running on http://127.0.0.1:9100` 后，访问 `http://127.0.0.1:9100/api/ai/health` 验证；
5. 换端口：运行配置 → Script options 填入 `--port 9200` （支持 `--host` 、 `--no-reload` ，详见 `./start.sh --help` ）。

> 等价命令行：`./start.sh` 或 `./start.sh --port 9200` 。

### 方式二：yunti-ai (Python)——需要 Python 插件

1. 安装 Python 插件： `Settings → Plugins → Marketplace` ，搜索 **Python** （IDEA Community 为 **Python Community Edition** ），安装后重启 IDEA；
2. 运行配置已直接绑定项目 `.venv` （不依赖项目解释器设置），右上角选择 **yunti-ai (Python)** ，点击 ▶ 即可启动；
3. 若 IDEA 仍提示找不到解释器： `Settings → Project → Python Interpreter → Add Interpreter → Existing` ，选择 `yunti-ai/.venv/bin/python` ，再运行一次。

> **常见问题：Unknown run configuration type PythonConfigurationType** 该提示只影响 **yunti-ai (Python)** （它依赖 Python 插件）。解决方式：
>
> 1. 直接使用 **yunti-ai (Shell)** 启动，完全不需要 Python 插件（推荐）；
> 2. 若想用 Python 配置，安装并启用 Python 插件后重启 IDEA。

> **常见问题：SDK is not defined for Run Configuration** 这是 **yunti-ai (Python)** 的解释器问题（Shell 配置不会遇到）。解决方式：
>
> 1. 使用 **yunti-ai (Shell)** 启动；
> 2. 如必须用 Python 配置： `File → Project Structure → Project → Python Interpreter → Add Interpreter → Existing` ，选择 `yunti-ai/.venv/bin/python` ，再点 ▶。

> **常见问题：ModuleNotFoundError: No module named 'uvicorn'** 说明运行用的 Python 不是 `.venv` （如系统 Python 3.10，未装任何依赖）。处理：
>
> 1. 使用 **yunti-ai (Shell)** 启动，脚本会自动使用 `.venv` 并补齐依赖（最稳）；
> 2. 若用 Python 配置：当前共享配置已直接绑定 `.venv` ，重新加载项目后即可生效；若旧运行条目仍在，删除旧条目后再从下拉框选择新的 **yunti-ai (Python)** ；
> 3. 也可以手动把项目解释器指向 `yunti-ai/.venv/bin/python` （ `Settings → Project → Python Interpreter → Add Interpreter → Existing` ）。

其他等价启动命令（任选其一，默认 9100 端口，可用 `YUNTI_AI_PORT` / `YUNTI_AI_HOST` 覆盖）：

```bash
./.venv/bin/python main.py    # 与 IDEA Python 配置入口一致
./.venv/bin/python -m ai      # 包模块方式
```

## 完整 AI 能力

### DeepSeek 文本模型

将密钥放在环境变量 `YUNTI_AI_DEEPSEEK_API_KEY`，模型设置为 `YUNTI_AI_DEEPSEEK_MODEL=deepseek-flash`。DeepSeek 接口中 `deepseek-flash` 的显示名称是 DeepSeek-V4.1-Flash。本机已有的 `DEEK_SEEK_KET` 也会作为密钥变量的兼容别名读取；正式配置建议使用标准变量名。不要将真实密钥写入 `.env.example` 或提交到仓库。


安装可选依赖后可启用真实 Agent/RAG：

改成：

```

当前未装 AI 依赖时，对话接口返回 **mock 回复** （代码里已留好接入点），保证骨架始终可运行。

## Java ↔ Python 通信约定（与后端对齐）

- 实时对话：Java customer-service 调 `POST /api/ai/v1/agent/chat` （后续演进 SSE 流式）；
- 批处理（训练/全量质检）：Kafka topic `ai.task.train` / `ai.task.qa` ；
- 租户隔离：请求头 `X-Tenant-Code` 全链路透传校验。
