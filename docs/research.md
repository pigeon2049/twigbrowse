# HtmlUnit 可行性调研

日期：2026-09-14。环境：Linux aarch64，Temurin JDK 17.0.20.1，Maven 3.9.11。

## 结论

HtmlUnit 可以作为 TwigBrowse 首版的轻量 DOM 浏览器引擎，覆盖搜索、导航、点击、表单、正文提取及部分动态页面；不应将产品承诺定义为完整 Chromium 替代品。

目标组合固定为 **Spring Boot 4.1.1 + Spring AI 2.0.1 + HtmlUnit 5.5.0**。JDK 最低目标可降为 **17**，不依赖虚拟线程、FFM 或 JDK 25 特性。

本次验证代码位于 `research/htmlunit-spike`，与正式发布模块隔离。此处记录最初原型的验证结果；正式 Starter 的后续实现与测试见 README 和 architecture.md。测试桩、原型网页访问和生产多租户安全保证不能混为一谈。

## 版本依据

| 组件 | 固定版本 | 依据 |
| --- | --- | --- |
| HtmlUnit | 5.5.0 | Maven Central 实际 POM 的 source/target/release 均为 17，最低 Java 17 |
| Spring AI | 2.0.1 | 当前目标版本，使用 2.x 的 ChatClient、Builder 自定义扩展和 ToolCallingAdvisor |
| Spring Boot | 4.1.1 | 官方最低 Java 17；与 Spring AI 2.0.x 支持的 Boot 4.1.x 匹配 |
| Java | 17 | 独立下载的完整 JDK 17 编译和运行，不只是用高版本 javac 设置 release |

来源：[HtmlUnit 项目说明](https://github.com/HtmlUnit/htmlunit)、[HtmlUnit 制品 POM](https://repo.maven.apache.org/maven2/org/htmlunit/htmlunit/5.5.0/htmlunit-5.5.0.pom)、[Spring Boot 系统要求](https://docs.spring.io/spring-boot/system-requirements.html)、[Spring AI 入门](https://docs.spring.io/spring-ai/reference/getting-started.html)。

## 页面能力实测

| 能力 | 结果 | 验证边界 |
| --- | --- | --- |
| 导航与正文读取 | 通过 | 跟随本地链接，检查目标 URL 与正文 |
| 输入、表单提交 | 通过 | 模拟输入，点击提交，检查查询参数 |
| 点击事件修改 DOM | 通过 | onclick 修改正文 |
| XMLHttpRequest | 通过 | 异步取 JSON 后更新页面 |
| fetch | 默认 undefined；启用 polyfill 后通过 | 使用 setFetchPolyfillEnabled(true)，验证异步 DOM 更新 |
| Promise | 存在 | 类型探测；没有测试完整规范 |
| 可选链与空值合并 | 简单表达式通过 | 不等于所有现代 JS 语法均支持 |
| WebSocket | 对象存在 | 尚未验证真实连接与行为 |
| ES Module | 测试页未执行 | type=module + import 外部脚本未更新 DOM |
| 同源多会话状态 | 通过 | 两个 WebClient 并发，Cookie/localStorage/sessionStorage/DOM 各自独立；新客户端为空 |
| 简单无限循环 | 被配置的脚本超时终止 | 不证明所有原生调用、资源耗尽或异步任务可强制终止 |

本地测试中“现代 JS 能力探测”只报告支持程度，因此测试总体通过也不表示 ES Module 支持。完整测试结果将在下方验收记录中列明。

WebClient 官方说明其不是线程安全对象，宜由单线程使用。建议每个活跃会话持有独立 WebClient 和固定执行线程/执行队列；异步回调按引擎机制处理，业务侧不并发访问同一实例。[WebClient 文档](https://htmlunit.org/apidocs/org/htmlunit/WebClient.html)

## 真实公网读取

使用 HtmlUnit，关闭 JS 与 CSS；本机本轮网络结果如下，不能当作稳定性 SLA：

| 页面 | HTTP | 提取结果 |
| --- | --- | --- |
| DuckDuckGo HTML，查询 Spring AI tool calling | 200 | 10 个结果链接，正文 3537 字符 |
| Bing，相同查询 | 200 | 9 个结果链接，正文 6098 字符 |
| React /learn | 200 | 正文 14260 字符 |

React 正文可读取说明服务端输出 HTML 可提取，不代表 React hydration、SPA 路由或客户端交互已通过验证。搜索链接中存在跳转包装，需要解码并验证真实来源 URL。搜索页结构、验证码和网络状态可能改变，必须将阻塞与解析失败明确返回，不能包装为成功的空结果。

## 依赖成本

直接引用 HtmlUnit 5.5.0，按 Boot 4.1.1 BOM 对齐后的 runtime classpath：**14 个 JAR，合计约 9.83 MiB**。只统计 JAR 文件字节，不含 JVM、Spring/模型 SDK、Maven 插件、测试库、源码和缓存。该数字不是每会话内存占用，也不是完整 Starter 打包体积。

浏览器层包括 HtmlUnit、Rhino 衍生 JS 引擎、Neko HTML 解析器、CSS/XPath/CSP、WebSocket 客户端、Apache HttpClient 4 及 Commons 库。没有安装 Chromium、ChromeDriver、Node、Deno，也没有引入 Selenium。

生产核心只依赖页面引擎；模型提供者 SDK 由应用选择，不能为了本次 Big Pickle 测试将其强塞进 Starter。研究模块中的 OpenAI 兼容接入依赖仅用于测试。

## Starter 建议

- `twigbrowse-core`：会话和页面接口、HtmlUnit 实现、限额与生命周期。
- `twigbrowse-spring-boot-autoconfigure`：可信身份解析 SPI、工具映射、Builder 自动注册、配置与开关。
- `twigbrowse-spring-boot-starter`：依赖入口，不绑定模型厂商。
- 搜索走静态 HTML 路线优先，页面交互按需开启 JS/fetch polyfill；不以重新执行有副作用的点击作为自动重试手段。
- 会话归属来自可信业务上下文，模型只获得该会话内的页面/元素引用；引用必须经过归属及页面版本检查。
- 一次 Agent 顶层调用内自动复用会话，多轮场景接入可信租户、用户与 conversationId；缺少身份时用临时会话，绝不回退全局实例。
- 向模型提供精简正文与语义元素引用；不直接暴露 Java 对象、任意脚本执行或全量 DOM。

可完全隐藏引擎创建与工具注册，但无法无配置猜测业务用户身份。用户手动构造 ChatClient、绕过自动配置 Builder 的场景需显式接入。

## 安全与尚未覆盖的边界

内嵌会话隔离用于避免正常并发串数据，不构成恶意脚本的 OS 沙箱。尚未完成生产级 SSRF、防重绑定、跨源访问控制审计、恶意页面资源压测、会话 TTL、引用越权和流式取消验证；这些是实现阶段要求，不应由本次两个 WebClient 的验证推导为已完成。

脚本超时测试通过也不等于可以安全强杀 JVM 内任意线程。对外应承诺明确的执行上限和可观察失败；需要强安全边界的用户仍需独立受限执行环境。[HtmlUnit 安全说明](https://htmlunit.org/security.html)

## 已有方案与本项目定位

Playwright MCP、Chrome DevTools MCP 已有通用浏览器工具，但带来浏览器与外部运行时要求。QuickJS、Deno 解决 JS 执行，DOM、导航和页面 API 还需补齐。HtmlUnit 提供更接近所需的纯 Java 能力。

因此 TwigBrowse 的价值应是：**Spring AI 2.x 无感接入、轻量网页交互、明确会话生命周期、结构化结果和失败边界**，而不是重新发明 JS 引擎。此次公开检索未找到可直接确认完全满足这些约束的现成 Starter；这不代表不存在。

参考：[Playwright MCP](https://github.com/microsoft/playwright-mcp)、[QuickJS](https://bellard.org/quickjs/quickjs.html)、[Deno Web API](https://docs.deno.com/runtime/reference/web_platform_apis/)、[HtmlUnit 入门](https://htmlunit.org/gettingStarted.html)、[Spring AI 工具调用](https://docs.spring.io/spring-ai/reference/api/tools.html)。

## 验收记录

- JDK 17 下 HtmlUnit 与 Spring AI 2.0.1 / Boot 4.1.1 的 9 个本地测试通过，包含自动配置 Builder 的实际工具循环（可控模型桩）。
- Big Pickle 真实端到端测试在首次模型请求时收到 HTTP 400，服务端说明免费层只能在 OpenCode 使用。因此没有完成真实模型驱动的搜索→读取→回答；这与上述 HtmlUnit 公网搜索页面成功是不同验证项。
- 模型密钥只从仓库外私有配置读取，未写入代码或报告。真实模型测试默认跳过。
- 接入方式比较见 [默认注册与注解方案](integration-design.md)。

### 最终结果（本轮）

**13 个测试全部通过，无跳过**：9 个 HtmlUnit/工具循环测试、3 个接入方式对照测试、1 个真实 DeepSeek 联调测试。正式模块父工程的 `mvn validate` 也通过。

真实联调使用 `https://api.deepseek.com` 的 `deepseek-flash`，通过 Spring AI 2.0.1 的 OpenAI 兼容适配调用。为验证普通工具调用链，此测试关闭 DeepSeek thinking 模式；不覆盖思考模式下 reasoning_content 的多轮传递。

模型实际执行 **1 次 web_search + 1 次 web_read**，返回含 `https://docs.spring.io/spring-ai/reference/api/tools.html` 的回答。该测试使用显式 `.tools(...)`；自动注册路径由独立 Boot ApplicationContextRunner 测试覆盖。二者不能被描述成已经发布可用的完整 Starter。

最终建议：JDK 17、Boot 4.1.1、Spring AI 2.0.1、HtmlUnit 5.5.0；首版默认通过 Builder 自定义器注册工具，允许关闭。优先实现清晰的会话/引用管理与搜索结果提取，ES Module/完整 SPA 兼容不列为首版承诺。

机器可读验收记录见 [validation-2026-09-14.json](validation-2026-09-14.json)。
