# TwigBrowse Web Example

A tiny Spring Boot application with an embedded HTML page. It uses Spring AI and TwigBrowse so a user can ask ordinary questions about any public website, such as:

> 帮我总结今天 Hacker News 上都在说什么，具体内容有哪些，大家评论区在讨论什么？

The model chooses when to search, open pages, inspect the DOM, read long pages in chunks, and close pages. The demo does not hard-code a target site; users can provide a URL or ask for a web research task in their own words. Responses stream over SSE and render Markdown with `marked` and `DOMPurify` in the embedded page. There is no frontend build step or external browser dependency.

## Run

From the repository root, install the library first, then start the independent example:

```sh
mvn install
export TWIGBROWSE_EXAMPLE_API_KEY=your-key
mvn -f examples/web/pom.xml spring-boot:run
```

Open http://localhost:8080. The default endpoint is DeepSeek; override `TWIGBROWSE_EXAMPLE_BASE_URL` and `TWIGBROWSE_EXAMPLE_MODEL` for another provider.

For mainland or restricted networks, configure `TWIGBROWSE_PROXY_ENABLED`, `TWIGBROWSE_PROXY_HOST`, `TWIGBROWSE_PROXY_PORT` and optional username/password. The page is served from the application itself.


## Conversations (TwigBrowse 0.1.3)

The server uses its HTTP session cookie to isolate each user's chat/tool history and browser. Follow-up questions retain actual tool results, including source URLs, page IDs and element references; existing pages can be clicked across turns. The demo uses the starter's public `TwigBrowseContext.BROWSER_SESSION` option. It does not copy the starter's browser tools.

Try consecutive messages:

> 帮我总结今天 Hacker News 上都在说什么？
>
> AI Agent 撒谎、作弊、合谋那篇，具体说了什么？
>
> 大家在评论区争论什么？

The site is not hard-coded. The current front page can change, so refer to an article actually listed in the first answer. The new-conversation button clears history and closes the browser. Same-session concurrent turns are rejected. Idle conversations are removed after five minutes (checked every 15 seconds); the next message starts a fresh conversation. Browser sessions reclaimed by the library are recreated without stale tool references. History retains up to eight user turns and prunes whole tool exchanges when the character budget is exceeded.

```yaml
demo:
  conversation-idle-timeout: 5m
  turn-timeout: 180s
  max-conversations: 64
twigbrowse:
  session-idle-timeout: 10m
spring:
  mvc:
    async:
      request-timeout: 200s # Keep above demo.turn-timeout.
```

SSE contains JSON-encoded `delta`, `status`, `done`, and `error` events. This preserves Markdown whitespace and shows progress during browser calls. Turn deadlines, errors, and disconnected clients reset their browser; completed chat history remains. Logs record request-local tool names, outcomes and durations without credentials or page contents. Conversation storage is in memory and intended for one demo instance; restart clears it.

Run deterministic regression tests with `mvn -f examples/web/pom.xml test`. They exercise real HtmlUnit pages with a scripted model, including follow-up clicks, original comment links, user isolation, reset, idle cleanup, deadlines, and streamed whitespace. Real model/network verification is separate from these repeatable tests.

For opt-in real network/model testing, run the demo with its API key, then run
`cd examples/web && python3 src/test/python/live_conversation.py`. It sends three ordinary user messages through the actual SSE endpoint using the same session cookie. Responses and timings are saved under `target/live-conversation/`. The test requires that the referenced story is present in the current summary; inspect the saved answers and tool timing logs to verify source continuity as live news changes.


This example is deliberately outside the root Maven reactor and has `maven.deploy.skip=true`. Its app classes, HTML, tests and dependencies are not added to the starter JARs. To create a standalone application JAR explicitly:

```sh
mvn -f examples/web/pom.xml package
java -jar examples/web/target/twigbrowse-example-web-0.1.3.jar
```
