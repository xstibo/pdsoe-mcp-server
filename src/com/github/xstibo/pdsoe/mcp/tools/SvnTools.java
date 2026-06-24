package com.github.xstibo.pdsoe.mcp.tools;

import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.eclipse.core.resources.IProject;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.MAX_READ_BYTES;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.error;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.param;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.requireContainedPath;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.resolveProject;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.result;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.str;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.tool;

/**
 * Version-control tools backed by the {@code svn} command-line client. The plugin shells out
 * to {@code svn} (which must be on the PATH) rather than depending on a particular Eclipse SVN
 * integration (Subversive/Subclipse), whose APIs are not stable/public. Read-only so far:
 * the working copy is the Eclipse project's on-disk location.
 */
public class SvnTools implements ToolProvider {

    /** Hard ceiling on how long a single svn invocation may run before it is killed. */
    private static final long PROCESS_TIMEOUT_SECONDS = 120;

    @Override
    public String domain() {
        return "Version control (SVN)";
    }

    @Override
    public List<AsyncToolSpecification> tools() {
        return List.of(svnDiffTool());
    }

    public AsyncToolSpecification svnDiffTool() {
        McpSchema.Tool tool = tool("svn_diff",
            "Runs 'svn diff' on an Eclipse project's working copy and returns the unified diff. "
                + "Without 'path' it diffs the whole project; 'path' narrows it to a file or folder. "
                + "'revision' is passed to 'svn -r' (e.g. BASE, HEAD, 1234, or 1200:HEAD); "
                + "the default compares the working copy against its base (last-updated) revision. "
                + "Requires the 'svn' command-line client on the PATH.",
            Map.of(
                "project", str(),
                "path", str("Project-relative file or folder to diff (default: whole project)"),
                "revision", str("Revision argument for 'svn -r', e.g. BASE, HEAD, 1234, 1200:HEAD")),
            List.of("project"));

        return new AsyncToolSpecification(tool, (exchange, req) -> Mono.defer(() -> {
            String projectName = param(req.arguments(), "project");
            if (projectName == null || projectName.isBlank()) return Mono.just(error("project is required"));

            IProject project;
            try {
                project = resolveProject(projectName);
            } catch (IllegalArgumentException e) {
                return Mono.just(error(e.getMessage()));
            }
            if (project.getLocation() == null)
                return Mono.just(error("Project has no on-disk location: " + projectName));
            File workingCopy = project.getLocation().toFile();

            String path = param(req.arguments(), "path");
            if (path != null && !path.isBlank()) {
                try {
                    requireContainedPath(path);
                } catch (IllegalArgumentException e) {
                    return Mono.just(error(e.getMessage()));
                }
            }
            String revision = param(req.arguments(), "revision");

            List<String> cmd = new ArrayList<>();
            cmd.add("svn");
            cmd.add("diff");
            if (revision != null && !revision.isBlank()) {
                cmd.add("-r");
                cmd.add(revision);
            }
            if (path != null && !path.isBlank())
                cmd.add(path);

            return Mono.just(runSvn(cmd, workingCopy));
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * Runs an svn command in {@code workingDir}, draining stdout+stderr on a separate thread so a
     * large diff cannot deadlock on a full pipe buffer, and killing the process past the timeout.
     * Output is capped at {@link ToolSupport#MAX_READ_BYTES}.
     */
    private static McpSchema.CallToolResult runSvn(List<String> cmd, File workingDir) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workingDir);
        pb.redirectErrorStream(true);

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            return error("Could not run 'svn' (is the Subversion command-line client on the PATH?): "
                + e.getMessage());
        }

        StringBuilder out = new StringBuilder();
        boolean[] truncated = {false};
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buf = new char[8192];
                int n;
                while ((n = br.read(buf)) != -1) {
                    if (out.length() < MAX_READ_BYTES) {
                        out.append(buf, 0, n);
                    } else {
                        truncated[0] = true; // keep draining so the process is not blocked
                    }
                }
            } catch (IOException ignored) {
                // stream closed on process exit/kill; whatever was read is kept
            }
        }, "svn-output-reader");
        reader.setDaemon(true);
        reader.start();

        try {
            if (!proc.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                reader.join(2000);
                return error("svn timed out after " + PROCESS_TIMEOUT_SECONDS + "s and was killed");
            }
            reader.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            return error("Interrupted while running svn");
        }

        String text = out.toString();
        if (truncated[0])
            text += "\n... [output truncated at " + MAX_READ_BYTES + " bytes]";

        int exit = proc.exitValue();
        if (exit != 0)
            return error("svn exited with code " + exit
                + (text.isBlank() ? "" : ":\n" + text));
        if (text.isBlank())
            return result("No differences.");
        return result(text);
    }
}
