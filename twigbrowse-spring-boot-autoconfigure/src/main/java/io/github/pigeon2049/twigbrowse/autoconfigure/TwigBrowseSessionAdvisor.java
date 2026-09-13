package io.github.pigeon2049.twigbrowse.autoconfigure;

import java.util.HashMap;
import io.github.pigeon2049.twigbrowse.core.*;
import org.springframework.ai.chat.client.*;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

/** Outermost tool lifecycle: one private session per call or stream subscription. */
public final class TwigBrowseSessionAdvisor implements CallAdvisor, StreamAdvisor {
    static final String SESSION_KEY = TwigBrowseSessionAdvisor.class.getName() + ".session";
    private final BrowserSessionManager sessions;
    public TwigBrowseSessionAdvisor(BrowserSessionManager sessions) { this.sessions = sessions; }
    @Override public String getName() { return "TwigBrowseSessionAdvisor"; }
    @Override public int getOrder() { return ToolCallingAdvisor.DEFAULT_ORDER - 1; }
    @Override public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        try (BrowserSession session = sessions.openSession(profile(request))) { return chain.nextCall(withSession(request, session)); }
    }
    @Override public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.using(() -> sessions.openSession(profile(request)),
            session -> chain.nextStream(withSession(request, session)), BrowserSession::close);
    }
    private BrowserProfile profile(ChatClientRequest request) {
        if (request.prompt().getOptions() instanceof ToolCallingChatOptions options && options.getToolContext() != null) {
            Object profile = options.getToolContext().get(TwigBrowseContext.BROWSER_PROFILE);
            if (profile != null && !(profile instanceof BrowserProfile))
                throw new IllegalArgumentException("twigbrowse.browserProfile must be a BrowserProfile instance");
            return (BrowserProfile)profile;
        }
        return null;
    }
    private ChatClientRequest withSession(ChatClientRequest request, BrowserSession session) {
        if (!(request.prompt().getOptions() instanceof ToolCallingChatOptions options))
            throw new IllegalStateException("TwigBrowse requires a ChatModel with ToolCallingChatOptions");
        var context = new HashMap<String, Object>();
        if (options.getToolContext() != null) context.putAll(options.getToolContext());
        context.put(SESSION_KEY, session);
        var copy = options.mutate().toolContext(context).build();
        return request.mutate().prompt(new Prompt(request.prompt().getInstructions(), copy)).build();
    }
}
