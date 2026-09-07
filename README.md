# 云梯智能客服项目

本仓库依据 `docs/design` 中的章节顺序逐步实现云梯智能客服系统，并使用“一章一个分支”的方式保留每个阶段的完整代码状态。

## 项目目录

| 目录 | 技术栈 | 对应起始章节 |
| --- | --- | --- |
| `yunti-backend` | Java 21、Spring Boot、Maven 多模块 | 04 搭建 Java 后端项目骨架 |
| `yunti-frontend` | Vue 3、TypeScript、Vite | 05 搭建后台管理系统前端项目骨架 |
| `yunti-ai` | Python、FastAPI | 06 搭建 Python AI 项目骨架 |

## 分支约定

- `main`：保存设计文档和项目说明，作为各章节开发的起点。
- `chapter/NN-英文简述`：按章节顺序实现，每完成一章形成一个可独立回溯的分支。

当前从第一个代码章节 `04 搭建 Java 后端项目骨架` 开始。
