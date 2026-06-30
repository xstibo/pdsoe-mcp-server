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

    /**
     * Tool names that must NOT be registered with the MCP server, as a
     * comma-separated list. String; default "" (every tool enabled). Storing the
     * disabled set (rather than the enabled set) means tools added in a future
     * release default to enabled without touching a saved preference.
     */
    public static final String KEY_DISABLED_TOOLS = "tools.disabled";

    /**
     * Full path to the Subversion command-line executable used by the SVN tools.
     * String; default "" (empty), which means auto-detect: try "svn" on the PATH,
     * then well-known install locations. A non-empty value overrides that lookup.
     */
    public static final String KEY_SVN_EXECUTABLE = "svn.executable";

    private PreferenceConstants() {
    }
}
