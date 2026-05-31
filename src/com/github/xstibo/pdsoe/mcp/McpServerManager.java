package com.github.xstibo.pdsoe.mcp;

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
import java.util.List;

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

    private Server jetty;
    private McpAsyncServer mcpServer;

    /** The port the running server is bound on; used to detect a port change. */
    private int boundPort = -1;

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
        int port = Activator.getDefault().getPreferenceStore().getInt(KEY_SERVER_PORT);

        HttpServletStreamableServerTransportProvider transport =
            HttpServletStreamableServerTransportProvider.builder()
                .mcpEndpoint("/mcp")
                .build();

        AsyncToolSpecification[] tools = PROVIDERS.stream()
            .flatMap(p -> p.tools().stream())
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

        Platform.getLog(Activator.class)
            .info("PDSOE MCP Server started on http://127.0.0.1:" + port + "/mcp ("
                + tools.length + " tools)");
    }

    /**
     * Reconcile the running server with the saved preferences. Called off the UI
     * thread (it may block on graceful close). Throws if a (re)start fails to bind
     * so the caller can surface the reason. After a throw the server is stopped.
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
        if (isRunning() && port == boundPort) {
            return; // already running on the configured port - nothing to do
        }
        if (isRunning()) {
            stop(false); // port changed - restart on the new one
        }
        startServer();
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
            return;
        }
        if (mcpServer != null) {
            try {
                mcpServer.closeGracefully().block(Duration.ofSeconds(10));
            } catch (Exception e) {
                Platform.getLog(Activator.class).error("Error closing MCP server gracefully", e);
            } finally {
                mcpServer = null;
            }
        }
        if (jetty != null) {
            jetty.setStopTimeout(5000);
            try {
                jetty.stop();
            } catch (Exception e) {
                Platform.getLog(Activator.class).error("Error stopping Jetty server", e);
            } finally {
                jetty = null;
            }
        }
        boundPort = -1;
    }

    public boolean isRunning() {
        return jetty != null && jetty.isRunning();
    }
}
