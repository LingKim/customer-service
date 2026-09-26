# 云梯智能客服官网

此目录是零运行依赖的静态官网与交互演示。`demo.html` 使用固定问答及模拟人工回复，不连接真实会话。页面报价只显示计费服务公开接口中已发布的套餐；当前计费服务尚无套餐实体，所以接口返回空列表，页面显示“待发布”。

本地预览：`./serve.sh --no-open`。默认监听 `127.0.0.1:5180`，把 `/api/**` 转发到 `http://127.0.0.1:9090/api/**`。端口占用时直接退出，可用 `--port` 指定其他端口。

构建：`node scripts/build.mjs`，生成 `dist/`。可通过 `YUNTI_API_BASE`、`YUNTI_APP_BASE` 指定接口前缀和管理端地址。Docker 镜像从构建产物复制静态文件，需先生成 `dist/`；如跨域部署，须由部署环境配置网关转发与 CORS。

`legal.html` 目前明确标示正式协议与隐私政策尚未发布，不能作为法务定稿。官网配置在 `yunti-ops-service` 的 `site-config.json`，公开接口为 `/api/ops/public/site-config`。
