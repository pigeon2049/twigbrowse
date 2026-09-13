# 贡献指南

TwigBrowse 当前处于早期实现阶段。较大改动请先提交 Issue，说明使用场景和拟议方案。

## 开发约定

- 使用 Java 17 与 Maven 3.9+。
- Java 包名前缀为 `io.github.pigeon2049.twigbrowse`。
- 不引入桌面或宿主机浏览器安装要求。
- 新依赖需说明必要性，并考虑传递依赖和运行成本。
- 保持会话归属明确，不使用全局浏览器状态，也不依赖 ThreadLocal 作为唯一会话身份来源。
- 测试涉及网页行为时优先使用可控本地页面；外网测试应独立、可选且可重复。

提交 PR 时说明改动目的、行为变化、验证方式和已知限制。功能改动应运行 `mvn verify` 并加入对应行为测试。

贡献内容按项目 Apache-2.0 许可证提供。

示例在 `examples/` 独立构建，不应添加到根 modules 或生产 JAR。先 `mvn install`，再 `mvn -f examples/cli/pom.xml verify`。
