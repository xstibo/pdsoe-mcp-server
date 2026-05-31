package com.github.xstibo.pdsoe.mcp.tools;

import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.eclipse.core.resources.IFile;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorDescriptor;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.describe;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.error;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.param;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.resolveEditorFile;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.resolveFile;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.result;

/** Editor-state tools: active file, cursor, selection, open editors, navigation, saving. */
public class EditorStateTools implements ToolProvider {

    @Override
    public String domain() {
        return "Editor state";
    }

    @Override
    public List<AsyncToolSpecification> tools() {
        return List.of(getOpenFileTool(), getCursorPositionTool(), getSelectionTool(),
            listOpenEditorsTool(), navigateToTool(), saveFileTool(), saveAllTool());
    }

    public AsyncToolSpecification getOpenFileTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("get_open_file")
            .description("Returns the workspace path of the currently active editor file")
            .inputSchema(new McpSchema.JsonSchema("object", Map.of(), null, false, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            McpSchema.CallToolResult[] holder = new McpSchema.CallToolResult[1];
            Display.getDefault().syncExec(() -> {
                try {
                    IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                    if (window == null) { holder[0] = result("No active workbench window"); return; }
                    IEditorPart editor = window.getActivePage().getActiveEditor();
                    if (editor == null) { holder[0] = result("No active editor"); return; }
                    IFile file = resolveEditorFile(editor.getEditorInput());
                    holder[0] = result(file != null ? file.getFullPath().toString()
                        : editor.getEditorInput().getName());
                } catch (Exception e) {
                    holder[0] = error(describe(e));
                }
            });
            return Mono.just(holder[0]);
        });
    }

    public AsyncToolSpecification getCursorPositionTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("get_cursor_position")
            .description("Returns the cursor line and column in the currently active text editor")
            .inputSchema(new McpSchema.JsonSchema("object", Map.of(), null, false, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            McpSchema.CallToolResult[] holder = new McpSchema.CallToolResult[1];
            Display.getDefault().syncExec(() -> {
                try {
                    IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                    if (window == null) { holder[0] = result("No active workbench window"); return; }
                    IEditorPart editor = window.getActivePage().getActiveEditor();
                    if (!(editor instanceof ITextEditor te)) {
                        holder[0] = result("Active editor is not a text editor");
                        return;
                    }
                    Object sel = te.getSelectionProvider().getSelection();
                    if (!(sel instanceof ITextSelection ts)) {
                        holder[0] = result("No text selection available");
                        return;
                    }
                    int line = ts.getStartLine() + 1;
                    int col = -1;
                    try {
                        IDocument doc = te.getDocumentProvider().getDocument(te.getEditorInput());
                        col = ts.getOffset() - doc.getLineOffset(ts.getStartLine()) + 1;
                    } catch (Exception ignored) {}
                    holder[0] = result("line: " + line + (col >= 0 ? ", column: " + col : ""));
                } catch (Exception e) {
                    holder[0] = error(describe(e));
                }
            });
            return Mono.just(holder[0]);
        });
    }

    public AsyncToolSpecification getSelectionTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("get_selection")
            .description("Returns the selected text and position in the currently active text editor")
            .inputSchema(new McpSchema.JsonSchema("object", Map.of(), null, false, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            McpSchema.CallToolResult[] holder = new McpSchema.CallToolResult[1];
            Display.getDefault().syncExec(() -> {
                try {
                    IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                    if (window == null) { holder[0] = result("No active workbench window"); return; }
                    IEditorPart editor = window.getActivePage().getActiveEditor();
                    if (!(editor instanceof ITextEditor te)) {
                        holder[0] = result("Active editor is not a text editor");
                        return;
                    }
                    Object sel = te.getSelectionProvider().getSelection();
                    if (!(sel instanceof ITextSelection ts) || ts.getLength() == 0) {
                        holder[0] = result("No text selected");
                        return;
                    }
                    int startLine = ts.getStartLine() + 1;
                    int endLine = ts.getEndLine() + 1;
                    holder[0] = result("start_line: " + startLine + "\nend_line: " + endLine + "\ntext:\n" + ts.getText());
                } catch (Exception e) {
                    holder[0] = error(describe(e));
                }
            });
            return Mono.just(holder[0]);
        });
    }

    public AsyncToolSpecification listOpenEditorsTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("list_open_editors")
            .description("Lists all currently open editor tabs in the Eclipse workbench")
            .inputSchema(new McpSchema.JsonSchema("object", Map.of(), null, false, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            McpSchema.CallToolResult[] holder = new McpSchema.CallToolResult[1];
            Display.getDefault().syncExec(() -> {
                try {
                    IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                    if (window == null) { holder[0] = result("No active workbench window"); return; }
                    IWorkbenchPage page = window.getActivePage();
                    IEditorReference[] refs = page.getEditorReferences();
                    if (refs.length == 0) { holder[0] = result("No open editors"); return; }
                    StringBuilder sb = new StringBuilder();
                    for (IEditorReference ref : refs) {
                        try {
                            IFile file = resolveEditorFile(ref.getEditorInput());
                            sb.append(file != null ? file.getFullPath().toString() : ref.getName()).append("\n");
                        } catch (Exception ex) {
                            sb.append(ref.getName()).append("\n");
                        }
                    }
                    holder[0] = result(sb.toString().trim());
                } catch (Exception e) {
                    holder[0] = error(describe(e));
                }
            });
            return Mono.just(holder[0]);
        });
    }

    public AsyncToolSpecification navigateToTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("navigate_to")
            .description("Opens a file in the Eclipse editor, optionally scrolling to a specific line.")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "project", Map.of("type", "string"),
                    "path", Map.of("type", "string", "description", "Relative path, e.g. src/main.p"),
                    "line", Map.of("type", "integer", "description", "Line to navigate to (1-based, optional)")
                ),
                List.of("project", "path"), null, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            String projectName = param(req.arguments(), "project");
            if (projectName == null || projectName.isBlank()) return Mono.just(error("project is required"));
            String path = param(req.arguments(), "path");
            if (path == null || path.isBlank()) return Mono.just(error("path is required"));
            Object lineObj = req.arguments().get("line");
            int lineNum = (lineObj instanceof Number n) ? n.intValue() : 0;

            IFile file;
            try {
                file = resolveFile(projectName, path);
            } catch (IllegalArgumentException e) {
                return Mono.just(error(e.getMessage()));
            }

            McpSchema.CallToolResult[] holder = new McpSchema.CallToolResult[1];
            Display.getDefault().syncExec(() -> {
                try {
                    IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                    if (window == null) { holder[0] = error("No active workbench window"); return; }
                    IWorkbenchPage page = window.getActivePage();
                    IEditorInput input = new IEditorInput() {
                        @Override public boolean exists() { return file.exists(); }
                        @Override public org.eclipse.jface.resource.ImageDescriptor getImageDescriptor() { return null; }
                        @Override public String getName() { return file.getName(); }
                        @Override public org.eclipse.ui.IPersistableElement getPersistable() { return null; }
                        @Override public String getToolTipText() { return file.getFullPath().toString(); }
                        @Override public <T> T getAdapter(Class<T> adapter) {
                            if (adapter == IFile.class) return adapter.cast(file);
                            return null;
                        }
                    };
                    IEditorDescriptor desc = PlatformUI.getWorkbench().getEditorRegistry()
                        .getDefaultEditor(file.getName());
                    String editorId = desc != null ? desc.getId() : "org.eclipse.ui.DefaultTextEditor";
                    IEditorPart editor = page.openEditor(input, editorId);
                    if (lineNum > 0 && editor instanceof ITextEditor te) {
                        IDocument doc = te.getDocumentProvider().getDocument(te.getEditorInput());
                        if (doc != null && lineNum <= doc.getNumberOfLines()) {
                            int offset = doc.getLineOffset(lineNum - 1);
                            te.selectAndReveal(offset, 0);
                        }
                    }
                    holder[0] = result("Opened: " + path + (lineNum > 0 ? " at line " + lineNum : ""));
                } catch (Exception e) {
                    holder[0] = error(describe(e));
                }
            });
            return Mono.just(holder[0]);
        });
    }

    public AsyncToolSpecification saveFileTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("save_file")
            .description("Saves a specific open editor by file path (triggers ABL compilation in PDSOE).")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "project", Map.of("type", "string"),
                    "path", Map.of("type", "string", "description", "Relative path, e.g. src/main.p")
                ),
                List.of("project", "path"), null, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            String projectName = param(req.arguments(), "project");
            if (projectName == null || projectName.isBlank()) return Mono.just(error("project is required"));
            String filePath = param(req.arguments(), "path");
            if (filePath == null || filePath.isBlank()) return Mono.just(error("path is required"));
            IFile file;
            try {
                file = resolveFile(projectName, filePath);
            } catch (IllegalArgumentException e) {
                return Mono.just(error(e.getMessage()));
            }

            McpSchema.CallToolResult[] holder = new McpSchema.CallToolResult[1];
            Display.getDefault().syncExec(() -> {
                try {
                    IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                    if (window == null) { holder[0] = error("No active workbench window"); return; }
                    IWorkbenchPage page = window.getActivePage();
                    IEditorPart editor = null;
                    for (org.eclipse.ui.IEditorReference ref : page.getEditorReferences()) {
                        IEditorInput edInput;
                        try { edInput = ref.getEditorInput(); } catch (org.eclipse.ui.PartInitException e2) { continue; }
                        IFile editorFile = resolveEditorFile(edInput);
                        if (file.equals(editorFile)) {
                            editor = ref.getEditor(true);
                            break;
                        }
                    }
                    if (editor == null) { holder[0] = result("File is not open in an editor: " + filePath); return; }
                    page.saveEditor(editor, false);
                    holder[0] = result("Saved: " + filePath);
                } catch (Exception e) {
                    holder[0] = error(describe(e));
                }
            });
            return Mono.just(holder[0]);
        });
    }

    public AsyncToolSpecification saveAllTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("save_all")
            .description("Saves all dirty open editors")
            .inputSchema(new McpSchema.JsonSchema("object", Map.of(), null, false, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            McpSchema.CallToolResult[] holder = new McpSchema.CallToolResult[1];
            Display.getDefault().syncExec(() -> {
                try {
                    IWorkbench wb = PlatformUI.getWorkbench();
                    wb.saveAllEditors(false);
                    holder[0] = result("All editors saved");
                } catch (Exception e) {
                    holder[0] = error(describe(e));
                }
            });
            return Mono.just(holder[0]);
        });
    }
}
