# 多搜索引擎

## 配置与返回

内置 `bing`（`www.bing.com` 国际入口，可能自动重定向到区域地址）、`duckduckgo`（`html.duckduckgo.com`），默认按此顺序。配置 `twigbrowse.search.engines` 可调整顺序或仅选单个。

首个返回非空可解析结果的引擎获选，后续不执行。返回包含 `engine`、`results`、`attempts`；超时、人机验证、HTTP/连接异常、空页面或版式变化会转到下一引擎。不把整页链接或广告导航当搜索结果，不破解验证码。

`NO_RESULTS_OR_LAYOUT_CHANGE` 有意保留歧义：HTML 模式无法可靠区分真实零结果、阻断和改版，因此继续降级。全部不可用时 `SearchResponse.success=false`。顶层工具封装的 success 只表示操作是否执行完成，模型须同时检查 data.success。

默认网络连接/读取超时 8s；整个 web_search 受会话 operation-timeout（35s）约束。它不是每个引擎固定 8s 的总时间保证：DNS、重定向和多个底层请求会占用额外时间。总操作超时会关闭当前请求会话，不能承诺始终有时间尝试所有引擎。

不为正常测试依赖真实搜索站点。适配器有可控 HTML 夹具、超时/人机验证/全失败/结果去重测试；`LiveSearchTest` 单独探测每个引擎，再验证实际默认降级链。一次网络探测不能代表中国大陆所有运营商网络。

## 扩展

```java
@Bean
SearchEngine companySearch() {
    return new SearchEngine() {
        @Override public String id() { return "company"; }
        @Override public List<SearchResult> search(WebClient browser, String query, int limit) throws Exception {
            // 使用传入浏览器访问自有 HTTP 搜索接口，解析成 SearchResult。
            // 无结果返回空集合；超时/故障抛异常，由 SearchService 降级。
            return lookupCompanyIndex(browser, query, limit);
        }
    };
}
```

配置 `engines: [company, bing, duckduckgo]`。同 ID Bean 可替代内置适配器，重复自定义 ID 或重复配置拒绝启动。扩展实现必须线程安全、限长结果并遵守取消及超时；勿跨请求保存传入 WebClient，也不要用额外不受限制的 HTTP 客户端绕过网络策略。

## 网页适配边界

- 必应解析有机结果 h2 链接，识别其已知 base64 URL 包装。
- DuckDuckGo 解析 HTML result 链接，解开 uddg 参数。
- 摘要尽力提取，可为空；每条结果始终包含可点击 HTTP(S) URL。
- 不包含 Google、搜狗、360 等未经实现和测试的适配器。后续可通过同一 SPI 扩展。
