package com.github.xstibo.pdsoe.mcp.preferences;

import static com.github.xstibo.pdsoe.mcp.preferences.PreferenceConstants.KEY_DISABLED_TOOLS;
import static com.github.xstibo.pdsoe.mcp.preferences.PreferenceConstants.KEY_SERVER_ENABLED;
import static com.github.xstibo.pdsoe.mcp.preferences.PreferenceConstants.KEY_SERVER_PORT;
import static com.github.xstibo.pdsoe.mcp.preferences.PreferenceConstants.KEY_SVN_EXECUTABLE;

import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.jface.viewers.CheckboxTreeViewer;
import org.eclipse.jface.viewers.ICheckStateProvider;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.ui.PlatformUI;

import com.github.xstibo.pdsoe.mcp.Activator;
import com.github.xstibo.pdsoe.mcp.McpServerManager;
import com.github.xstibo.pdsoe.mcp.McpServerManager.ToolInfo;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Preferences for the embedded MCP server: Enable on/off, the bind port, a
 * read-only status line, and a per-tool filter, grouped into "Server
 * Configuration", "Server Status", and "Tools" sections. The bind address stays
 * loopback-only and is not exposed.
 *
 * Changes are applied only when the user presses Apply or Apply and Close
 * (performOk). The server is then reconciled to the new settings on a background
 * Job (the graceful close can block), and the status line / any bind failure is
 * reported back on the UI thread.
 *
 * Tool filtering keeps a working copy of the *disabled* tool names
 * ({@link #disabledTools}) as the source of truth for the tree; an
 * {@link ICheckStateProvider} derives each row's checked/grayed state from it, so
 * the state is correct even for collapsed (never-realized) tree nodes.
 */
public class McpPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    private Button enableButton;
    private Text portText;
    private Label statusLabel;
    private Text svnText;

    private CheckboxTreeViewer toolsViewer;
    /** domain label -> tools in that domain, in registration order. */
    private LinkedHashMap<String, List<ToolInfo>> toolModel;
    /** Working copy of disabled tool names; mutated as the user toggles the tree. */
    private final Set<String> disabledTools = new HashSet<>();

    @Override
    public void init(IWorkbench workbench) {
        setPreferenceStore(Activator.getDefault().getPreferenceStore());
        setDescription("Embedded MCP server exposed at http://127.0.0.1:<port>/mcp (loopback only).");
    }

    @Override
    protected Control createContents(Composite parent) {
        Composite top = new Composite(parent, SWT.NONE);
        GridLayout topLayout = new GridLayout(1, false);
        topLayout.marginWidth = 0;
        topLayout.marginHeight = 0;
        topLayout.verticalSpacing = 10;
        top.setLayout(topLayout);
        top.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        IPreferenceStore store = getPreferenceStore();

        // --- Server Configuration -------------------------------------------------
        Group config = new Group(top, SWT.NONE);
        config.setText("Server Configuration");
        GridLayout cfgLayout = new GridLayout(2, false);
        cfgLayout.marginWidth = 10;
        cfgLayout.marginHeight = 10;
        cfgLayout.horizontalSpacing = 8;
        cfgLayout.verticalSpacing = 8;
        config.setLayout(cfgLayout);
        config.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        enableButton = new Button(config, SWT.CHECK);
        enableButton.setText("Enable HTTP MCP Server");
        GridData enableGd = new GridData(SWT.LEFT, SWT.CENTER, true, false);
        enableGd.horizontalSpan = 2;
        enableButton.setLayoutData(enableGd);
        enableButton.setSelection(store.getBoolean(KEY_SERVER_ENABLED));

        Label portLabel = new Label(config, SWT.NONE);
        portLabel.setText("Port:");
        portLabel.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        portText = new Text(config, SWT.BORDER | SWT.SINGLE);
        portText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        portText.setText(Integer.toString(store.getInt(KEY_SERVER_PORT)));
        portText.addModifyListener(e -> validate());

        // --- Server Status --------------------------------------------------------
        Group status = new Group(top, SWT.NONE);
        status.setText("Server Status");
        GridLayout stLayout = new GridLayout(1, false);
        stLayout.marginWidth = 10;
        stLayout.marginHeight = 10;
        status.setLayout(stLayout);
        status.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        statusLabel = new Label(status, SWT.NONE);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // --- Version Control ------------------------------------------------------
        createVersionControlGroup(top, store);

        // --- Tools ----------------------------------------------------------------
        createToolsGroup(top, store);

        refreshStatus(null);
        validate();
        return top;
    }

    /** Build the "Version Control" section: the optional path to the svn executable. */
    private void createVersionControlGroup(Composite top, IPreferenceStore store) {
        Group vc = new Group(top, SWT.NONE);
        vc.setText("Version Control");
        GridLayout layout = new GridLayout(3, false);
        layout.marginWidth = 10;
        layout.marginHeight = 10;
        layout.horizontalSpacing = 8;
        layout.verticalSpacing = 8;
        vc.setLayout(layout);
        vc.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        Label label = new Label(vc, SWT.NONE);
        label.setText("svn executable:");
        label.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        svnText = new Text(vc, SWT.BORDER | SWT.SINGLE);
        svnText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        svnText.setText(store.getString(KEY_SVN_EXECUTABLE));
        svnText.setToolTipText("Full path to the Subversion command-line client. "
            + "Leave empty to auto-detect (PATH, then common install locations).");

        Button browse = new Button(vc, SWT.PUSH);
        browse.setText("Browse...");
        browse.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        browse.addListener(SWT.Selection, e -> {
            FileDialog dialog = new FileDialog(browse.getShell(), SWT.OPEN);
            dialog.setText("Select the svn executable");
            String current = svnText.getText().trim();
            if (!current.isEmpty()) dialog.setFileName(current);
            String chosen = dialog.open();
            if (chosen != null) svnText.setText(chosen);
        });

        Label hint = new Label(vc, SWT.WRAP);
        hint.setText("Optional. If empty, the server looks for 'svn' on the PATH, then at "
            + "common install locations (TortoiseSVN, SlikSVN, ...). Set this if svn is "
            + "installed elsewhere or not on the PATH.");
        GridData hintGd = new GridData(SWT.FILL, SWT.TOP, true, false);
        hintGd.horizontalSpan = 3;
        hintGd.widthHint = 360;
        hint.setLayoutData(hintGd);
    }

    /** Build the "Tools" section: a checkbox tree of domains and their tools. */
    private void createToolsGroup(Composite top, IPreferenceStore store) {
        Group tools = new Group(top, SWT.NONE);
        tools.setText("Tools");
        GridLayout toolsLayout = new GridLayout(1, false);
        toolsLayout.marginWidth = 10;
        toolsLayout.marginHeight = 10;
        tools.setLayout(toolsLayout);
        tools.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Label hint = new Label(tools, SWT.WRAP);
        hint.setText("Unchecked tools are not registered with the MCP server. "
            + "Changes apply on Apply / Apply and Close - the tool list updates "
            + "live on the running server (your MCP client refreshes without "
            + "reconnecting); no restart.");
        hint.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        toolModel = McpServerManager.toolsByDomain();
        loadDisabledFromStore(store);

        toolsViewer = new CheckboxTreeViewer(tools, SWT.BORDER);
        GridData treeGd = new GridData(SWT.FILL, SWT.FILL, true, true);
        treeGd.heightHint = 220;
        toolsViewer.getTree().setLayoutData(treeGd);

        toolsViewer.setContentProvider(new ITreeContentProvider() {
            @Override
            public Object[] getElements(Object input) {
                return toolModel.keySet().toArray();
            }

            @Override
            public Object[] getChildren(Object parent) {
                if (parent instanceof String domain) {
                    return toolModel.get(domain).toArray();
                }
                return new Object[0];
            }

            @Override
            public Object getParent(Object element) {
                if (element instanceof ToolInfo info) {
                    return domainOf(info);
                }
                return null;
            }

            @Override
            public boolean hasChildren(Object element) {
                return element instanceof String;
            }
        });

        toolsViewer.setLabelProvider(new LabelProvider() {
            @Override
            public String getText(Object element) {
                if (element instanceof String domain) {
                    return domain;
                }
                if (element instanceof ToolInfo info) {
                    return info.name();
                }
                return String.valueOf(element);
            }
        });

        toolsViewer.setCheckStateProvider(new ICheckStateProvider() {
            @Override
            public boolean isChecked(Object element) {
                if (element instanceof ToolInfo info) {
                    return !disabledTools.contains(info.name());
                }
                if (element instanceof String domain) {
                    return enabledCount(domain) > 0; // checked (solid or grayed) if any enabled
                }
                return false;
            }

            @Override
            public boolean isGrayed(Object element) {
                if (element instanceof String domain) {
                    int enabled = enabledCount(domain);
                    return enabled > 0 && enabled < toolModel.get(domain).size();
                }
                return false;
            }
        });

        toolsViewer.addCheckStateListener(event -> {
            Object element = event.getElement();
            boolean checked = event.getChecked();
            if (element instanceof String domain) {
                for (ToolInfo info : toolModel.get(domain)) {
                    setToolEnabled(info, checked);
                }
            } else if (element instanceof ToolInfo info) {
                setToolEnabled(info, checked);
            }
            toolsViewer.refresh(); // recompute checked/grayed via the provider
        });

        toolsViewer.setInput(toolModel);
    }

    /** Number of enabled (not-disabled) tools in a domain. */
    private int enabledCount(String domain) {
        int count = 0;
        for (ToolInfo info : toolModel.get(domain)) {
            if (!disabledTools.contains(info.name())) {
                count++;
            }
        }
        return count;
    }

    private String domainOf(ToolInfo info) {
        for (var entry : toolModel.entrySet()) {
            if (entry.getValue().contains(info)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void setToolEnabled(ToolInfo info, boolean enabled) {
        if (enabled) {
            disabledTools.remove(info.name());
        } else {
            disabledTools.add(info.name());
        }
    }

    private void loadDisabledFromStore(IPreferenceStore store) {
        disabledTools.clear();
        String csv = store.getString(KEY_DISABLED_TOOLS);
        if (csv != null) {
            for (String part : csv.split(",")) {
                String name = part.trim();
                if (!name.isEmpty()) {
                    disabledTools.add(name);
                }
            }
        }
    }

    private void validate() {
        String text = portText.getText().trim();
        int port;
        try {
            port = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            setErrorMessage("Port must be an integer between 1024 and 65535");
            setValid(false);
            return;
        }
        if (port < 1024 || port > 65535) {
            setErrorMessage("Port must be an integer between 1024 and 65535");
            setValid(false);
            return;
        }
        setErrorMessage(null);
        setValid(true);
    }

    @Override
    public boolean performOk() {
        if (!isValid()) {
            return false;
        }
        IPreferenceStore store = getPreferenceStore();
        store.setValue(KEY_SERVER_ENABLED, enableButton.getSelection());
        store.setValue(KEY_SERVER_PORT, Integer.parseInt(portText.getText().trim()));
        // Persist the disabled set sorted, so the stored value is stable/diff-friendly.
        store.setValue(KEY_DISABLED_TOOLS, String.join(",", new TreeSet<>(disabledTools)));
        // The svn path is read by the tool at call time; persisting it is enough (no reconcile).
        store.setValue(KEY_SVN_EXECUTABLE, svnText.getText().trim());
        // Capture the main workbench window shell + its display now: both outlive
        // this preference dialog, so feedback still works on "Apply and Close"
        // (which disposes this page and its statusLabel before the Job finishes).
        Shell shell = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell();
        applyServerConfigAsync(shell, shell.getDisplay());
        return true;
    }

    @Override
    protected void performDefaults() {
        IPreferenceStore store = getPreferenceStore();
        enableButton.setSelection(store.getDefaultBoolean(KEY_SERVER_ENABLED));
        portText.setText(Integer.toString(store.getDefaultInt(KEY_SERVER_PORT)));
        if (svnText != null) {
            svnText.setText(store.getDefaultString(KEY_SVN_EXECUTABLE));
        }
        // Default is "no tools disabled" - re-check everything in the tree.
        disabledTools.clear();
        if (toolsViewer != null) {
            toolsViewer.refresh();
        }
        validate();
        super.performDefaults();
    }

    /**
     * Reconcile the server to the saved preferences off the UI thread (the graceful
     * close can block up to ~10s), then refresh the status / report a bind failure.
     */
    private void applyServerConfigAsync(Shell shell, Display display) {
        Job.create("Applying MCP server settings", monitor -> {
            String error = null;
            try {
                Activator.getDefault().applyServerConfiguration();
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            final String err = error;
            if (display.isDisposed()) {
                return; // whole IDE is shutting down - nothing to report
            }
            display.asyncExec(() -> {
                refreshStatus(err); // self-guards if the page/label is already disposed
                if (err != null && !shell.isDisposed()) {
                    MessageDialog.openError(shell, "MCP Server",
                        "Could not start the MCP server:\n\n" + err);
                }
            });
        }).schedule();
    }

    private void refreshStatus(String error) {
        if (statusLabel == null || statusLabel.isDisposed()) {
            return;
        }
        boolean running = Activator.getDefault().isServerRunning();
        String text;
        if (running) {
            int port = getPreferenceStore().getInt(KEY_SERVER_PORT);
            text = "Running on http://127.0.0.1:" + port + "/mcp";
        } else if (error != null) {
            text = "Stopped - " + error;
        } else {
            text = "Stopped";
        }
        statusLabel.setText(text);
        statusLabel.getParent().layout();
    }
}
