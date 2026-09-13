# HtmlUnit 可行性原型

独立调研模块，不属于正式 Starter 的发布内容。固定 Spring Boot 4.1.1、Spring AI 2.0.1、HtmlUnit 5.5.0，以 JDK 17 编译运行。

## 本地验证

```sh
mvn -f research/htmlunit-spike/pom.xml test
```

默认仅运行可控本地 HTTP 页面测试，真实模型测试自动跳过。包括导航、表单、DOM、AJAX、独立 Cookie/Storage、脚本超时、现代 JS 能力探测，以及 Boot 自动配置 Builder 的完整工具循环。

“能力探测”测试输出 `CAPABILITY`，它记录支持程度而不把所有现代特性都支持作为通过条件。完整结论见项目 docs/research.md。

## 真实模型验证（显式启用）

提前在环境变量中设置 `TWIGBROWSE_TEST_API_KEY`、`TWIGBROWSE_TEST_BASE_URL` 和 `TWIGBROWSE_TEST_MODEL`，不要将密钥提交到 Git。

```sh
TWIGBROWSE_LIVE_TEST=true mvn -f research/htmlunit-spike/pom.xml -Dtest=LiveModelTest test
```

该测试访问 配置的兼容 Chat Completions 模型接口、DuckDuckGo HTML 搜索和官方 Spring 文档；发送的任务仅涉及公开文档。它要求模型通过 Spring AI 调用搜索和页面读取工具。搜索限两次，读取限两次。外部站点、模型或网络故障会导致失败，默认构建不依赖外网测试。

LiveProbe 为独立网页探测入口，JavaScript 默认关闭，可用 `-Dprobe.javascript=true` 启用。它不提供生产级 URL 校验或隔离边界。

本机实测 Big Pickle 返回 HTTP 400：免费层只能在 OpenCode 使用，因此真实模型端到端验证尚未通过；不要用客户端伪装绕过提供方限制。

最终验证使用 DeepSeek `deepseek-flash`，13 项测试全部通过，其中真实模型调用搜索与读取各 1 次。该 DeepSeek 探测显式关闭 thinking 模式；不验证思考模式协议。
