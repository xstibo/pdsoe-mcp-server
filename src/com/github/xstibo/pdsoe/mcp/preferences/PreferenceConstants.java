package com.github.xstibo.pdsoe.mcp.preferences;

/**
 * Keys for the plugin preference store (instance scope, keyed by the bundle
 * symbolic name). Defaults are set in {@link PreferenceInitializer}.
 */
public final class PreferenceConstants {

    /** Whether the MCP server should run. Boolean; default true. */
    public static final String KEY_SERVER_ENABLED = "server.enabled";

    /** TCP port the server binds on 127.0.0.1. Integer; default 8123. */
    public static final String KEY_SERVER_PORT = "server.port";

    private PreferenceConstants() {
    }
}
