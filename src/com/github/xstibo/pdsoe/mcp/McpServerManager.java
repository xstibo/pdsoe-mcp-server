package com.github.xstibo.pdsoe.mcp;

import static com.github.xstibo.pdsoe.mcp.preferences.PreferenceConstants.KEY_DISABLED_TOOLS;
import static com.github.xstibo.pdsoe.mcp.preferences.PreferenceConstants.KEY_SERVER_ENABLED;
import static com.github.xstibo.pdsoe.mcp.preferences.PreferenceConstants.KEY_SERVER_PORT;

import com.github.xstibo.pdsoe.mcp.tools.DiagnosticsTools;
import com.github.xstibo.pdsoe.mcp.tools.EditingTools;
import com.github.xstibo.pdsoe.mcp.tools.EditorStateTools;
import com.github.xstibo.pdsoe.mcp.tools.FileHistoryTools;
import com.github.xstibo.pdsoe.mcp.tools.ReadingTools;
import com.github.xstibo.pdsoe.mcp.tools.ToolProvider;
import com.github.xstibo.pdsoe.mcp.tools.WorkspaceTools;
import com.github.xstibo.pdsoe.mcp.tools.symbol.SymbolGraphTools;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jface.preference.IPreferenceStore;

import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

public class McpServerManager {

    /** Every tool provider whose tools are exposed by the MCP server. */
    private static final List<ToolProvider> PROVIDERS = List.of(
        new WorkspaceTools(),
        new ReadingTools(),
        new SymbolGraphTools(),
        new EditorStateTools(),
        new DiagnosticsTools(),
        new EditingTools(),
        new FileHistoryTools());

    /** A tool's name + description, for the preference page's filtering tree. */
    public record ToolInfo(String name, String description) {
    }

    /**
     * Every tool grouped by its provider's domain, in provider/registration order.
     * Reuses {@link #PROVIDERS} so the preference UI and the server agree on the
     * exact tool set. Cheap to call (the providers are stateless tool factories).
     */
    public static LinkedHashMap<String, List<ToolInfo>> toolsByDomain() {
        LinkedHashMap<String, List<ToolInfo>> byDomain = new LinkedHashMap<>();
        for (ToolProvider p : PROVIDERS) {
            List<ToolInfo> infos = p.tools().stream()
                .map(t -> new ToolInfo(t.tool().name(), t.tool().description()))
                .toList();
            byDomain.put(p.domain(), infos);
        }
        return byDomain;
    }

    /**
     * Every tool spec from every provider, keyed by tool name, in registration
     * order. Shared by {@link #startServer()} (initial registration) and
     * {@link #reconcileTools(Set)} (live add/remove) so both agree on the spec set.
     */
    private static LinkedHashMap<String, AsyncToolSpecification> allToolSpecs() {
        LinkedHashMap<String, AsyncToolSpecification> specs = new LinkedHashMap<>();
        for (ToolProvider p : PROVIDERS) {
            for (AsyncToolSpecification t : p.tools()) {
                specs.put(t.tool().name(), t);
            }
        }
        return specs;
    }

    /** Parse the comma-separated KEY_DISABLED_TOOLS value into a set of tool names. */
    private static Set<String> parseDisabled(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        Set<String> names = new HashSet<>();
        for (String part : csv.split(",")) {
            String name = part.trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    private Server jetty;
    private McpAsyncServer mcpServer;

    /** The port the running server is bound on; used to detect a port change. */
    private int boundPort = -1;

    /** The disabled-tool set the running server was built with; detects a filter change. */
    private Set<String> boundDisabledTools = Set.of();

    /**
     * Start the server, swallowing and logging any failure. Used at IDE startup
     * (Activator.start) so a bad or in-use port never breaks the bundle.
     */
    public void start() {
        try {
            startServer();
        } catch (Exception e) {
            Platform.getLog(Activator.class).error("Failed to start MCP server", e);
        }
    }

    /**
     * Start the server, propagating any failure (e.g. the configured port is in
     * use). Used by the reconcile path so the preference page can report it.
     */
    public void startServer() throws Exception {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        int port = store.getInt(KEY_SERVER_PORT);
        Set<String> disabled = parseDisabled(store.getString(KEY_DISABLED_TOOLS));

        HttpServletStreamableServerTransportProvider transport =
            HttpServletStreamableServerTransportProvider.builder()
                .mcpEndpoint("/mcp")
                .build();

        AsyncToolSpecification[] tools = allToolSpecs().values().stream()
            .filter(t -> !disabled.contains(t.tool().name()))
            .toArray(AsyncToolSpecification[]::new);

        mcpServer = McpServer.async(transport)
            .serverInfo("pdsoe-mcp", "0.1.0")
            .capabilities(McpSchema.ServerCapabilities.builder()
                .tools(true)
                .build())
            .tools(tools)
            .build();

        jetty = new Server();
        ServerConnector connector = new ServerConnector(jetty);
        connector.setHost("127.0.0.1");
        connector.setPort(port);
        jetty.addConnector(connector);

        ServletContextHandler ctx = new ServletContextHandler();
        ctx.setContextPath("/");
        ctx.addServlet(new ServletHolder(transport), "/*");
        jetty.setHandler(ctx);

        jetty.start();
        boundPort = port;
        boundDisabledTools = disabled;

        Platform.getLog(Activator.class)
            .info("PDSOE MCP Server started on http://127.0.0.1:" + port + "/mcp ("
                + tools.length + " tools)");
    }

    /**
     * Reconcile the running server with the saved preferences. Called off the UI
     * thread (it may block on graceful close). Throws if a (re)start fails to bind
     * so the caller can surface the reason. After a throw the server is stopped.
     *
     * <p>A change to only the disabled-tool set (port unchanged, still enabled) is
     * applied live via {@link #reconcileTools(Set)} - no Jetty restart. A restart
     * would force a graceful stop that times out against a connected client's
     * long-lived stream (logged as "Error stopping Jetty server"). Only a port change
     * (or enable -> disable) still restarts/stops.
     */
    public void applyConfiguration() throws Exception {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        boolean enabled = store.getBoolean(KEY_SERVER_ENABLED);
        int port = store.getInt(KEY_SERVER_PORT);

        if (!enabled) {
            if (isRunning()) {
                stop(false);
            }
            return;
        }
        Set<String> desiredDisabled = parseDisabled(store.getString(KEY_DISABLED_TOOLS));
        if (isRunning() && port == boundPort && desiredDisabled.equals(boundDisabledTools)) {
            return; // already running on the configured port with the same tool set
        }
        if (isRunning() && port == boundPort) {
            reconcileTools(desiredDisabled); // only the tool set changed - apply live
            return;
        }
        if (isRunning()) {
            stop(false); // port changed - restart
        }
        startServer();
    }

    /**
     * Apply a disabled-tool-set change to the running server without restarting:
     * add the newly-enabled tools, remove the newly-disabled ones, then notify the
     * client its tool list changed. Blocks briefly on each reactive call (we are on
     * a background Job). On success {@link #boundDisabledTools} is updated.
     */
    private void reconcileTools(Set<String> desiredDisabled) throws Exception {
        LinkedHashMap<String, AsyncToolSpecification> specs = allToolSpecs();
        for (var entry : specs.entrySet()) {
            String name = entry.getKey();
            boolean wasEnabled = !boundDisabledTools.contains(name);
            boolean nowEnabled = !desiredDisabled.contains(name);
            if (wasEnabled && !nowEnabled) {
                mcpServer.removeTool(name).block(Duration.ofSeconds(5));
            } else if (!wasEnabled && nowEnabled) {
                mcpServer.addTool(entry.getValue()).block(Duration.ofSeconds(5));
            }
        }
        mcpServer.notifyToolsListChanged().block(Duration.ofSeconds(5));
        boundDisabledTools = desiredDisabled;

        long enabledCount = specs.keySet().stream()
            .filter(n -> !desiredDisabled.contains(n)).count();
        Platform.getLog(Activator.class)
            .info("PDSOE MCP Server tools reconciled live (" + enabledCount + " tools)");
    }

    public void stop(boolean frameworkStopping) {
        if (frameworkStopping) {
            // The OSGi framework (and the JVM) is shutting down. The OS will free the
            // socket and kill our threads, so no cleanup is needed here. Worse, doing
            // ANY work now is harmful: closeGracefully()/jetty.stop() lazily load
            // reactor/jetty classes that were never touched while the server ran, but
            // the bundle's extracted nested JARs are already gone at this point. Each
            // failed class load surfaces as a spurious Equinox FrameworkEvent ERROR
            // (a try/catch cannot suppress those). So we full no-op and just drop refs.
            mcpServer = null;
            jetty = null;
            boundPort = -1;
            boundDisabledTools = Set.of();
            return;
        }
        if (mcpServer != null) {
            try {
                mcpServer.closeGracefully().block(Duration.ofSeconds(2));
            } catch (Exception e) {
                Platform.getLog(Activator.class).error("Error closing MCP server gracefully", e);
            } finally {
                mcpServer = null;
            }
        }
        if (jetty != null) {
            // Hard stop (no graceful drain): this is a deliberate restart/stop, so we
            // do not wait for a connected client's long-lived stream to drain - that
            // wait always times out and the client reconnects anyway. setStopTimeout(0)
            // releases the socket immediately and deterministically for a same-port
            // rebind (ServerConnector defaults to SO_REUSEADDR).
            jetty.setStopTimeout(0);
            try {
                jetty.stop();
            } catch (Exception e) {
                Platform.getLog(Activator.class).error("Error stopping Jetty server", e);
            } finally {
                jetty = null;
            }
        }
        boundPort = -1;
        boundDisabledTools = Set.of();
    }

    public boolean isRunning() {
        return jetty != null && jetty.isRunning();
    }
}
