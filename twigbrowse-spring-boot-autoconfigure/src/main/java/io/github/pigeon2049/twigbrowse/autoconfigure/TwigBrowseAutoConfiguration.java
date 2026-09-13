package io.github.pigeon2049.twigbrowse.autoconfigure;

import java.util.*;
import io.github.pigeon2049.twigbrowse.core.*;
import org.springframework.ai.chat.client.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(ChatClient.class)
@ConditionalOnProperty(prefix="twigbrowse", name="enabled", havingValue="true", matchIfMissing=true)
@EnableConfigurationProperties(TwigBrowseProperties.class)
public class TwigBrowseAutoConfiguration {
    @Bean @ConditionalOnMissingBean
    SearchService twigBrowseSearchService(TwigBrowseProperties properties, ObjectProvider<SearchEngine> customEngines) {
        Map<String, SearchEngine> available = new LinkedHashMap<>();
        for (SearchEngine engine : HtmlSearchEngine.values()) available.put(engine.id(), engine);
        Set<String> customIds = new HashSet<>();
        customEngines.orderedStream().forEach(engine -> {
            if (!customIds.add(engine.id())) throw new IllegalArgumentException("Duplicate custom search engine: " + engine.id());
            available.put(engine.id(), engine);
        });
        List<SearchEngine> selected = new ArrayList<>();
        for (String id : properties.getSearch().getEngines()) {
            SearchEngine engine = available.get(id);
            if (engine == null) throw new IllegalArgumentException("Unknown twigbrowse.search.engines entry: " + id);
            selected.add(engine);
        }
        return new SearchService(selected, properties.browserSettings(), properties.getBrowser().profile(), properties.proxySettings());
    }
    @Bean(destroyMethod="close") @ConditionalOnMissingBean
    BrowserSessionManager twigBrowseSessionManager(TwigBrowseProperties properties, SearchService search) {
        return new BrowserSessionManager(properties.browserSettings(), search, properties.getBrowser().profile(), properties.getSessionIdleTimeout(), properties.proxySettings());
    }
    @Bean @ConditionalOnMissingBean
    TwigBrowseTools twigBrowseTools() { return new TwigBrowseTools(); }
    @Bean @ConditionalOnMissingBean
    TwigBrowseSessionAdvisor twigBrowseSessionAdvisor(BrowserSessionManager sessions) { return new TwigBrowseSessionAdvisor(sessions); }
    @Bean
    ChatClientBuilderCustomizer twigBrowseChatClientCustomizer(TwigBrowseTools tools, TwigBrowseSessionAdvisor advisor) {
        return builder -> builder.defaultTools(tools).defaultAdvisors(advisor);
    }
}
