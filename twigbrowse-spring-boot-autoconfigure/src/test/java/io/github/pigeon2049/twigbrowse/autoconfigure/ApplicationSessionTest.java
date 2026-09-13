package io.github.pigeon2049.twigbrowse.autoconfigure;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import io.github.pigeon2049.twigbrowse.core.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationSessionTest {
    @Test void borrowedSessionSurvivesCallStreamErrorAndCancellationAndIsNeverSharedByDefault() {
        AtomicReference<BrowserSession> seen = new AtomicReference<>();
        var waiting = new java.util.concurrent.CountDownLatch(1);
        var model = new TwigBrowseAutoConfigurationTest.CaptureModel() {
            @Override public ChatResponse call(Prompt prompt) {
                seen.set((BrowserSession)((ToolCallingChatOptions)prompt.getOptions()).getToolContext().get(TwigBrowseSessionAdvisor.SESSION_KEY));
                if (prompt.getContents().contains("wait")) waiting.countDown();
                if (prompt.getContents().contains("fail")) throw new IllegalStateException("fixture failure");
                return TwigBrowseAutoConfigurationTest.answer("ok");
            }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.defer(() -> { var response = call(prompt); return prompt.getContents().contains("wait") ? Flux.never() : Flux.just(response); });
            }
        };
        new TwigBrowseAutoConfigurationTest().context(model).run(ctx -> {
            var manager = ctx.getBean(BrowserSessionManager.class);
            var client = ctx.getBean(ChatClient.Builder.class).build();
            try (var owned = manager.openSession()) {
                var context = Map.<String,Object>of(TwigBrowseContext.BROWSER_SESSION, owned);
                client.prompt("one").toolContext(context).call().content();
                assertSame(owned, seen.get()); assertFalse(owned.isClosed());
                client.prompt("two").toolContext(context).stream().content().collectList().block();
                assertSame(owned, seen.get()); assertFalse(owned.isClosed());
                assertThrows(Exception.class, () -> client.prompt("fail").toolContext(context).call().content());
                assertFalse(owned.isClosed());
                var subscription = client.prompt("wait").toolContext(context).stream().content().subscribe();
                assertTrue(waiting.await(5, java.util.concurrent.TimeUnit.SECONDS));
                subscription.dispose(); assertFalse(owned.isClosed());
                assertThrows(Exception.class, () -> client.prompt("fail").toolContext(context).stream().content().collectList().block());
                assertFalse(owned.isClosed());
                client.prompt("independent").call().content();
                assertNotSame(owned, seen.get()); assertTrue(seen.get().isClosed());
                owned.close();
                assertThrows(BrowserException.class, () -> client.prompt("closed").toolContext(context).call().content());
            }
        });
    }
    @Test void rejectsInvalidSessionsAndAmbiguousProfileOverrides() {
        new TwigBrowseAutoConfigurationTest().context(new TwigBrowseAutoConfigurationTest.CaptureModel()).run(ctx -> {
            var client = ctx.getBean(ChatClient.Builder.class).build();
            assertThrows(IllegalArgumentException.class, () -> client.prompt("x")
                .toolContext(Map.of(TwigBrowseContext.BROWSER_SESSION, "user-controlled-id")).call().content());
            try (var owned = ctx.getBean(BrowserSessionManager.class).openSession()) {
                assertThrows(IllegalArgumentException.class, () -> client.prompt("x").toolContext(Map.of(
                    TwigBrowseContext.BROWSER_SESSION, owned, TwigBrowseContext.BROWSER_PROFILE, BrowserProfile.defaults())).call().content());
            }
        });
    }
}
