# 接入方式比较：默认注册还是 @TwigBrowse

调研基线：Spring Boot 4.1.1、Spring AI 2.0.1，2026-09-14。默认注册方案已在正式 Starter 实现；注解暂不提供。

## 推荐

**首版采用 Starter 默认注册工具，允许配置关闭。** 若后续确有同一应用内按客户端选择的需求，再添加 `@TwigBrowse`，优先标在 `@Bean ChatClient` 或 `@Bean ChatClient.Builder`，不以 ChatModel 为主要扩展层。

“默认注册”表示工具对模型可用，不表示每次提问必定联网；是否调用由模型及业务工具策略决定。浏览器会话在第一次使用工具时懒创建，避免应用启动就为所有模型创建浏览器。

## 三种方案

| 方案 | 调用方体验 | 实现方式 | 边界 |
| --- | --- | --- | --- |
| Starter 默认注册 | 添加依赖，继续使用自动配置 Builder | ChatClientBuilderCustomizer + defaultTools/defaultToolCallbacks | 手工 new/static builder 路径不会自动套用 Spring Bean 自定义器 |
| @TwigBrowse 标在 ChatModel Bean 上 | 标记某个模型 | 包装模型/修改其选项，或额外管理模型与客户端的映射 | 多客户端共享模型时作用域偏大；具体模型类型注入、stream、选项复制和生命周期更复杂；直接 model.call 不自动拥有 Agent 工具循环 |
| @TwigBrowse 标在 ChatClient Bean 上 | 标记某个 Agent 客户端 | Bean 元数据识别 + client.mutate().defaultTools(...).build() | 只增强被选中的客户端；需验证 Bean 后处理顺序及与其他自定义器的组合 |

ChatClientBuilderCustomizer 的公开签名只有 `customize(ChatClient.Builder)`，Builder 没有公开的模型读取接口。因此，不能仅靠这个自定义器可靠判断其底层模型 Bean 上是否存在注解。可以设计模型装饰器，但不能称为“贴个注解就天然生效”。

## 首版使用方式

调用方添加 `twigbrowse-spring-boot-starter` 后仍沿用：

```java
@Bean
ChatClient assistant(ChatClient.Builder builder) {
    return builder.build();
}
```

配置关闭：

```yaml
twigbrowse:
  enabled: false
```

内部以条件自动配置注册无状态工具适配器和 ChatClientBuilderCustomizer。默认工具应与应用已有工具合并，遇到重名明确报错；不覆盖模型选项、业务 system prompt、会话身份或已有 advisor。缺少模型能力时给出明确诊断，不能假装已联网。

关闭应禁用本 Starter 的注册及会话管理组件；不全局篡改其他来源工具。不要为了拦截所有手工客户端去反射 Spring AI 私有字段。

## 可选注解的合理位置

下面是未来设计示意，当前没有 `@TwigBrowse` 实现：

```java
@Bean
@TwigBrowse
ChatClient researcher(@Qualifier("researchModel") ChatModel model) {
    return ChatClient.builder(model).build();
}
```

Java 注解使用 PascalCase，即 `@TwigBrowse`。建议只支持 Bean 工厂方法和明确的类型级标记；不把普通字段注解宣称为有效的全局开关。

注解选择的是客户端能力边界：同一个 ChatModel 可以被“联网研究助手”和“不联网摘要助手”复用。利用 ChatClient.mutate 的公共 API 增强一个客户端，不必修改共享模型。若新增 annotated 模式，应明确默认注册与注解模式互斥，避免用户误以为只标记的 Bean 才联网、实际却全局启用。

## 为什么不优先标 ChatModel

Spring AI 2.x 将工具执行循环放在 ChatClient advisor 链，ChatModel 是模型调用层。[工具调用文档](https://docs.spring.io/spring-ai/reference/api/tools.html)

给 ChatModel 添加工具选项可以让模型知道工具，但不自动解决循环执行、会话资源清理与请求身份传播。装饰 ChatModel 也必须正确支持 getOptions()/call()/stream()，保留提供者特有配置；替换 Bean 可能影响按 OpenAiChatModel 等具体类型注入的代码。

这条路线可以实现，但对当前“小依赖、无感、易维护”的目标，没有默认 Builder 自定义器直接。

## 实测依据与限制

本地使用实际 2.0.1 JAR 核实公开 API，并在研究模块验证：

- 自动配置 Builder 可注册工具，并完成模型请求工具 → HtmlUnit 读取页面 → 工具结果回传模型的循环（模型使用可控测试桩）。
- `WiringStyleTest` 的 3 个对照测试已通过：默认工具与业务工具合并、静态 Builder 绕过自定义器、单客户端 mutate 增强不影响原客户端/共享模型。
- 注解扫描和 BeanPostProcessor 尚未实现；不能把 mutate 测试当作完整注解方案的生产验证。

官方说明手工 ChatClient.builder/create 会绕过自动配置自定义器；多模型场景可通过 ChatClientBuilderConfigurer 显式应用配置。[ChatClient 文档](https://docs.spring.io/spring-ai/reference/api/chatclient.html)

参考：[ChatClientBuilderCustomizer](https://docs.spring.io/spring-ai/docs/current/api/org/springframework/ai/chat/client/ChatClientBuilderCustomizer.html)。
