package com.github.xstibo.pdsoe.mcp;

import static com.github.xstibo.pdsoe.mcp.preferences.PreferenceConstants.KEY_SERVER_ENABLED;

import org.eclipse.ui.IStartup;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

public class Activator extends AbstractUIPlugin implements IStartup {

    public static final String PLUGIN_ID = "com.github.xstibo.pdsoe.mcp";
    private static Activator instance;
    private McpServerManager serverManager;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        instance = this;
        serverManager = new McpServerManager();
        // Honor the Enable preference; an explicit opt-out means do not start.
        if (getPreferenceStore().getBoolean(KEY_SERVER_ENABLED)) {
            serverManager.start();
        }
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        if (serverManager != null) {
            // The system bundle (id 0) is STOPPING only during a full framework/IDE
            // shutdown; for an individual bundle stop/update it stays ACTIVE. We pass
            // that signal so the manager can skip reactive cleanup that would fail
            // against already-removed bundle JARs during shutdown.
            boolean frameworkStopping = context.getBundle(0).getState() == Bundle.STOPPING;
            serverManager.stop(frameworkStopping);
        }
        instance = null;
        super.stop(context);
    }

    @Override
    public void earlyStartup() {
    }

    /**
     * Reconcile the running server with the saved preferences. Call off the UI
     * thread (it may block on graceful close); throws if a (re)start fails to bind.
     */
    public void applyServerConfiguration() throws Exception {
        if (serverManager != null) {
            serverManager.applyConfiguration();
        }
    }

    public boolean isServerRunning() {
        return serverManager != null && serverManager.isRunning();
    }

    public static Activator getDefault() {
        return instance;
    }
}
