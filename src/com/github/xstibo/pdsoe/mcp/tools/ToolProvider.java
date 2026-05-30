package com.github.xstibo.pdsoe.mcp.tools;

import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;

import java.util.List;

/**
 * A cohesive group of MCP tools for one domain of the ABL workspace (file navigation,
 * code reading, the symbol graph, editor state, diagnostics, editing, or file history).
 * {@link com.github.xstibo.pdsoe.mcp.McpServerManager} aggregates every provider's tools
 * into a single MCP server, so adding a tool means touching only one provider.
 */
public interface ToolProvider {

    /** @return the tool specifications this provider contributes to the MCP server. */
    List<AsyncToolSpecification> tools();
}
