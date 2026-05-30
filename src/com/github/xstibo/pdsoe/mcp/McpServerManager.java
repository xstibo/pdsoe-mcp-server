package com.github.xstibo.pdsoe.mcp;

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

import java.time.Duration;
import java.util.List;

public class McpServerManager {

    private static final int PORT = Integer.getInteger("pdsoe.mcp.port", 8123);

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

    public void start() {
        try {
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
            connector.setPort(PORT);
            jetty.addConnector(connector);

            ServletContextHandler ctx = new ServletContextHandler();
            ctx.setContextPath("/");
            ctx.addServlet(new ServletHolder(transport), "/*");
            jetty.setHandler(ctx);

            jetty.start();

            Platform.getLog(Activator.class)
                .info("PDSOE MCP Server started on http://127.0.0.1:" + PORT + "/mcp ("
                    + tools.length + " tools)");

        } catch (Exception e) {
            Platform.getLog(Activator.class)
                .error("Failed to start MCP server", e);
        }
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
    }

    public boolean isRunning() {
        return jetty != null && jetty.isRunning();
    }
}
