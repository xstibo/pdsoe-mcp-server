package com.github.xstibo.pdsoe.mcp.tools;

import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.IConsoleManager;
import org.eclipse.ui.console.MessageConsole;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.ABL_BUILDER_ID;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.collectMarkers;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.describe;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.error;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.param;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.resolveFile;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.resolveProject;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.result;

/** Diagnostics and build tools: problem markers, project/file builds, clean, console output. */
public class DiagnosticsTools implements ToolProvider {

    @Override
    public String domain() {
        return "Diagnostics & build";
    }

    @Override
    public List<AsyncToolSpecification> tools() {
        return List.of(getMarkersTool(), getMarkersForFileTool(), buildProjectTool(),
            buildFileTool(), cleanProjectTool(), getConsoleOutputTool());
    }

    public AsyncToolSpecification buildProjectTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("build_project")
            .description("Builds an Eclipse project (including ABL via PDSOE builder)")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "project", Map.of("type", "string"),
                    "full", Map.of("type", "boolean", "description", "true = full build, false = incremental")),
                List.of("project"),
                null, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            String projectName = param(req.arguments(), "project");
            if (projectName == null || projectName.isBlank()) return Mono.just(error("project is required"));
            boolean fullBuild = Boolean.TRUE.equals(req.arguments().get("full"));
            IProject project;
            try {
                project = resolveProject(projectName);
            } catch (IllegalArgumentException e) {
                return Mono.just(error(e.getMessage()));
            }

            return Mono.create(sink -> {
                WorkspaceJob job = new WorkspaceJob("MCP Build: " + projectName) {
                    @Override
                    public IStatus runInWorkspace(IProgressMonitor monitor) {
                        try {
                            int kind = fullBuild
                                ? IncrementalProjectBuilder.FULL_BUILD
                                : IncrementalProjectBuilder.INCREMENTAL_BUILD;
                            project.build(kind, ABL_BUILDER_ID, null, monitor);
                            sink.success(result("OK\n\n" + collectMarkers(project)));
                        } catch (CoreException e) {
                            sink.success(error("Build error: " + describe(e)));
                        }
                        return Status.OK_STATUS;
                    }
                };
                job.setRule(ResourcesPlugin.getWorkspace().getRoot());
                job.schedule();
            });
        });
    }

    public AsyncToolSpecification getMarkersTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("get_markers")
            .description("Returns all errors and warnings for a project")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of("project", Map.of("type", "string")),
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
            return Mono.just(result(collectMarkers(project)));
        });
    }

    public AsyncToolSpecification getConsoleOutputTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("get_console_output")
            .description("Returns the text content of the last active Eclipse console (build output, run output, etc.)")
            .inputSchema(new McpSchema.JsonSchema("object", Map.of(), null, false, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            McpSchema.CallToolResult[] holder = new McpSchema.CallToolResult[1];
            Display.getDefault().syncExec(() -> {
                try {
                    IConsoleManager cm = ConsolePlugin.getDefault().getConsoleManager();
                    IConsole[] consoles = cm.getConsoles();
                    if (consoles.length == 0) { holder[0] = result("No consoles found"); return; }
                    IConsole last = consoles[consoles.length - 1];
                    if (!(last instanceof MessageConsole mc)) { holder[0] = result("Console is not a MessageConsole"); return; }
                    IDocument doc = mc.getDocument();
                    holder[0] = result(doc == null ? "(empty)" : doc.get());
                } catch (Exception e) {
                    holder[0] = error(describe(e));
                }
            });
            return Mono.just(holder[0]);
        });
    }

    public AsyncToolSpecification getMarkersForFileTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("get_markers_for_file")
            .description("Returns all problem markers (errors, warnings, info) for a specific file.")
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
            return Mono.just(result(collectMarkers(file)));
        });
    }

    public AsyncToolSpecification buildFileTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("build_file")
            .description("Recompiles a specific file (always, even if unchanged) via an incremental project build "
                + "and returns markers (errors/warnings) for that file only.")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "project", Map.of("type", "string"),
                    "path", Map.of("type", "string", "description", "Project-relative file path, e.g. src/myproc.p")),
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
            IProject project = file.getProject();

            return Mono.create(sink -> {
                WorkspaceJob job = new WorkspaceJob("MCP build_file") {
                    @Override
                    public IStatus runInWorkspace(IProgressMonitor monitor) {
                        try {
                            // Touch so the incremental builder cannot skip the file as
                            // "unchanged". Without it, a file whose last compile failed only
                            // because a dependency was missing keeps its stale error markers,
                            // and a file edited outside the editor may keep stale r-code.
                            file.touch(monitor);
                            project.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, ABL_BUILDER_ID, null, monitor);
                            sink.success(result("OK\n\n" + collectMarkers(file)));
                        } catch (CoreException e) {
                            sink.success(error("Build error: " + describe(e)));
                        }
                        return Status.OK_STATUS;
                    }
                };
                job.setRule(ResourcesPlugin.getWorkspace().getRoot());
                job.schedule();
            });
        });
    }

    public AsyncToolSpecification cleanProjectTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("clean_project")
            .description("Cleans a project (removes derived build output) and triggers a full rebuild.")
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
                WorkspaceJob job = new WorkspaceJob("MCP clean_project: " + projectName) {
                    @Override
                    public IStatus runInWorkspace(IProgressMonitor monitor) {
                        try {
                            project.build(IncrementalProjectBuilder.CLEAN_BUILD, monitor);
                            sink.success(result("Cleaned " + projectName + ". Markers after clean:\n" + collectMarkers(project)));
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
}
