package com.github.xstibo.pdsoe.mcp.tools;

import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFileState;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.countOccurrences;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.describe;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.detectFormat;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.ensureParentFolders;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.error;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.fileCharset;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.normalizeLineEndings;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.param;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.readAllLines;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.requireContainedPath;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.resolveFile;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.resolveProject;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.result;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.writeAllLines;

/** Code-editing tools: write, insert, replace lines/text, delete lines, undo, apply patch. */
public class EditingTools implements ToolProvider {

    @Override
    public String domain() {
        return "Code editing";
    }

    @Override
    public List<AsyncToolSpecification> tools() {
        return List.of(writeFileTool(), insertAtLineTool(), replaceLinesTool(), replaceInFileTool(),
            deleteLinesTool(), undoEditTool(), applyPatchTool());
    }

    public AsyncToolSpecification writeFileTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("write_file")
            .description("Writes content to a workspace file, creating it if it does not exist")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "project", Map.of("type", "string"),
                    "path", Map.of("type", "string"),
                    "content", Map.of("type", "string")),
                List.of("project", "path", "content"),
                null, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            String projectName = param(req.arguments(), "project");
            if (projectName == null || projectName.isBlank()) return Mono.just(error("project is required"));
            String path = param(req.arguments(), "path");
            if (path == null || path.isBlank()) return Mono.just(error("path is required"));
            Object contentObj = req.arguments().get("content");
            if (contentObj == null) return Mono.just(error("content is required"));
            String content = contentObj.toString();
            IProject project;
            try {
                project = resolveProject(projectName);
                requireContainedPath(path);
            } catch (IllegalArgumentException e) {
                return Mono.just(error(e.getMessage()));
            }
            IFile file = project.getFile(path);

            return Mono.create(sink -> {
                WorkspaceJob job = new WorkspaceJob("MCP Write: " + path) {
                    @Override
                    public IStatus runInWorkspace(IProgressMonitor monitor) {
                        try {
                            if (file.exists()) {
                                // Preserve the existing file's charset and line endings so an
                                // overwrite does not flip every line's terminator (see Gotchas).
                                ToolSupport.TextFormat fmt = detectFormat(file);
                                byte[] bytes = normalizeLineEndings(content, fmt.lineSeparator())
                                    .getBytes(fmt.charset());
                                file.setContents(new ByteArrayInputStream(bytes), IResource.KEEP_HISTORY, monitor);
                            } else {
                                byte[] bytes = content.getBytes(fileCharset(file));
                                ensureParentFolders(file, monitor);
                                file.create(new ByteArrayInputStream(bytes), IResource.NONE, monitor);
                            }
                            sink.success(result("Written: " + path));
                        } catch (Exception e) {
                            sink.success(error(describe(e)));
                        }
                        return Status.OK_STATUS;
                    }
                };
                job.setRule(file.getProject());
                job.schedule();
            });
        });
    }

    public AsyncToolSpecification insertAtLineTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("insert_at_line")
            .description("Inserts text before a specific line in a workspace file")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "project", Map.of("type", "string"),
                    "path", Map.of("type", "string"),
                    "line", Map.of("type", "integer", "description", "Line number to insert before (1-based)"),
                    "content", Map.of("type", "string", "description", "Text to insert")),
                List.of("project", "path", "line", "content"),
                null, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            String projectName = param(req.arguments(), "project");
            if (projectName == null || projectName.isBlank()) return Mono.just(error("project is required"));
            String path = param(req.arguments(), "path");
            if (path == null || path.isBlank()) return Mono.just(error("path is required"));
            Number lineObj = (Number) req.arguments().get("line");
            if (lineObj == null) return Mono.just(error("line is required"));
            int line = lineObj.intValue();
            if (line < 1) return Mono.just(error("line must be >= 1"));
            Object contentObj = req.arguments().get("content");
            if (contentObj == null) return Mono.just(error("content is required"));
            String content = contentObj.toString();
            IFile file;
            try {
                file = resolveFile(projectName, path);
            } catch (IllegalArgumentException e) {
                return Mono.just(error(e.getMessage()));
            }

            return Mono.create(sink -> {
                WorkspaceJob job = new WorkspaceJob("MCP Insert: " + path) {
                    @Override
                    public IStatus runInWorkspace(IProgressMonitor monitor) {
                        try {
                            List<String> lines = readAllLines(file);
                            int idx = Math.min(line - 1, lines.size());
                            // Split on LF only; writeAllLines re-applies the file's real EOL, so
                            // normalize CRLF/CR out of the incoming content first to avoid doubled CRs.
                            String normalized = content.replace("\r\n", "\n").replace("\r", "\n").replaceAll("\n+$", "");
                            lines.addAll(idx, Arrays.asList(normalized.split("\n", -1)));
                            writeAllLines(file, lines, monitor);
                            sink.success(result("Inserted at line " + line));
                        } catch (Exception e) {
                            sink.success(error(describe(e)));
                        }
                        return Status.OK_STATUS;
                    }
                };
                job.setRule(file.getProject());
                job.schedule();
            });
        });
    }

    public AsyncToolSpecification replaceLinesTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("replace_lines")
            .description("Replaces a line range in a workspace file with new content")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "project", Map.of("type", "string"),
                    "path", Map.of("type", "string"),
                    "start_line", Map.of("type", "integer", "description", "First line to replace (1-based)"),
                    "end_line", Map.of("type", "integer", "description", "Last line to replace (1-based, inclusive)"),
                    "content", Map.of("type", "string", "description", "Replacement text")),
                List.of("project", "path", "start_line", "end_line", "content"),
                null, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            String projectName = param(req.arguments(), "project");
            if (projectName == null || projectName.isBlank()) return Mono.just(error("project is required"));
            String path = param(req.arguments(), "path");
            if (path == null || path.isBlank()) return Mono.just(error("path is required"));
            Number startLineObj2 = (Number) req.arguments().get("start_line");
            if (startLineObj2 == null) return Mono.just(error("start_line is required"));
            int startLine = startLineObj2.intValue();
            if (startLine < 1) return Mono.just(error("start_line must be >= 1"));
            Number endLineObj2 = (Number) req.arguments().get("end_line");
            if (endLineObj2 == null) return Mono.just(error("end_line is required"));
            int endLine = endLineObj2.intValue();
            if (endLine < startLine) return Mono.just(error("end_line must be >= start_line"));
            Object contentObj = req.arguments().get("content");
            if (contentObj == null) return Mono.just(error("content is required"));
            String content = contentObj.toString();
            IFile file;
            try {
                file = resolveFile(projectName, path);
            } catch (IllegalArgumentException e) {
                return Mono.just(error(e.getMessage()));
            }

            return Mono.create(sink -> {
                WorkspaceJob job = new WorkspaceJob("MCP Replace lines: " + path) {
                    @Override
                    public IStatus runInWorkspace(IProgressMonitor monitor) {
                        try {
                            List<String> lines = readAllLines(file);
                            int s = startLine - 1;
                            int e2 = Math.min(endLine, lines.size());
                            if (s > lines.size()) {
                                sink.success(error("start_line " + startLine + " is beyond end of file (" + lines.size() + " lines)"));
                                return Status.OK_STATUS;
                            }
                            lines.subList(s, e2).clear();
                            // Split on LF only; writeAllLines re-applies the file's real EOL, so
                            // normalize CRLF/CR out of the incoming content first to avoid doubled CRs.
                            String normalized = content.replace("\r\n", "\n").replace("\r", "\n").replaceAll("\n+$", "");
                            lines.addAll(s, Arrays.asList(normalized.split("\n", -1)));
                            writeAllLines(file, lines, monitor);
                            sink.success(result("Replaced lines " + startLine + "-" + endLine));
                        } catch (Exception ex) {
                            sink.success(error(describe(ex)));
                        }
                        return Status.OK_STATUS;
                    }
                };
                job.setRule(file.getProject());
                job.schedule();
            });
        });
    }

    public AsyncToolSpecification replaceInFileTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("replace_in_file")
            .description("Replaces occurrences of a search string (or regex) with replacement text in a workspace file")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "project", Map.of("type", "string"),
                    "path", Map.of("type", "string"),
                    "search", Map.of("type", "string", "description", "Text or regex to find"),
                    "replace", Map.of("type", "string", "description", "Replacement text"),
                    "regex", Map.of("type", "boolean", "description", "Treat search as regex (default: false)"),
                    "all", Map.of("type", "boolean", "description", "Replace all occurrences (default: false = replace first only)")),
                List.of("project", "path", "search", "replace"),
                null, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            String projectName = param(req.arguments(), "project");
            if (projectName == null || projectName.isBlank()) return Mono.just(error("project is required"));
            String path = param(req.arguments(), "path");
            if (path == null || path.isBlank()) return Mono.just(error("path is required"));
            Object searchObj = req.arguments().get("search");
            if (searchObj == null) return Mono.just(error("search is required"));
            String search = searchObj.toString();
            Object replaceObj = req.arguments().get("replace");
            if (replaceObj == null) return Mono.just(error("replace is required"));
            String replace = replaceObj.toString();
            boolean useRegex = Boolean.TRUE.equals(req.arguments().get("regex"));
            IFile file;
            try {
                file = resolveFile(projectName, path);
            } catch (IllegalArgumentException e) {
                return Mono.just(error(e.getMessage()));
            }

            return Mono.create(sink -> {
                WorkspaceJob job = new WorkspaceJob("MCP Replace in: " + path) {
                    @Override
                    public IStatus runInWorkspace(IProgressMonitor monitor) {
                        try (InputStream is = file.getContents()) {
                            Charset cs = fileCharset(file);
                            String original = new String(is.readAllBytes(), cs);
                            // Match the replacement's line endings to the file's so a multi-line
                            // replacement does not inject lone LFs into a CRLF file.
                            String sep = original.contains("\r\n") ? "\r\n"
                                : original.indexOf('\r') >= 0 ? "\r" : "\n";
                            String safeReplace = normalizeLineEndings(replace, sep);
                            Pattern p = useRegex ? Pattern.compile(search)
                                : Pattern.compile(Pattern.quote(search));
                            boolean replaceAll = Boolean.TRUE.equals(req.arguments().get("all"));
                            String updated = replaceAll
                                ? p.matcher(original).replaceAll(safeReplace)
                                : p.matcher(original).replaceFirst(safeReplace);
                            int count = countOccurrences(original, search);
                            byte[] bytes = updated.getBytes(cs);
                            file.setContents(new ByteArrayInputStream(bytes), IResource.KEEP_HISTORY, monitor);
                            sink.success(result("Replaced " + (replaceAll ? count : Math.min(count, 1)) + " occurrence(s)"));
                        } catch (Exception e) {
                            sink.success(error(describe(e)));
                        }
                        return Status.OK_STATUS;
                    }
                };
                job.setRule(file.getProject());
                job.schedule();
            });
        });
    }

    public AsyncToolSpecification deleteLinesTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("delete_lines_in_file")
            .description("Deletes a range of lines from a workspace file.")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "project", Map.of("type", "string"),
                    "path", Map.of("type", "string"),
                    "start_line", Map.of("type", "integer", "description", "First line to delete (1-based)"),
                    "end_line", Map.of("type", "integer", "description", "Last line to delete (1-based, inclusive)")),
                List.of("project", "path", "start_line", "end_line"),
                null, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            String projectName = param(req.arguments(), "project");
            if (projectName == null || projectName.isBlank()) return Mono.just(error("project is required"));
            String path = param(req.arguments(), "path");
            if (path == null || path.isBlank()) return Mono.just(error("path is required"));
            Number startLineObj3 = (Number) req.arguments().get("start_line");
            if (startLineObj3 == null) return Mono.just(error("start_line is required"));
            int startLine = startLineObj3.intValue();
            if (startLine < 1) return Mono.just(error("start_line must be >= 1"));
            Number endLineObj3 = (Number) req.arguments().get("end_line");
            if (endLineObj3 == null) return Mono.just(error("end_line is required"));
            int endLine = endLineObj3.intValue();
            if (endLine < startLine) return Mono.just(error("end_line must be >= start_line"));
            IFile file;
            try {
                file = resolveFile(projectName, path);
            } catch (IllegalArgumentException e) {
                return Mono.just(error(e.getMessage()));
            }

            return Mono.create(sink -> {
                WorkspaceJob job = new WorkspaceJob("MCP delete_lines_in_file") {
                    @Override
                    public IStatus runInWorkspace(IProgressMonitor monitor) {
                        try {
                            List<String> lines = readAllLines(file);
                            int s = startLine - 1;
                            int e2 = Math.min(endLine, lines.size());
                            if (s > lines.size()) {
                                sink.success(error("start_line " + startLine + " is beyond end of file (" + lines.size() + " lines)"));
                                return Status.OK_STATUS;
                            }
                            lines.subList(s, e2).clear();
                            writeAllLines(file, lines, monitor);
                            sink.success(result("Deleted lines " + startLine + "-" + Math.min(endLine, e2)));
                        } catch (Exception e) {
                            sink.success(error(describe(e)));
                        }
                        return Status.OK_STATUS;
                    }
                };
                job.setRule(file.getProject());
                job.schedule();
            });
        });
    }

    public AsyncToolSpecification undoEditTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("undo_edit")
            .description("Restores a file to its previous state using Eclipse local history (equivalent to Ctrl+Z on the file level).")
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

            return Mono.create(sink -> {
                WorkspaceJob job = new WorkspaceJob("MCP undo_edit") {
                    @Override
                    public IStatus runInWorkspace(IProgressMonitor monitor) {
                        try {
                            IFileState[] history = file.getHistory(monitor);
                            if (history.length == 0) {
                                sink.success(error("No local history available for: " + filePath));
                                return Status.OK_STATUS;
                            }
                            try (InputStream is = history[0].getContents()) {
                                file.setContents(is, IResource.KEEP_HISTORY, monitor);
                            }
                            sink.success(result("Restored from history: " + filePath));
                        } catch (Exception e) {
                            sink.success(error(describe(e)));
                        }
                        return Status.OK_STATUS;
                    }
                };
                job.setRule(file.getProject());
                job.schedule();
            });
        });
    }

    public AsyncToolSpecification applyPatchTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("apply_patch")
            .description("Applies a unified diff patch to a workspace file")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "project", Map.of("type", "string"),
                    "path", Map.of("type", "string"),
                    "patch", Map.of("type", "string", "description", "Unified diff patch text")),
                List.of("project", "path", "patch"),
                null, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            String projectName = param(req.arguments(), "project");
            if (projectName == null || projectName.isBlank()) return Mono.just(error("project is required"));
            String path = param(req.arguments(), "path");
            if (path == null || path.isBlank()) return Mono.just(error("path is required"));
            Object patchObj = req.arguments().get("patch");
            if (patchObj == null) return Mono.just(error("patch is required"));
            String patch = patchObj.toString();
            IFile file;
            try {
                file = resolveFile(projectName, path);
            } catch (IllegalArgumentException e) {
                return Mono.just(error(e.getMessage()));
            }

            return Mono.create(sink -> {
                WorkspaceJob job = new WorkspaceJob("MCP apply_patch: " + path) {
                    @Override
                    public IStatus runInWorkspace(IProgressMonitor monitor) {
                        try {
                            List<String> original = readAllLines(file);
                            List<String> patched = applyUnifiedDiff(original, patch);
                            writeAllLines(file, patched, monitor);
                            sink.success(result("Patch applied to " + path));
                        } catch (Exception e) {
                            sink.success(error(describe(e)));
                        }
                        return Status.OK_STATUS;
                    }
                };
                job.setRule(file.getProject());
                job.schedule();
            });
        });
    }

    /**
     * Applies unified-diff hunks positionally. Hunk length comes from {@code origCount} in
     * the {@code @@ -start,origCount @@} header; context lines are not verified against the
     * original, so the patch must target the file it was generated from.
     */
    private List<String> applyUnifiedDiff(List<String> original, String patch) {
        String normalized = patch.replace("\r\n", "\n").replace("\r", "\n");
        String[] patchLines = normalized.split("\n", -1);
        List<String> result = new ArrayList<>(original);
        int offset = 0;
        int i = 0;
        while (i < patchLines.length) {
            String line = patchLines[i];
            if (!line.startsWith("@@")) { i++; continue; }
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@")
                .matcher(line);
            if (!m.find()) { i++; continue; }
            int origStart = Integer.parseInt(m.group(1)) - 1 + offset;
            int origCount = m.group(2) != null ? Integer.parseInt(m.group(2)) : 1;
            i++;
            List<String> contextAndAdds = new ArrayList<>();
            while (i < patchLines.length && !patchLines[i].startsWith("@@")) {
                String pl = patchLines[i];
                if (!pl.startsWith("-")) {
                    contextAndAdds.add(pl.startsWith("+") ? pl.substring(1) : (pl.isEmpty() ? "" : pl.substring(1)));
                }
                i++;
            }
            int end = Math.min(origStart + origCount, result.size());
            result.subList(origStart, end).clear();
            result.addAll(origStart, contextAndAdds);
            offset += contextAndAdds.size() - origCount;
        }
        return result;
    }
}
