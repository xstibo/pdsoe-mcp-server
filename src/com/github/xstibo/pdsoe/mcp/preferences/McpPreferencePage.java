package com.github.xstibo.pdsoe.mcp.preferences;

import static com.github.xstibo.pdsoe.mcp.preferences.PreferenceConstants.KEY_SERVER_ENABLED;
import static com.github.xstibo.pdsoe.mcp.preferences.PreferenceConstants.KEY_SERVER_PORT;

import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.ui.PlatformUI;

import com.github.xstibo.pdsoe.mcp.Activator;

/**
 * Preferences for the embedded MCP server: Enable on/off, the bind port, and a
 * read-only status line, grouped into "Server Configuration" and "Server Status"
 * sections. The bind address stays loopback-only and is not exposed.
 *
 * Changes are applied only when the user presses Apply or Apply and Close
 * (performOk). The server is then reconciled to the new settings on a background
 * Job (the graceful close can block), and the status line / any bind failure is
 * reported back on the UI thread.
 */
public class McpPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    private Button enableButton;
    private Text portText;
    private Label statusLabel;

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

        refreshStatus(null);
        validate();
        return top;
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
