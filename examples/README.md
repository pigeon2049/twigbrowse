# Examples

These are standalone applications, **not root reactor modules** and **not included in library JARs**. The CLI POM also skips deployment.

## Build and check without network calls or API keys

From the repository root:

```sh
mvn install
mvn -f examples/cli/pom.xml package
java -jar examples/cli/target/twigbrowse-example-cli-0.1.0.jar --example.offline=true
```

The offline mode prints the 12 auto-registered tools. It verifies wiring only; it does not pretend to search the web.

## Search, navigate and read

Inject `TWIGBROWSE_EXAMPLE_API_KEY` into the environment. The default OpenAI-compatible provider configuration is DeepSeek (`https://api.deepseek.com`, `deepseek-flash`); override `TWIGBROWSE_EXAMPLE_BASE_URL` and `TWIGBROWSE_EXAMPLE_MODEL` for your provider.

```sh
java -jar examples/cli/target/twigbrowse-example-cli-0.1.0.jar \
  'Find Spring AI official tool calling documentation, open a result, query the article DOM, read it and close the page. Summarize the main section with its URL.'
```

The default prompt is also a complete workflow: `web_search` → `web_navigate` → `web_query` → `web_read` → `web_close`. The model receives page IDs and DOM refs from each preceding tool result; it must not invent them.

## Stream the answer and override browser language

```sh
java -jar examples/cli/target/twigbrowse-example-cli-0.1.0.jar \
  --example.stream=true --example.language=zh-CN \
  '搜索 Spring AI 官方文档，打开结果并阅读正文，附上来源。'
```

## Inspect and interact with a page you control

```sh
java -jar examples/cli/target/twigbrowse-example-cli-0.1.0.jar \
  'Open https://your-test-site.example/form, query input and select elements, describe their names and available options. Do not submit the form.'
```

Replace the URL with an accessible page you control. Use `web_query` to obtain refs, then `web_type`, `web_select`, `web_check`, `web_click` and `web_wait` as needed. References expire after a new query/snapshot or action. For intentionally local fixtures, pass `--twigbrowse.allow-private-network=true`; keep the default for public web access.

Search order, browser profile and resource settings are the same as the library configuration in the [main README](../README.md). The runnable example contains its dependencies; published library JARs remain regular Maven libraries.
