# Browser and agent validation

Formal starter results: **45 offline tests + 6 opt-in live tests passed** on Java 17 / Boot 4.1.1 / Spring AI 2.0.1 / HtmlUnit 5.5.0. Two additional opt-in Hacker News agent tests are included and require live credentials. Machine-readable results: [starter-validation-2026-09-14.json](starter-validation-2026-09-14.json).

| Layer | Scenarios |
| --- | --- |
| Browser interaction | Navigation, redirects, text entry, form submission, DOM click handlers, CSS query, attributes, dropdowns, checkboxes and radios |
| Dynamic DOM | Click → fetch → wait → read; JS disabled behavior; script timeout; asynchronous element removal |
| Isolation | Concurrent cookies, origin localStorage, per-window sessionStorage, cross-request page/ref rejection, stale refs |
| Browser profile | Default presets, partial overrides, HTTP UA/Accept-Language, navigator language/platform/UA, JS timezone, unchanged defaults across concurrent requests |
| Resource/error handling | Page/session/operation caps, idle-session reaping, truncated text, 404, non-HTML, oversized response, invalid selectors, unsupported upload inputs, disabled/read-only fields, operation timeout, manager shutdown |
| Search | Bing/DDG HTML parsers and redirect unwrapping, ordered timeout/challenge fallback, all-failed result, fresh cookies per attempt, query/limit validation |
| Spring AI | Default 12 tools, business tools retained, disabled config, custom provider/profile configuration, invalid configuration, missing session, manual-builder behavior |
| Agent lifecycle | Full search tool loop, navigation → query → type → query → click → read, invalid-page recovery, sync/stream, concurrent requests, model failure and stream cancellation cleanup |
| Packaging | Root library build/install; separate CLI package; runnable offline wiring check; no example/research/test classes in library JARs |

Run `mvn verify` for local deterministic tests. External tests are explicitly enabled with the environment variables documented in the README. The real model test is a functionality smoke test, not a ranking-quality evaluation or high-load benchmark.

## Network-specific observation

The test host returned synthetic private IPv6 addresses alongside IPv4 addresses for public search/documentation hosts. The default URL guard correctly rejected that mixed result. Successful real-network tests ran with `-Djava.net.preferIPv4Stack=true` only in the test JVM; the default private-network restriction stayed enabled. No host DNS/proxy settings were changed. This is an environment-specific observation, not a recommended global change for every application.

Both Bing and DuckDuckGo returned parseable results. Bing's ranking was broad for the public-documentation query; the model still completed search/navigation/read and cited the official tool calling page. Search fallback currently detects availability/parse failures, not result relevance.

## What these checks do not establish

No claim of full modern-browser compatibility, OS sandboxing, DNS-rebinding resistance, forced termination of all JVM work, sustained high-load reliability or availability on every mainland network. Source constraints are described in SECURITY.md. Live model credentials remain outside the repository.
