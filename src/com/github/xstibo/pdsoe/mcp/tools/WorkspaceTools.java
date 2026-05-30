package com.github.xstibo.pdsoe.mcp.tools;

import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.core.runtime.Status;
import org.eclipse.search.core.text.TextSearchEngine;
import org.eclipse.search.core.text.TextSearchMatchAccess;
import org.eclipse.search.core.text.TextSearchRequestor;
import org.eclipse.search.core.text.TextSearchScope;
import reactor.core.publisher.Mono;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.MAX_SEARCH_MATCHES;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.describe;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.error;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.param;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.readPropath;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.requireContainedPath;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.resolveFile;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.resolveProject;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.result;

/** Workspace and file-navigation tools: projects, file listing/search, propath, includes, file ops. */
public class WorkspaceTools implements ToolProvider {

    @Override
    public List<AsyncToolSpecification> tools() {
        return List.of(listProjectsTool(), listAblFilesTool(), listAllFilesTool(), searchFilesTool(),
            searchInFilesTool(), fileSearchRegexpTool(), getProjectInfoTool(), getPropathTool(),
            listIncludesTool(), findFilesIncludingTool(), getFileInfoTool(), deleteFileTool(),
            moveFileTool(), copyFileTool(), createFolderTool(), deleteFolderTool(),
            refreshProjectTool(), getProjectLayoutTool());
    }

    public AsyncToolSpecification listProjectsTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("list_projects")
            .description("Lists all projects in the Eclipse workspace")
            .inputSchema(new McpSchema.JsonSchema("object", Map.of(), null, false, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
            StringBuilder sb = new StringBuilder();
            for (IProject p : projects) {
                sb.append(p.getName())
                  .append(" [").append(p.isOpen() ? "open" : "closed").append("]")
                  .append("\n");
            }
            return Mono.just(result(sb.toString().trim()));
        });
    }

    public AsyncToolSpecification listAblFilesTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("list_abl_files")
            .description("Lists all ABL files (.p, .cls, .i, .w, .t) in a project")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of("project", Map.of("type", "string", "description", "Project name")),
                List.of("project"),
                null, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            String projectName = param(req.arguments(), "project");
            if (projectName == null || projectName.isBlank()) return Mono.just(error("project is required"));
            IProject project;
            try {
                project = resolveProject(projectName);
            } catch (IllegalArgumentException e) {
                return Mono.just(error(e.getMessage()));
            }

            List<String> files = new ArrayList<>();
            try {
                project.accept(resource -> {
                    if (resource instanceof IFile f) {
                        String ext = f.getFileExtension();
                        if (ext != null && List.of("p", "cls", "i", "w", "t").contains(ext)) {
                            files.add(f.getProjectRelativePath().toString());
                        }
                    }
                    return true;
                });
            } catch (CoreException e) {
                return Mono.just(error(describe(e)));
            }
            return Mono.just(result(files.isEmpty() ? "No ABL files found" : String.join("\n", files)));
        });
    }

    public AsyncToolSpecification listAllFilesTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("list_all_files")
            .description("Lists all files in a project, optionally filtered by extension (e.g. p, cls)")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "project", Map.of("type", "string"),
                    "extension", Map.of("type", "string", "description", "Optional file extension filter, e.g. p or cls (without dot)")),
                List.of("project"),
                null, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            String projectName = param(req.arguments(), "project");
            if (projectName == null || projectName.isBlank()) return Mono.just(error("project is required"));
            String ext = param(req.arguments(), "extension");
            IProject project;
            try {
                project = resolveProject(projectName);
            } catch (IllegalArgumentException e) {
                return Mono.just(error(e.getMessage()));
            }
            List<String> files = new ArrayList<>();
            try {
                project.accept(resource -> {
                    if (resource instanceof IFile f) {
                        if (ext == null || ext.equalsIgnoreCase(f.getFileExtension())) {
                            files.add(f.getProjectRelativePath().toString());
                        }
                    }
                    return true;
                });
            } catch (CoreException e) {
                return Mono.just(error(describe(e)));
            }
            return Mono.just(result(files.isEmpty() ? "No files found" : String.join("\n", files)));
        });
    }

    public AsyncToolSpecification searchFilesTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("search_files")
            .description("Finds files by name or glob pattern in a project (e.g. *.p, src/**/*.cls)")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "project", Map.of("type", "string"),
                    "pattern", Map.of("type", "string", "description", "Glob pattern, e.g. *.p or src/**/*.cls")),
                List.of("project", "pattern"),
                null, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            String projectName = param(req.arguments(), "project");
            if (projectName == null || projectName.isBlank()) return Mono.just(error("project is required"));
            String pattern = param(req.arguments(), "pattern");
            if (pattern == null || pattern.isBlank()) return Mono.just(error("pattern is required"));
            IProject project;
            try {
                project = resolveProject(projectName);
            } catch (IllegalArgumentException e) {
                return Mono.just(error(e.getMessage()));
            }

            // Auto-wrap bare names (no wildcard) so "DocPMain" finds "DocPMain.p" without requiring "**/*DocPMain*"
            String effectivePattern = pattern.contains("*") ? pattern : "**/*" + pattern + "*";
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + effectivePattern);
            List<String> matches = new ArrayList<>();
            try {
                project.accept(resource -> {
                    if (resource instanceof IFile f) {
                        Path relPath = Paths.get(f.getProjectRelativePath().toString());
                        if (matcher.matches(relPath)) {
                            matches.add(f.getProjectRelativePath().toString());
                        }
                    }
                    return true;
                });
            } catch (CoreException e) {
                return Mono.just(error(describe(e)));
            }
            return Mono.just(result(matches.isEmpty() ? "No files matched" : String.join("\n", matches)));
        });
    }

    public AsyncToolSpecification searchInFilesTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("search_in_files")
            .description("Searches for text or a regex pattern across files in a project")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "project", Map.of("type", "string"),
                    "query", Map.of("type", "string", "description", "Text or regex to search for"),
                    "file_pattern", Map.of("type", "string", "description", "Glob to filter file names, e.g. *.p (default: all files)"),
                    "regex", Map.of("type", "boolean", "description", "Treat query as a regex (default: false)")),
                List.of("project", "query"),
                null, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            String projectName = param(req.arguments(), "project");
            if (projectName == null || projectName.isBlank()) return Mono.just(error("project is required"));
            String query = param(req.arguments(), "query");
            if (query == null || query.isBlank()) return Mono.just(error("query is required"));
            String rawPattern = param(req.arguments(), "file_pattern");
            boolean useRegex = Boolean.TRUE.equals(req.arguments().get("regex"));

            IProject project;
            try {
                project = resolveProject(projectName);
            } catch (IllegalArgumentException e) {
                return Mono.just(error(e.getMessage()));
            }

            String filePatternStr = (rawPattern == null || rawPattern.isBlank()) ? "*" : rawPattern;
            String fileRegex = filePatternStr
                .replace(".", "\\.")
                .replace("**", " ")
                .replace("*", "[^/]*")
                .replace(" ", ".*");
            Pattern fileNamePattern = Pattern.compile(fileRegex);

            Pattern searchPattern;
            try {
                searchPattern = useRegex ? Pattern.compile(query)
                    : Pattern.compile(Pattern.quote(query));
            } catch (PatternSyntaxException e) {
                return Mono.just(error("Invalid regex: " + e.getMessage()));
            }

            List<String> matchLines = new ArrayList<>();
            TextSearchEngine engine = TextSearchEngine.create();
            TextSearchScope scope = TextSearchScope.newSearchScope(
                new IResource[]{project}, fileNamePattern, false);
            engine.search(scope, new TextSearchRequestor() {
                @Override
                public boolean acceptPatternMatch(TextSearchMatchAccess access) throws CoreException {
                    CharSequence content = access.getFileContent(0, access.getFileContentLength());
                    if (content == null) return true;
                    int matchOffset = access.getMatchOffset();
                    int line = 1;
                    int lineStart = 0;
                    for (int i = 0; i < matchOffset; i++) {
                        if (content.charAt(i) == '\n') { line++; lineStart = i + 1; }
                    }
                    int lineEnd = matchOffset;
                    while (lineEnd < content.length() && content.charAt(lineEnd) != '\n') lineEnd++;
                    String ctx = content.subSequence(lineStart, lineEnd).toString().trim();
                    String filePath = access.getFile().getProjectRelativePath().toString();
                    matchLines.add(filePath + ":" + line + ": " + ctx);
                    return matchLines.size() < MAX_SEARCH_MATCHES;
                }
            }, searchPattern, null);

            if (matchLines.isEmpty()) return Mono.just(result("No matches found"));
            String suffix = matchLines.size() >= MAX_SEARCH_MATCHES
                ? "\n[TRUNCATED: showing first " + MAX_SEARCH_MATCHES + " matches - refine your query]" : "";
            return Mono.just(result(String.join("\n", matchLines) + suffix));
        });
    }

    public AsyncToolSpecification fileSearchRegexpTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("file_search_regexp")
            .description("Finds files whose names match a Java regular expression, optionally scoped to a single project.")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "pattern", Map.of("type", "string", "description", "Java regular expression to match against file names (not full paths)"),
                    "project", Map.of("type", "string", "description", "Optional project name to restrict the search")
                ),
                List.of("pattern"), null, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            String pattern = param(req.arguments(), "pattern");
            if (pattern == null || pattern.isBlank()) return Mono.just(error("pattern is required"));
            String projectName = param(req.arguments(), "project");

            java.util.regex.Pattern regex;
            try {
                regex = java.util.regex.Pattern.compile(pattern);
            } catch (java.util.regex.PatternSyntaxException e) {
                return Mono.just(error("Invalid regex: " + e.getMessage()));
            }

            IResource[] roots;
            if (projectName != null && !projectName.isBlank()) {
                try {
                    roots = new IResource[]{resolveProject(projectName)};
                } catch (IllegalArgumentException e) {
                    return Mono.just(error(e.getMessage()));
                }
            } else {
                roots = ResourcesPlugin.getWorkspace().getRoot().getProjects();
            }

            List<String> matches = new ArrayList<>();
            try {
                for (IResource root : roots) {
                    root.accept(resource -> {
                        if (resource instanceof IFile f && regex.matcher(f.getName()).find()) {
                            matches.add(f.getProjectRelativePath().toString());
                        }
                        return true;
                    });
                }
            } catch (CoreException e) {
                return Mono.just(error(describe(e)));
            }
            return Mono.just(result(matches.isEmpty() ? "No files matched" : String.join("\n", matches)));
        });
    }

    public AsyncToolSpecification getProjectInfoTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("get_project_info")
            .description("Returns natures, description, and persistent properties of a project.")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of("project", Map.of("type", "string")),
                List.of("project"), null, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            String projectName = param(req.arguments(), "project");
            if (projectName == null || projectName.isBlank()) return Mono.just(error("project is required"));
            IProject project;
            try {
                project = resolveProject(projectName);
            } catch (IllegalArgumentException e) {
                return Mono.just(error(e.getMessage()));
            }
            try {
                IProjectDescription desc = project.getDescription();
                StringBuilder sb = new StringBuilder();
                sb.append("name: ").append(project.getName()).append("\n");
                sb.append("open: ").append(project.isOpen()).append("\n");
                String comment = desc.getComment();
                if (comment != null && !comment.isBlank()) sb.append("description: ").append(comment).append("\n");
                String[] natures = desc.getNatureIds();
                if (natures.length > 0) sb.append("natures:\n");
                for (String n : natures) sb.append("  ").append(n).append("\n");
                Map<QualifiedName, String> props = project.getPersistentProperties();
                if (!props.isEmpty()) {
                    sb.append("persistent_properties:\n");
                    props.forEach((k, v) -> sb.append("  ").append(k).append(" = ").append(v).append("\n"));
                }
                return Mono.just(result(sb.toString().trim()));
            } catch (CoreException e) {
                return Mono.just(error(describe(e)));
            }
        });
    }

    public AsyncToolSpecification getPropathTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("get_propath")
            .description("Returns the configured PROPATH for an ABL project.")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of("project", Map.of("type", "string")),
                List.of("project"), null, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            String projectName = param(req.arguments(), "project");
            if (projectName == null || projectName.isBlank()) return Mono.just(error("project is required"));
            IProject proj;
            try {
                proj = resolveProject(projectName);
            } catch (IllegalArgumentException e) {
                return Mono.just(error(e.getMessage()));
            }
            String propath = readPropath(proj);
            if (propath == null || propath.isBlank()) return Mono.just(result("(empty)"));
            String display = String.join("\n", propath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)));
            return Mono.just(result(display));
        });
    }

    public AsyncToolSpecification listIncludesTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("list_includes")
            .description("Returns the .i include files directly referenced in an ABL source file (scans for {file.i} patterns).")
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

            try {
                String content = new String(file.getContents().readAllBytes(), StandardCharsets.UTF_8);
                Pattern p = Pattern.compile("\\{\\s*([^}&{\"\\s][^\\s}&{\"]*\\.i)(?:\\s[^}]*)?\\}", Pattern.CASE_INSENSITIVE);
                Matcher m = p.matcher(content);
                List<String> includes = new ArrayList<>();
                while (m.find()) {
                    includes.add(m.group(1).trim());
                }
                return Mono.just(includes.isEmpty()
                    ? result("No includes found")
                    : result(String.join("\n", includes)));
            } catch (Exception e) {
                return Mono.just(error(describe(e)));
            }
        });
    }

    public AsyncToolSpecification findFilesIncludingTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("find_files_including")
            .description("Returns all ABL files in a project that include a given .i file (reverse of list_includes).")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "project",      Map.of("type", "string"),
                    "include_path", Map.of("type", "string", "description",
                        "Include filename or relative path, e.g. common.i or utils/common.i")),
                List.of("project", "include_path"), null, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            String projectName = param(req.arguments(), "project");
            if (projectName == null || projectName.isBlank()) return Mono.just(error("project is required"));
            String includePath = param(req.arguments(), "include_path");
            if (includePath == null || includePath.isBlank()) return Mono.just(error("include_path is required"));

            IProject proj;
            try {
                proj = resolveProject(projectName);
            } catch (IllegalArgumentException e) {
                return Mono.just(error(e.getMessage()));
            }

            String needle = includePath.replace("\\", "/").toLowerCase();

            return Mono.create(sink -> {
                WorkspaceJob job = new WorkspaceJob("MCP find_files_including") {
                    public IStatus runInWorkspace(IProgressMonitor monitor) throws CoreException {
                        Pattern p = Pattern.compile(
                            "\\{\\s*([^}&{\"\\s][^\\s}&{\"]*\\.i)(?:\\s[^}]*)?\\}",
                            Pattern.CASE_INSENSITIVE);
                        Set<String> ablExts = Set.of("p", "cls", "i", "w", "t");
                        List<String> matches = new ArrayList<>();

                        try {
                            proj.accept(resource -> {
                                if (!(resource instanceof IFile f)) return true;
                                String ext = f.getFileExtension();
                                if (ext == null || !ablExts.contains(ext.toLowerCase())) return true;
                                try {
                                    String content = new String(f.getContents().readAllBytes(), StandardCharsets.UTF_8);
                                    Matcher m = p.matcher(content);
                                    while (m.find()) {
                                        String captured = m.group(1).trim().replace("\\", "/").toLowerCase();
                                        if (captured.equals(needle) || captured.endsWith("/" + needle)) {
                                            matches.add(f.getProjectRelativePath().toString());
                                            break;
                                        }
                                    }
                                } catch (Exception ignored) {}
                                return true;
                            });
                        } catch (CoreException e) {
                            sink.success(error(describe(e)));
                            return Status.OK_STATUS;
                        }

                        matches.sort(null);
                        sink.success(matches.isEmpty()
                            ? result("No files include: " + includePath)
                            : result(String.join("\n", matches)));
                        return Status.OK_STATUS;
                    }
                };
                job.setRule(proj);
                job.schedule();
            });
        });
    }

    public AsyncToolSpecification getFileInfoTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("get_file_info")
            .description("Returns metadata for a workspace file: exists, size, last modified, line count")
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
            IProject project;
            try {
                project = resolveProject(projectName);
                requireContainedPath(filePath);
            } catch (IllegalArgumentException e) {
                return Mono.just(error(e.getMessage()));
            }
            IFile file = project.getFile(filePath);
            if (!file.exists()) return Mono.just(result("exists: false\npath: " + filePath));
            java.io.File raw = file.getLocation().toFile();
            long size = raw.length();
            long lastModified = raw.lastModified();
            int lineCount = 0;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(file.getContents(), StandardCharsets.UTF_8))) {
                while (br.readLine() != null) lineCount++;
            } catch (Exception e) {
                return Mono.just(error(describe(e)));
            }
            return Mono.just(result(
                "exists: true\npath: " + filePath +
                "\nsize_bytes: " + size +
                "\nlast_modified_ms: " + lastModified +
                "\nline_count: " + lineCount
            ));
        });
    }

    public AsyncToolSpecification deleteFileTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("delete_file")
            .description("Deletes a file from the workspace. The deletion is recorded in local history.")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "project", Map.of("type", "string"),
                    "path", Map.of("type", "string", "description", "Relative path, e.g. src/old.p")
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
                WorkspaceJob job = new WorkspaceJob("MCP delete_file") {
                    @Override
                    public IStatus runInWorkspace(IProgressMonitor monitor) {
                        try {
                            file.delete(IResource.KEEP_HISTORY | IResource.FORCE, monitor);
                            sink.success(result("Deleted: " + filePath));
                        } catch (CoreException e) {
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

    public AsyncToolSpecification moveFileTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("move_file")
            .description("Moves or renames a file within the workspace. destination_project defaults to the source project.")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "project", Map.of("type", "string"),
                    "path", Map.of("type", "string", "description", "Source path, e.g. src/old.p"),
                    "destination_path", Map.of("type", "string", "description", "Destination path relative to destination_project"),
                    "destination_project", Map.of("type", "string", "description", "Destination project name (optional, defaults to source project)")
                ),
                List.of("project", "path", "destination_path"), null, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            String projectName = param(req.arguments(), "project");
            if (projectName == null || projectName.isBlank()) return Mono.just(error("project is required"));
            String srcPath = param(req.arguments(), "path");
            if (srcPath == null || srcPath.isBlank()) return Mono.just(error("path is required"));
            String dstPath = param(req.arguments(), "destination_path");
            if (dstPath == null || dstPath.isBlank()) return Mono.just(error("destination_path is required"));
            String dstProjectRaw = param(req.arguments(), "destination_project");
            final String dstProject = (dstProjectRaw == null || dstProjectRaw.isBlank()) ? projectName : dstProjectRaw;

            IFile srcFile;
            try {
                srcFile = resolveFile(projectName, srcPath);
                requireContainedPath(dstPath);
            } catch (IllegalArgumentException e) {
                return Mono.just(error(e.getMessage()));
            }
            IPath destination = new org.eclipse.core.runtime.Path("/" + dstProject + "/" + dstPath);

            return Mono.create(sink -> {
                WorkspaceJob job = new WorkspaceJob("MCP move_file") {
                    @Override
                    public IStatus runInWorkspace(IProgressMonitor monitor) {
                        try {
                            srcFile.move(destination, IResource.FORCE | IResource.KEEP_HISTORY, monitor);
                            sink.success(result("Moved " + srcPath + " -> " + dstProject + "/" + dstPath));
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

    public AsyncToolSpecification copyFileTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("copy_file")
            .description("Copies a file within the workspace. destination_project defaults to the source project.")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "project", Map.of("type", "string"),
                    "path", Map.of("type", "string", "description", "Source path, e.g. src/original.p"),
                    "destination_path", Map.of("type", "string", "description", "Destination path relative to destination_project"),
                    "destination_project", Map.of("type", "string", "description", "Destination project name (optional, defaults to source project)")
                ),
                List.of("project", "path", "destination_path"), null, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            String projectName = param(req.arguments(), "project");
            if (projectName == null || projectName.isBlank()) return Mono.just(error("project is required"));
            String srcPath = param(req.arguments(), "path");
            if (srcPath == null || srcPath.isBlank()) return Mono.just(error("path is required"));
            String dstPath = param(req.arguments(), "destination_path");
            if (dstPath == null || dstPath.isBlank()) return Mono.just(error("destination_path is required"));
            String dstProjectRaw = param(req.arguments(), "destination_project");
            final String dstProject = (dstProjectRaw == null || dstProjectRaw.isBlank()) ? projectName : dstProjectRaw;

            IFile srcFile;
            try {
                srcFile = resolveFile(projectName, srcPath);
                requireContainedPath(dstPath);
            } catch (IllegalArgumentException e) {
                return Mono.just(error(e.getMessage()));
            }
            IPath destination = new org.eclipse.core.runtime.Path("/" + dstProject + "/" + dstPath);
            IFile destFile = ResourcesPlugin.getWorkspace().getRoot().getFile(destination);
            if (destFile.exists()) return Mono.just(error("Destination already exists: " + dstProject + "/" + dstPath));

            return Mono.create(sink -> {
                WorkspaceJob job = new WorkspaceJob("MCP copy_file") {
                    @Override
                    public IStatus runInWorkspace(IProgressMonitor monitor) {
                        try {
                            srcFile.copy(destination, IResource.KEEP_HISTORY, monitor);
                            sink.success(result("Copied " + srcPath + " -> " + dstProject + "/" + dstPath));
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

    public AsyncToolSpecification createFolderTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("create_folder")
            .description("Creates a directory (and any missing parent directories) in the workspace.")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "project", Map.of("type", "string"),
                    "path", Map.of("type", "string", "description", "Relative folder path, e.g. src/subfolder")
                ),
                List.of("project", "path"), null, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            String projectName = param(req.arguments(), "project");
            if (projectName == null || projectName.isBlank()) return Mono.just(error("project is required"));
            String folderPath = param(req.arguments(), "path");
            if (folderPath == null || folderPath.isBlank()) return Mono.just(error("path is required"));
            IProject project;
            try {
                project = resolveProject(projectName);
                requireContainedPath(folderPath);
            } catch (IllegalArgumentException e) {
                return Mono.just(error(e.getMessage()));
            }
            IFolder folder = project.getFolder(folderPath);

            return Mono.create(sink -> {
                WorkspaceJob job = new WorkspaceJob("MCP create_folder") {
                    @Override
                    public IStatus runInWorkspace(IProgressMonitor monitor) {
                        try {
                            boolean existed = folder.exists();
                            ToolSupport.ensureFolder(folder, monitor);
                            sink.success(result(existed ? "Folder already existed: " + folderPath : "Created: " + folderPath));
                        } catch (CoreException e) {
                            sink.success(error(describe(e)));
                        }
                        return Status.OK_STATUS;
                    }
                };
                job.setRule(project);
                job.schedule();
            });
        });
    }

    public AsyncToolSpecification deleteFolderTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("delete_folder")
            .description("Deletes a folder and all its contents from the workspace.")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "project", Map.of("type", "string"),
                    "path", Map.of("type", "string", "description", "Relative folder path, e.g. src/old-package")
                ),
                List.of("project", "path"), null, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            String projectName = param(req.arguments(), "project");
            if (projectName == null || projectName.isBlank()) return Mono.just(error("project is required"));
            String folderPath = param(req.arguments(), "path");
            if (folderPath == null || folderPath.isBlank()) return Mono.just(error("path is required"));
            IProject project;
            try {
                project = resolveProject(projectName);
                requireContainedPath(folderPath);
            } catch (IllegalArgumentException e) {
                return Mono.just(error(e.getMessage()));
            }
            IFolder folder = project.getFolder(folderPath);
            if (!folder.exists()) return Mono.just(error("Folder not found: " + folderPath));

            return Mono.create(sink -> {
                WorkspaceJob job = new WorkspaceJob("MCP delete_folder") {
                    @Override
                    public IStatus runInWorkspace(IProgressMonitor monitor) {
                        try {
                            folder.delete(IResource.KEEP_HISTORY | IResource.FORCE, monitor);
                            sink.success(result("Deleted folder: " + folderPath));
                        } catch (CoreException e) {
                            sink.success(error(describe(e)));
                        }
                        return Status.OK_STATUS;
                    }
                };
                job.setRule(project);
                job.schedule();
            });
        });
    }

    public AsyncToolSpecification refreshProjectTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("refresh_project")
            .description("Re-syncs a project with the filesystem (picks up externally added/removed files).")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of("project", Map.of("type", "string")),
                List.of("project"), null, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            String projectName = param(req.arguments(), "project");
            if (projectName == null || projectName.isBlank()) return Mono.just(error("project is required"));
            IProject project;
            try {
                project = resolveProject(projectName);
            } catch (IllegalArgumentException e) {
                return Mono.just(error(e.getMessage()));
            }

            return Mono.create(sink -> {
                WorkspaceJob job = new WorkspaceJob("MCP refresh_project") {
                    @Override
                    public IStatus runInWorkspace(IProgressMonitor monitor) {
                        try {
                            project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
                            sink.success(result("Refreshed: " + projectName));
                        } catch (CoreException e) {
                            sink.success(error(describe(e)));
                        }
                        return Status.OK_STATUS;
                    }
                };
                job.setRule(project);
                job.schedule();
            });
        });
    }

    public AsyncToolSpecification getProjectLayoutTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("get_project_layout")
            .description("Returns a tree-structured view of all files and folders in a project.")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of("project", Map.of("type", "string")),
                List.of("project"), null, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            String projectName = param(req.arguments(), "project");
            if (projectName == null || projectName.isBlank()) return Mono.just(error("project is required"));
            IProject project;
            try {
                project = resolveProject(projectName);
            } catch (IllegalArgumentException e) {
                return Mono.just(error(e.getMessage()));
            }
            StringBuilder sb = new StringBuilder();
            sb.append(projectName).append("/\n");
            try {
                project.accept(resource -> {
                    if (resource.equals(project)) return true;
                    int depth = resource.getProjectRelativePath().segmentCount();
                    String indent = "  ".repeat(depth);
                    String name = resource.getName();
                    if (resource instanceof IFolder) name += "/";
                    sb.append(indent).append(name).append("\n");
                    return true;
                });
            } catch (CoreException e) {
                return Mono.just(error(describe(e)));
            }
            return Mono.just(result(sb.toString().trim()));
        });
    }
}
