# TwigBrowse

[English](README.md) | 简体中文

为 Spring AI Agent 提供多引擎搜索和 DOM 操作的 Spring Boot Starter。纯 Java、无桌面，不需要安装浏览器、Node.js 或 Deno。

当前版本为 GitHub Release `0.1.1`。基线：**Java 17、Spring Boot 4.1.1、Spring AI 2.0.1、HtmlUnit 5.5.0**。

## 解决什么问题

- 给 Agent 补上实时网页信息、搜索来源和正文读取能力。
- Bing 失败时自动切换 DuckDuckGo，避免只依赖一个搜索入口。
- 对需要交互的页面提供 CSS 查询、填写、点击、选择和等待工具。
- 纯 Java 部署，减少安装和维护浏览器进程的成本。
- 请求间隔离 Cookie、存储、页面及元素引用，防止正常调用时串用上下文。

## 最便捷分发：JitPack

[JitPack](https://jitpack.io/#pigeon2049/twigbrowse) 可以直接按 GitHub tag 或 commit 构建 Maven 制品，无需手工上传 JAR。项目已有 [jitpack.yml](jitpack.yml)，使用 Java 17，仅构建根模块，不包含 example。参见 [官方多模块说明](https://docs.jitpack.io/building/#multi-module-projects)。

代码推送后、JitPack 远端构建成功即可使用：

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>com.github.pigeon2049.twigbrowse</groupId>
        <artifactId>twigbrowse-spring-boot-starter</artifactId>
        <version>v0.1.1</version>
    </dependency>
</dependencies>
```

这里使用已发布的 `v0.1.1` tag，通过 JitPack 获取。本项目是多模块，groupId 为 **`com.github.pigeon2049.twigbrowse`**，与规划中的 Central 坐标不同，同一应用选择一种渠道即可。

远端 JitPack 构建如遇缓存或延迟，可先使用下面的本地安装方式。

## 接入

先在本项目执行 `mvn install`，应用添加依赖，并保留自己使用的 Spring AI 模型 Starter：

```xml
<dependency>
    <groupId>io.github.pigeon2049</groupId>
    <artifactId>twigbrowse-spring-boot-starter</artifactId>
    <version>0.1.1</version>
</dependency>
```

```java
@Bean
ChatClient assistant(ChatClient.Builder builder) {
    return builder.build();
}
```

使用 Spring 自动配置的 Builder 即默认注册全部 12 个工具，与业务已有工具合并。不要求注解，不指定模型供应商。是否调用工具由模型决定，普通问答不创建浏览器。

`ChatClient.create(model)`、静态 `ChatClient.builder(model)` 和直接 `ChatModel.call` 不自动应用此配置。多模型应用可使用 Spring AI 的 `ChatClientBuilderConfigurer`。关闭整个 Starter：`twigbrowse.enabled=false`。

## 多搜索引擎

默认按 **国际必应入口（可能自动重定向到区域地址） → DuckDuckGo HTML** 顺序尝试，首个成功即返回。网络错误、HTTP 错误、人机验证和无可提取结果会尝试下一个引擎。不破解验证码；全部失败时明确返回 `data.success=false` 和每个引擎的状态。工具自身执行失败则为顶层 `success=false`。

```yaml
twigbrowse:
  enabled: true
  search:
    engines: [bing, duckduckgo]
  network-timeout: 8s
  operation-timeout: 35s
  session-idle-timeout: 5m
  proxy:
    enabled: false
    host: 127.0.0.1
    port: 8080
    # username: proxy-user
    # password: ${TWIGBROWSE_PROXY_PASSWORD}
    # socks: false
```

可以调整顺序、删除不可达引擎或只保留一个。无需搜索 API Key；公开搜索结果页仍可能限流或变更，不能保证所有墙内网络都可用。搜索返回实际引擎、标题、URL、可提取的摘要和尝试状态。
设置 `proxy.enabled=true` 后，搜索和页面导航都会通过同一个 HTTP 或 SOCKS 代理。建议从环境变量或密钥管理器注入代理密码。

注册 `SearchEngine` Bean 可以扩展自己的搜索 API 或内部搜索服务，再把 `id()` 放入 `search.engines`；同 ID Bean 覆盖内置适配器。实现须线程安全，遵守超时，使用传入的独立浏览器；配置为空、重名或未知 ID 会启动失败。详见 [搜索设计](docs/search.md)。

## DOM 工具

| 工具 | 操作 |
| --- | --- |
| `web_search(query, limit)` | 多引擎搜索，最多 10 条 |
| `web_navigate(url)` | 打开 HTML 页面，返回 pageId、文本、元素引用 |
| `web_snapshot(pageId)` | 当前页面文本及最多 100 个可操作元素；链接返回绝对 `href` |
| `web_query(pageId, selector)` | CSS 选择器查询 DOM，返回文本、常用属性和 refs |
| `web_attribute(pageId, ref, name)` | 读取指定 HTML 属性 |
| `web_click(pageId, ref)` | 点击链接、按钮或查询得到的元素 |
| `web_type(pageId, ref, text)` | 替换文本框内容，最多 4000 字符 |
| `web_select(pageId, ref, value)` | 按 value 选择下拉选项 |
| `web_check(pageId, ref, checked)` | 勾选/取消复选框，选择单选项 |
| `web_wait(pageId, selector, timeoutMillis)` | 等待 DOM 元素出现，最多 5 秒 |
| `web_read(pageId, offset)` | 分片读取 article/main 正文，按 `nextOffset` 继续 |
| `web_close(pageId)` | 关闭页面 |

模型使用路径：搜索 → 导航结果 URL → 按 CSS 查询 → 用返回的 ref 操作 → 读取。快照、查询和变更操作返回新引用，旧引用失效；跨请求 pageId/ref 被拒绝。`web_read` 接受已打开的 pageId，不直接接受 URL。

不提供任意 JavaScript 执行、文件上传、下载或截图工具。CSS 查询基于 DOM；不等于像素可见性或完整 Chromium 渲染。动态页面兼容性受 HtmlUnit 限制。

## 浏览器配置与单次覆盖

默认使用 HtmlUnit 的 `CHROME` 预设，其余字段继承该预设。应用可设置：

```yaml
twigbrowse:
  browser:
    preset: CHROME          # CHROME / EDGE / FIREFOX / FIREFOX_ESR
    language: zh-CN
    accept-language: "zh-CN,zh;q=0.9,en;q=0.8"
    time-zone: Asia/Shanghai
    # user-agent: "自定义 User-Agent"
    # platform: "Win32"
```

单次调用覆盖，仅覆盖非 null 字段，未指定字段继承应用配置：

```java
import io.github.pigeon2049.twigbrowse.core.BrowserProfile;
import io.github.pigeon2049.twigbrowse.autoconfigure.TwigBrowseContext;

String answer = client.prompt()
    .user("搜索并阅读 Spring AI 的工具调用文档")
    .toolContext(Map.of(TwigBrowseContext.BROWSER_PROFILE,
        BrowserProfile.builder()
            .language("en-US")
            .acceptLanguage("en-US,en;q=0.9")
            .timeZone("UTC")
            .build()))
    .call().content();
```

这些配置由应用传入，模型不能通过工具参数更换请求身份或读取其他会话。覆盖同时用于搜索和页面导航，保持到当前工具循环结束；不修改全局 BrowserVersion，不复用其他请求的 Cookie。

这里的“指纹”是 HtmlUnit 支持的 HTTP/navigator/时区字段。自定义 UA 不会自动同步所有 client hints、appVersion 等字段，不模拟 Chromium 的 TLS、Canvas 或 WebGL 指纹。需要一致的浏览器能力时优先选择预设。

## 生命周期与资源

每次顶层 `.call()`、每次流订阅建立一个临时请求上下文，在第一次网页工具操作时分配独立 worker；导航浏览器再懒创建。搜索的每次引擎尝试使用独立 WebClient，搜索站点之间也不共享 Cookie。

同请求操作串行，不同请求并发。成功、异常、流取消和应用关闭会触发清理。首版不保留跨轮登录状态或页面；应用的 ChatMemory 隔离仍由应用负责。

默认最多 16 个活跃会话、每会话 8 个页面、128 次操作、16 个排队操作；正文按每次 `web_read` 最多 12000 字符分片，可根据返回的 `nextOffset` 继续读取。可配置 `max-sessions`、`max-pages`、`max-text-chars`、`script-timeout`（默认 2s）和 `java-script-enabled`（默认 true）。搜索始终关闭 JavaScript。

如果业务通过底层会话 API 长时间持有页面，超过 `session-idle-timeout`（默认 5 分钟）后会被后台定时回收。普通 ChatClient 顶层调用仍会在完成、异常或流取消时立即关闭临时会话。

默认拒绝非 HTTP(S)、带凭据 URL、内网/保留地址；WebSocket、图片下载和弹窗默认关闭。`allow-private-network=true` 仅适用于应用主动需要访问受控内网的部署。

**共享 JVM 不是进程沙箱。** 超时取消不能强制终止所有底层执行；4 MiB 限制在响应下载后、解析前检查，不能限制下载期间的全部资源；DNS 检查未绑定最终 socket，存在重绑定边界。详见 [SECURITY.md](SECURITY.md)。

## 独立示例

[CLI 示例](examples/README.md)提供普通调用、流式输出及单次浏览器配置覆盖：

```sh
mvn install
mvn -f examples/cli/pom.xml package
java -jar examples/cli/target/twigbrowse-example-cli-0.1.1.jar --example.offline=true
```

离线模式无需 API Key，检查 12 个工具的自动注册。真实运行通过环境变量注入模型 Key，详见示例文档。**examples 不属于根 Maven 模块，不会被打入任何库 JAR，并禁用示例 deploy。**

## 制品与发布地址

| 渠道 | 用途与状态 |
| --- | --- |
| 本地 Maven | 使用 `mvn install`，版本 `0.1.1` |
| [JitPack](https://jitpack.io/#pigeon2049/twigbrowse) | 最省事的早期分发方式，按 Git tag/commit 自动构建，远端尚未验证 |
| Maven Central | 推荐正式依赖分发；规划坐标 `io.github.pigeon2049:twigbrowse-spring-boot-starter`，尚未发布 |
| [GitHub Releases](https://github.com/pigeon2049/twigbrowse/releases) | 规划提供库 JAR、POM、源码与校验和下载，尚无发布资产 |
| GitHub Packages | 可选；公开 Maven 包下载也需要认证，不作为默认接入入口 |

Starter 是 Maven 依赖入口，不是可单独运行的 fat JAR。手工下载单个 starter JAR 无法替代 Maven 解析其依赖。规划的 Maven 路径、GitHub 下载路径和发布前置步骤见 [发布方案](docs/publishing.md)。

验证记录：[38 项离线测试与 6 项联网测试](docs/testing.md)。

## 开发与验证

```sh
mvn verify
```

默认只运行本地可控测试，不访问真实搜索引擎和付费模型。联网测试显式启用：

```sh
export TWIGBROWSE_LIVE_TEST=true
export TWIGBROWSE_TEST_BASE_URL=https://api.deepseek.com
export TWIGBROWSE_TEST_MODEL=deepseek-flash
# 将 TWIGBROWSE_TEST_API_KEY 注入环境，勿提交凭据。
mvn -Dtest=LiveSearchTest,LiveStarterTest -Dsurefire.failIfNoSpecifiedTests=false verify
```

[架构](docs/architecture.md) · [接入方式比较](docs/integration-design.md) · [选型调研](docs/research.md) · [贡献说明](CONTRIBUTING.md)

采用 [Apache License 2.0](LICENSE)。
