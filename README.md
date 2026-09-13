# TwigBrowse

English | [简体中文](README.zh-CN.md)

**Web search and browser tools for Spring AI agents, with one Starter dependency.**

TwigBrowse embeds HtmlUnit in your JVM. No desktop, installed browser, Chromium download, Node.js or Deno is required.

**Status:** `0.1.1` released on GitHub. Tested baseline: **Java 17 · Spring Boot 4.1.1 · Spring AI 2.0.1 · HtmlUnit 5.5.0**.

## What it solves

- **An agent needs current web information.** Give it search, source URLs, navigation and article reading through Spring AI tools.
- **One search provider can fail.** Try Bing first, then DuckDuckGo HTML; configure the order or supply your own provider.
- **A page requires interaction.** Query its DOM, fill inputs, click links, select options and wait for dynamic elements.
- **Browser installations complicate server deployment.** Run entirely in Java using a regular Maven dependency.
- **A shared browser can mix users' state.** Each top-level ChatClient request gets its own browser state and element references, with cleanup on completion, failure and stream cancellation.

This is a DOM browser, not a full Chromium replacement. Shared-JVM isolation is not an operating-system sandbox; see [limitations](#limits-and-security).

## Fastest distribution: JitPack

[JitPack](https://jitpack.io/#pigeon2049/twigbrowse) builds Maven artifacts directly from a GitHub tag or commit. The repository includes [jitpack.yml](jitpack.yml) with Java 17 and a root-only Maven build. No manual JAR upload is needed. [JitPack multi-module documentation](https://docs.jitpack.io/building/#multi-module-projects).

Once this code is pushed and a JitPack build succeeds, use:

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

This uses the published `v0.1.1` tag through JitPack. This is a multi-module repository: the group is **`com.github.pigeon2049.twigbrowse`**, not the planned Maven Central group. Do not mix both dependency variants in one application.

Remote JitPack publication has not been triggered or verified yet. After the first build, verify its generated module list and transitive POM before documenting a version for consumers. You can already use the local build below.

## Quick start

For local development, install the library from this repository:

```sh
mvn install
```

Add TwigBrowse alongside your existing Spring AI model starter:

```xml
<dependency>
    <groupId>io.github.pigeon2049</groupId>
    <artifactId>twigbrowse-spring-boot-starter</artifactId>
    <version>0.1.1</version>
</dependency>
```

Use Spring's auto-configured builder as usual:

```java
@Service
class ResearchAgent {
    private final ChatClient assistant;

    ResearchAgent(ChatClient.Builder builder) {
        this.assistant = builder.build();
    }

    String answer(String question) {
        // All browser tools are already available; the model chooses when to use them.
        return assistant.prompt()
            .user(question)
            .call().content();
    }
}
```

No annotation or manual `.tools(...)` call is needed. Existing business tools are preserved. Normal questions allocate no browser resources.

`ChatClient.create(model)`, static `ChatClient.builder(model)` and direct `ChatModel.call()` bypass Spring's builder customizers. For multiple models, apply Spring AI's `ChatClientBuilderConfigurer`. Your model must support tool calling.

## Browser tools

| Tool | Purpose |
| --- | --- |
| `web_search(query, limit)` | Search with provider fallback; up to 10 results |
| `web_navigate(url)` | Open an HTML page; return `pageId`, text and element refs |
| `web_snapshot(pageId)` | Read the current page and refresh interactive refs |
| `web_query(pageId, selector)` | Query DOM with CSS; return text, common attributes and refs |
| `web_attribute(pageId, ref, name)` | Read an HTML attribute, distinct from a JS property |
| `web_click(pageId, ref)` | Click an element; may navigate or submit a form |
| `web_type(pageId, ref, text)` | Replace a supported text input or textarea value |
| `web_select(pageId, ref, value)` | Select a dropdown option by value |
| `web_check(pageId, ref, checked)` | Toggle a checkbox or select a radio button |
| `web_wait(pageId, selector, timeoutMillis)` | Wait up to 5 seconds for a DOM match |
| `web_read(pageId, offset)` | Read article/main text in chunks; continue with `nextOffset` |
| `web_close(pageId)` | Close a page and release its slot |

A typical agent flow is **search → navigate → query → interact → read**. Navigation, snapshots, queries and actions return fresh refs and invalidate previous refs. A page or ref cannot be used by another request. `web_read` takes an opened `pageId`, not a URL; for long pages, continue with the returned `nextOffset` until it is null.

Tool errors contain a stable code such as `UNKNOWN_PAGE`, `STALE_REFERENCE`, `ELEMENT_TIMEOUT` or `CAPACITY`, allowing the agent to recover. Content is marked as untrusted data in tool descriptions.

## Common configuration

All settings are optional:

```yaml
twigbrowse:
  enabled: true                  # false disables TwigBrowse auto-configuration
  search:
    engines: [bing, duckduckgo]   # ordered fallback; [bing] also works
  browser:
    preset: CHROME               # CHROME / EDGE / FIREFOX / FIREFOX_ESR
    language: zh-CN
    accept-language: "zh-CN,zh;q=0.9,en;q=0.8"
    time-zone: Asia/Shanghai
    # user-agent: "Your custom User-Agent"
    # platform: "Win32"
  network-timeout: 8s
  script-timeout: 2s
  operation-timeout: 35s
  session-idle-timeout: 5m
  max-sessions: 16
  max-pages: 8
  max-text-chars: 12000        # size of each web_read chunk; follow nextOffset for the rest
  java-script-enabled: true
  allow-private-network: false
  proxy:
    enabled: false
    host: 127.0.0.1
    port: 8080
    # username: proxy-user
    # password: ${TWIGBROWSE_PROXY_PASSWORD}
    # socks: false
```

Search uses the international Bing entry point (`www.bing.com`, which may redirect to a regional endpoint) first and `html.duckduckgo.com` second, with JavaScript disabled. The first nonempty parsed result wins. Errors, timeouts, challenges and unparseable/empty pages fall through to the next engine. Search reports the actual provider and every attempt status; total failure is **not** presented as “no results.” No search API key is required, but public search pages may throttle, block or change layout.

`network-timeout` bounds individual network operations; it is not a complete provider deadline. The request's `operation-timeout` limits the whole search operation and may end it before every provider is attempted. See [search details](docs/search.md).
Set `proxy.enabled=true` to route search and page navigation through one HTTP or SOCKS proxy. Keep proxy passwords in an environment variable or secret manager.

### Per-request browser overrides

The default is HtmlUnit's Chrome preset. Application configuration overrides that preset; a request can override only selected fields:

```java
import io.github.pigeon2049.twigbrowse.core.BrowserProfile;
import io.github.pigeon2049.twigbrowse.autoconfigure.TwigBrowseContext;

String answer = client.prompt()
    .user("Search for the official documentation in English.")
    .toolContext(Map.of(TwigBrowseContext.BROWSER_PROFILE,
        BrowserProfile.builder()
            .language("en-US")
            .acceptLanguage("en-US,en;q=0.9")
            .timeZone("UTC")
            .build()))
    .call().content();
```

Non-null fields override application defaults for both search and navigation. The immutable profile lasts through that request's tool loop; it does not change other requests or global browser presets. Request overrides are trusted application parameters, not model-supplied session identifiers.

The supported “fingerprint” fields are HTTP/navigator/time-zone settings. A custom UA does not automatically synchronize every client hint or appVersion field, nor reproduce Chrome's TLS, Canvas or WebGL fingerprint. Prefer a preset for consistent browser behavior.

### Add a search provider

Implement the public `SearchEngine` interface as a Spring bean and include its `id()` in `twigbrowse.search.engines`. A bean with a built-in ID replaces that adapter. Implementations must be thread-safe, respect timeouts and use the supplied per-attempt WebClient. Unknown or duplicate IDs fail startup. [Extension example](docs/search.md#扩展).

## Runnable example

The [CLI example](examples/README.md) demonstrates automatic tool injection, web search/read, streaming and request-specific browser settings:

```sh
mvn install
mvn -f examples/cli/pom.xml package
java -jar examples/cli/target/twigbrowse-example-cli-0.1.1.jar --example.offline=true
```

The offline mode checks wiring without a model key or web requests. For real tasks, inject `TWIGBROWSE_EXAMPLE_API_KEY` and follow the example instructions.

**Examples are standalone projects, excluded from the root Maven reactor and all published library JARs.**

## Artifacts and publishing

| Channel | Intended use | Address / status |
| --- | --- | --- |
| Local Maven | Development | `mvn install`, version `0.1.1` |
| JitPack | Simplest early distribution from GitHub | [Build page](https://jitpack.io/#pigeon2049/twigbrowse); remote build not yet verified |
| Maven Central | Recommended public dependency distribution | Planned coordinates: `io.github.pigeon2049:twigbrowse-spring-boot-starter`; **not published yet** |
| GitHub Releases | Download library JARs, POMs and checksums | [Releases](https://github.com/pigeon2049/twigbrowse/releases); no release assets published yet |
| GitHub Packages | Optional, not the default consumer repository | Requires Maven authentication; see [publishing plan](docs/publishing.md) |

The starter is a small dependency entry point, not a standalone executable or shaded browser bundle. Maven resolves `autoconfigure`, `core` and their dependencies. A single downloaded starter JAR is insufficient by itself. Planned Maven repository paths and release steps are documented in the [publishing plan](docs/publishing.md).

## Testing

```sh
mvn verify
```

Normal tests use local HTTP fixtures and deterministic models. They cover DOM actions, dynamic fetch, redirects, browser profile headers/navigator values, cookies/storage isolation, invalid refs, search fallback, limits, cleanup, full agent tool loops and streaming.

Real network/model tests are opt-in and send only a public documentation task:

```sh
export TWIGBROWSE_LIVE_TEST=true
export TWIGBROWSE_TEST_BASE_URL=https://api.deepseek.com
export TWIGBROWSE_TEST_MODEL=deepseek-flash
# Inject TWIGBROWSE_TEST_API_KEY securely; never commit it.
mvn -Dtest=LiveSearchTest,LiveStarterTest -Dsurefire.failIfNoSpecifiedTests=false verify
```

Validation details: [45 offline + 6 opt-in live tests](docs/testing.md).

## Limits and security

Each top-level call or stream subscription has a temporary session. Navigation allocates its own WebClient lazily; each search-provider attempt uses a separate WebClient. Operations within a session are serialized. Browser state and authentication do not persist across chat turns. Application ChatMemory isolation remains the application's responsibility.

The default limits are 16 active sessions, 8 pages per session, 128 operations per request, 16 queued operations and 12,000 output characters. Unsafe schemes, credential-bearing URLs and private/reserved destinations are blocked by default. WebSockets, image downloads and popups are disabled. There is no arbitrary JS-evaluation, file-upload, download or screenshot tool.

Sessions that remain open through a manual integration are automatically closed after `session-idle-timeout` (5 minutes by default). A normal top-level ChatClient call still closes its temporary session as soon as the call, error or stream subscription ends.

HtmlUnit has partial modern-page compatibility. DOM presence does not imply visual visibility. JVM threads do not form a security sandbox; cancellation cannot forcibly stop every engine call, the 4 MiB response limit is checked after download, and DNS checks are not pinned to socket addresses. See [SECURITY.md](SECURITY.md) before deploying.

[Architecture](docs/architecture.md) · [Integration design](docs/integration-design.md) · [Research](docs/research.md) · [Contributing](CONTRIBUTING.md)

Licensed under [Apache License 2.0](LICENSE).
