package com.github.xstibo.pdsoe.mcp.preferences;

import static com.github.xstibo.pdsoe.mcp.preferences.PreferenceConstants.KEY_SERVER_ENABLED;
import static com.github.xstibo.pdsoe.mcp.preferences.PreferenceConstants.KEY_SERVER_PORT;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;

import com.github.xstibo.pdsoe.mcp.Activator;

/**
 * Seeds the default values for the plugin preferences. Registered via the
 * org.eclipse.core.runtime.preferences extension point in plugin.xml.
 *
 * Writes straight to the default-scope node (keyed by the bundle symbolic name,
 * which is the node AbstractUIPlugin.getPreferenceStore() falls back to). This is
 * the documented-safe idiom: an initializer can fire before the plugin singleton
 * exists, so we do not route through Activator.getDefault().
 */
public class PreferenceInitializer extends AbstractPreferenceInitializer {

    @Override
    public void initializeDefaultPreferences() {
        IEclipsePreferences defaults = DefaultScope.INSTANCE.getNode(Activator.PLUGIN_ID);
        defaults.putBoolean(KEY_SERVER_ENABLED, true);
        // Preserve the historical -Dpdsoe.mcp.port escape hatch as the default;
        // a saved preference value overrides it.
        defaults.putInt(KEY_SERVER_PORT, Integer.getInteger("pdsoe.mcp.port", 8123));
    }
}
