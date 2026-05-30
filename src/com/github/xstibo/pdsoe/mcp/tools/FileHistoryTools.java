package com.github.xstibo.pdsoe.mcp.tools;

import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFileState;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.describe;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.error;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.param;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.readAllLines;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.resolveFile;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.result;

/** Eclipse local-history tools: list snapshots, read a snapshot, and diff against one. */
public class FileHistoryTools implements ToolProvider {

    @Override
    public List<AsyncToolSpecification> tools() {
        return List.of(listFileHistoryTool(), getFileHistoryContentTool(), diffFileTool());
    }

    public AsyncToolSpecification listFileHistoryTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("list_file_history")
            .description("Lists Eclipse local-history snapshots for a file. Returns index and timestamp for each entry. Use index with get_file_history_content or diff_file.")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "project", Map.of("type", "string"),
                    "path", Map.of("type", "string")),
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

            return Mono.create(sink -> {
                WorkspaceJob job = new WorkspaceJob("MCP list_file_history") {
                    @Override
                    public IStatus runInWorkspace(IProgressMonitor monitor) {
                        try {
                            IFileState[] history = file.getHistory(monitor);
                            if (history.length == 0) {
                                sink.success(result("No history available"));
                                return Status.OK_STATUS;
                            }
                            StringBuilder sb = new StringBuilder();
                            for (int i = 0; i < history.length; i++) {
                                java.time.Instant ts = java.time.Instant.ofEpochMilli(history[i].getModificationTime());
                                sb.append(i).append(": ").append(ts).append("\n");
                            }
                            sink.success(result(sb.toString().trim()));
                        } catch (CoreException e) {
                            sink.success(error(describe(e)));
                        }
                        return Status.OK_STATUS;
                    }
                };
                job.setRule(ResourcesPlugin.getWorkspace().getRoot());
                job.schedule();
            });
        });
    }

    public AsyncToolSpecification getFileHistoryContentTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("get_file_history_content")
            .description("Returns the content of an Eclipse local-history snapshot. Use list_file_history to get the index.")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "project", Map.of("type", "string"),
                    "path", Map.of("type", "string"),
                    "index", Map.of("type", "integer", "description", "History snapshot index (0 = most recent)")),
                List.of("project", "path", "index"), null, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            String projectName = param(req.arguments(), "project");
            if (projectName == null || projectName.isBlank()) return Mono.just(error("project is required"));
            String filePath = param(req.arguments(), "path");
            if (filePath == null || filePath.isBlank()) return Mono.just(error("path is required"));
            String indexStr = param(req.arguments(), "index");
            if (indexStr == null || indexStr.isBlank()) return Mono.just(error("index is required"));

            IFile file;
            try {
                file = resolveFile(projectName, filePath);
            } catch (IllegalArgumentException e) {
                return Mono.just(error(e.getMessage()));
            }
            int idx;
            try {
                idx = Integer.parseInt(indexStr);
            } catch (NumberFormatException e) {
                return Mono.just(error("index must be an integer"));
            }

            return Mono.create(sink -> {
                WorkspaceJob job = new WorkspaceJob("MCP get_file_history_content") {
                    @Override
                    public IStatus runInWorkspace(IProgressMonitor monitor) {
                        try {
                            IFileState[] history = file.getHistory(monitor);
                            if (idx < 0 || idx >= history.length) {
                                sink.success(error("Index " + idx + " out of range (history has " + history.length + " entries)"));
                                return Status.OK_STATUS;
                            }
                            String content = new String(history[idx].getContents().readAllBytes(), StandardCharsets.UTF_8);
                            sink.success(result(content));
                        } catch (Exception e) {
                            sink.success(error(describe(e)));
                        }
                        return Status.OK_STATUS;
                    }
                };
                job.setRule(ResourcesPlugin.getWorkspace().getRoot());
                job.schedule();
            });
        });
    }

    public AsyncToolSpecification diffFileTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("diff_file")
            .description("Shows a diff of the current file versus a local-history snapshot. index defaults to 0 (most recent snapshot).")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "project", Map.of("type", "string"),
                    "path", Map.of("type", "string"),
                    "index", Map.of("type", "integer", "description", "History snapshot index (default 0 = most recent)")),
                List.of("project", "path"), null, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            String projectName = param(req.arguments(), "project");
            if (projectName == null || projectName.isBlank()) return Mono.just(error("project is required"));
            String filePath = param(req.arguments(), "path");
            if (filePath == null || filePath.isBlank()) return Mono.just(error("path is required"));
            String indexStr = param(req.arguments(), "index");
            int idx = 0;
            if (indexStr != null && !indexStr.isBlank()) {
                try { idx = Integer.parseInt(indexStr); }
                catch (NumberFormatException e) { return Mono.just(error("index must be an integer")); }
            }
            final int histIdx = idx;

            IFile file;
            try {
                file = resolveFile(projectName, filePath);
            } catch (IllegalArgumentException e) {
                return Mono.just(error(e.getMessage()));
            }

            return Mono.create(sink -> {
                WorkspaceJob job = new WorkspaceJob("MCP diff_file") {
                    @Override
                    public IStatus runInWorkspace(IProgressMonitor monitor) {
                        try {
                            IFileState[] history = file.getHistory(monitor);
                            if (history.length == 0) {
                                sink.success(result("No history available"));
                                return Status.OK_STATUS;
                            }
                            if (histIdx < 0 || histIdx >= history.length) {
                                sink.success(error("Index " + histIdx + " out of range (history has " + history.length + " entries)"));
                                return Status.OK_STATUS;
                            }

                            List<String> oldLines = new ArrayList<>(Arrays.asList(
                                new String(history[histIdx].getContents().readAllBytes(), StandardCharsets.UTF_8).split("\n", -1)));
                            List<String> newLines = readAllLines(file);

                            java.time.Instant ts = java.time.Instant.ofEpochMilli(history[histIdx].getModificationTime());
                            String diff = buildSimpleDiff(filePath, ts.toString(), oldLines, newLines);
                            sink.success(result(diff));
                        } catch (Exception e) {
                            sink.success(error(describe(e)));
                        }
                        return Status.OK_STATUS;
                    }
                };
                job.setRule(ResourcesPlugin.getWorkspace().getRoot());
                job.schedule();
            });
        });
    }

    private String buildSimpleDiff(String path, String histTimestamp, List<String> oldLines, List<String> newLines) {
        int prefixLen = 0;
        while (prefixLen < oldLines.size() && prefixLen < newLines.size()
               && oldLines.get(prefixLen).equals(newLines.get(prefixLen))) prefixLen++;

        int oldEnd = oldLines.size(), newEnd = newLines.size();
        while (oldEnd > prefixLen && newEnd > prefixLen
               && oldLines.get(oldEnd - 1).equals(newLines.get(newEnd - 1))) { oldEnd--; newEnd--; }

        if (oldEnd == prefixLen && newEnd == prefixLen)
            return "Files are identical";

        final int CONTEXT = 3;
        int ctxStart = Math.max(0, prefixLen - CONTEXT);
        int oldCtxEnd = Math.min(oldLines.size(), oldEnd + CONTEXT);
        int newCtxEnd = Math.min(newLines.size(), newEnd + CONTEXT);

        int oldCount = (prefixLen - ctxStart) + (oldEnd - prefixLen) + (oldCtxEnd - oldEnd);
        int newCount = (prefixLen - ctxStart) + (newEnd - prefixLen) + (newCtxEnd - newEnd);

        StringBuilder sb = new StringBuilder();
        sb.append("--- a/").append(path).append("  (history: ").append(histTimestamp).append(")\n");
        sb.append("+++ b/").append(path).append("  (current)\n");
        sb.append("@@ -").append(ctxStart + 1).append(",").append(oldCount)
          .append(" +").append(ctxStart + 1).append(",").append(newCount).append(" @@\n");

        for (int i = ctxStart; i < prefixLen; i++)
            sb.append(" ").append(oldLines.get(i)).append("\n");
        for (int i = prefixLen; i < oldEnd; i++)
            sb.append("-").append(oldLines.get(i)).append("\n");
        for (int i = prefixLen; i < newEnd; i++)
            sb.append("+").append(newLines.get(i)).append("\n");
        for (int i = oldEnd; i < oldCtxEnd; i++)
            sb.append(" ").append(oldLines.get(i)).append("\n");

        return sb.toString().stripTrailing();
    }
}
