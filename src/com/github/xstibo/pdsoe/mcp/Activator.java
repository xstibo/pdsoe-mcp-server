package com.github.xstibo.pdsoe.mcp;

import org.eclipse.core.runtime.Plugin;
import org.eclipse.ui.IStartup;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

public class Activator extends Plugin implements IStartup {

    public static final String PLUGIN_ID = "com.github.xstibo.pdsoe.mcp";
    private static Activator instance;
    private McpServerManager serverManager;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        instance = this;
        serverManager = new McpServerManager();
        serverManager.start();
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

    public static Activator getDefault() {
        return instance;
    }
}
