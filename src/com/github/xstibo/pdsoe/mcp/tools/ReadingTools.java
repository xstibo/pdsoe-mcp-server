package com.github.xstibo.pdsoe.mcp.tools;

import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import reactor.core.publisher.Mono;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.MAX_READ_BYTES;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.describe;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.error;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.param;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.readAllLines;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.readPropath;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.resolveFile;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.result;

/** Code-reading tools: raw content, line ranges, ABL outline/method source/signature, and xref. */
public class ReadingTools implements ToolProvider {

    @Override
    public String domain() {
        return "Reading code";
    }

    @Override
    public List<AsyncToolSpecification> tools() {
        return List.of(readFileTool(), readLinesTool(), getFileOutlineTool(),
            getMethodSourceTool(), getMethodSignatureTool(), getXrefTool());
    }

    public AsyncToolSpecification readFileTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("read_file")
            .description("Reads the content of a file in the workspace")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "project", Map.of("type", "string"),
                    "path", Map.of("type", "string", "description", "Relative path, e.g. src/main.p")),
                List.of("project", "path"),
                null, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            String projectName = param(req.arguments(), "project");
            if (projectName == null || projectName.isBlank()) return Mono.just(error("project is required"));
            String path = param(req.arguments(), "path");
            if (path == null || path.isBlank()) return Mono.just(error("path is required"));
            IFile file;
            try {
                file = resolveFile(projectName, path);
            } catch (IllegalArgumentException e) {
                return Mono.just(error(e.getMessage()));
            }
            long size = file.getLocation().toFile().length();
            if (size > MAX_READ_BYTES) {
                return Mono.just(error("File is too large to read (" + size + " bytes); use read_lines to read a range"));
            }
            try (InputStream is = file.getContents()) {
                return Mono.just(result(new String(is.readAllBytes(), StandardCharsets.UTF_8)));
            } catch (Exception e) {
                return Mono.just(error(describe(e)));
            }
        });
    }

    public AsyncToolSpecification readLinesTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("read_lines")
            .description("Reads a specific line range from a workspace file without loading the entire file")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "project", Map.of("type", "string"),
                    "path", Map.of("type", "string"),
                    "start_line", Map.of("type", "integer", "description", "First line to read (1-based)"),
                    "end_line", Map.of("type", "integer", "description", "Last line to read (1-based, inclusive); omit or 0 for EOF")),
                List.of("project", "path", "start_line"),
                null, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            String projectName = param(req.arguments(), "project");
            if (projectName == null || projectName.isBlank()) return Mono.just(error("project is required"));
            String path = param(req.arguments(), "path");
            if (path == null || path.isBlank()) return Mono.just(error("path is required"));
            Number startLineObj = (Number) req.arguments().get("start_line");
            if (startLineObj == null) return Mono.just(error("start_line is required"));
            int startLine = startLineObj.intValue();
            if (startLine < 1) return Mono.just(error("start_line must be >= 1"));
            Object endLineRaw = req.arguments().get("end_line");
            int endLine = (endLineRaw instanceof Number n) ? n.intValue() : 0;
            if (endLine > 0 && endLine < startLine) return Mono.just(error("end_line must be >= start_line"));

            IFile file;
            try {
                file = resolveFile(projectName, path);
            } catch (IllegalArgumentException e) {
                return Mono.just(error(e.getMessage()));
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(file.getContents(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                int lineNum = 0;
                while ((line = reader.readLine()) != null) {
                    lineNum++;
                    if (lineNum < startLine) continue;
                    if (endLine > 0 && lineNum > endLine) break;
                    sb.append(lineNum).append(": ").append(line).append('\n');
                }
                return Mono.just(sb.isEmpty() ? result("(no lines in range)") : result(sb.toString()));
            } catch (Exception e) {
                return Mono.just(error(describe(e)));
            }
        });
    }

    public AsyncToolSpecification getFileOutlineTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("get_file_outline")
            .description("Returns the list of procedures, functions, methods, and classes defined in an ABL source file.")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "project", Map.of("type", "string"),
                    "path",    Map.of("type", "string", "description", "Relative path to the ABL file, e.g. src/customer.p")
                ),
                List.of("project", "path"), null, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            String projectName = param(req.arguments(), "project");
            if (projectName == null || projectName.isBlank()) return Mono.just(error("project is required"));
            String path = param(req.arguments(), "path");
            if (path == null || path.isBlank()) return Mono.just(error("path is required"));

            IFile file;
            try {
                file = resolveFile(projectName, path);
            } catch (IllegalArgumentException e) {
                return Mono.just(error(e.getMessage()));
            }
            IProject project = file.getProject();

            try (InputStream is = file.getContents()) {
                String propath = readPropath(project);
                org.prorefactor.proparse.support.IProparseEnvironment session = buildParseSession(propath);
                org.prorefactor.treeparser.ParseUnit pu = null;
                try {
                    pu = new org.prorefactor.treeparser.ParseUnit(is, path, session, StandardCharsets.UTF_8);
                    pu.treeParser01();

                    org.prorefactor.treeparser.TreeParserRootSymbolScope scope = pu.getRootScope();
                    if (scope == null) return Mono.just(error("Parse produced no symbol scope for: " + path));

                    List<org.prorefactor.treeparser.symbols.Routine> routines = scope.getRoutines();
                    if (routines.isEmpty()) return Mono.just(result("No outline entries found."));

                    StringBuilder sb = new StringBuilder();
                    for (org.prorefactor.treeparser.symbols.Routine r : routines) {
                        org.prorefactor.core.JPNode defNode = r.getDefineNode();
                        int line = defNode != null ? defNode.getLine() : 0;
                        sb.append(r.getNodeType().name()).append(" ").append(r.getName());
                        if (line > 0) sb.append(" (line ").append(line).append(")");
                        sb.append("\n");
                    }
                    return Mono.just(result(sb.toString().trim()));
                } finally {
                    pu = null;
                    session = null;
                }
            } catch (Exception e) {
                return Mono.just(error("Parse error: " + describe(e)));
            }
        });
    }

    public AsyncToolSpecification getMethodSourceTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("get_method_source")
            .description("Returns the source of a specific procedure, function, method, or constructor by name.")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "project", Map.of("type", "string"),
                    "path",    Map.of("type", "string", "description", "Relative path to the ABL file"),
                    "name",    Map.of("type", "string", "description", "Name of the procedure, function, method, or constructor (case-insensitive)")
                ),
                List.of("project", "path", "name"), null, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            String projectName = param(req.arguments(), "project");
            if (projectName == null || projectName.isBlank()) return Mono.just(error("project is required"));
            String path = param(req.arguments(), "path");
            if (path == null || path.isBlank()) return Mono.just(error("path is required"));
            String name = param(req.arguments(), "name");
            if (name == null || name.isBlank()) return Mono.just(error("name is required"));

            IFile file;
            try {
                file = resolveFile(projectName, path);
            } catch (IllegalArgumentException e) {
                return Mono.just(error(e.getMessage()));
            }
            IProject project = file.getProject();

            try {
                List<String> lines = readAllLines(file);
                String propath = readPropath(project);
                org.prorefactor.proparse.support.IProparseEnvironment session = buildParseSession(propath);
                org.prorefactor.treeparser.ParseUnit pu = null;
                try {
                    pu = new org.prorefactor.treeparser.ParseUnit(
                        String.join("\n", lines), path, session, StandardCharsets.UTF_8);
                    pu.treeParser01();

                    org.prorefactor.treeparser.TreeParserRootSymbolScope scope = pu.getRootScope();
                    if (scope == null) return Mono.just(error("Parse produced no symbol scope for: " + path));

                    List<org.prorefactor.treeparser.symbols.Routine> routines = scope.lookupRoutines(name);
                    if (routines == null || routines.isEmpty())
                        return Mono.just(result("No routine named '" + name + "' found in " + path));

                    org.prorefactor.treeparser.symbols.Routine routine = routines.get(0);
                    org.prorefactor.core.JPNode defNode = routine.getDefineNode();
                    if (defNode == null) return Mono.just(error("Could not locate definition node for: " + name));

                    int startLine = defNode.getLine();
                    int endLine = startLine;
                    org.prorefactor.core.JPNode endNode = defNode.getNextSibling();
                    while (endNode != null) {
                        endLine = endNode.getLine();
                        endNode = endNode.getNextSibling();
                    }
                    if (endLine < startLine) endLine = startLine;

                    List<String> sourceLines = lines.subList(
                        Math.max(0, startLine - 1),
                        Math.min(endLine, lines.size()));
                    return Mono.just(result(String.join("\n", sourceLines)));
                } finally {
                    pu = null;
                    session = null;
                }
            } catch (Exception e) {
                return Mono.just(error("Parse error: " + describe(e)));
            }
        });
    }

    public AsyncToolSpecification getMethodSignatureTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("get_method_signature")
            .description("Returns the signature (header + parameters) of a procedure, function, or class method, without the body.")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "project", Map.of("type", "string"),
                    "path",    Map.of("type", "string", "description", "Relative path to the ABL file"),
                    "name",    Map.of("type", "string", "description",
                        "Name of the procedure, function, or method (case-insensitive)")),
                List.of("project", "path", "name"), null, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            String projectName = param(req.arguments(), "project");
            if (projectName == null || projectName.isBlank()) return Mono.just(error("project is required"));
            String path = param(req.arguments(), "path");
            if (path == null || path.isBlank()) return Mono.just(error("path is required"));
            String name = param(req.arguments(), "name");
            if (name == null || name.isBlank()) return Mono.just(error("name is required"));

            IFile file;
            try {
                file = resolveFile(projectName, path);
            } catch (IllegalArgumentException e) {
                return Mono.just(error(e.getMessage()));
            }
            IProject project = file.getProject();

            try {
                List<String> lines = readAllLines(file);
                String propath = readPropath(project);
                org.prorefactor.proparse.support.IProparseEnvironment session = buildParseSession(propath);
                org.prorefactor.treeparser.ParseUnit pu = null;
                try {
                    pu = new org.prorefactor.treeparser.ParseUnit(
                        String.join("\n", lines), path, session, StandardCharsets.UTF_8);
                    pu.treeParser01();

                    org.prorefactor.treeparser.TreeParserRootSymbolScope scope = pu.getRootScope();
                    if (scope == null) return Mono.just(error("Parse produced no symbol scope for: " + path));

                    List<org.prorefactor.treeparser.symbols.Routine> routines = scope.lookupRoutines(name);
                    if (routines == null || routines.isEmpty())
                        return Mono.just(result("No routine named '" + name + "' found in " + path));

                    org.prorefactor.treeparser.symbols.Routine routine = routines.get(0);
                    org.prorefactor.core.JPNode defNode = routine.getDefineNode();
                    if (defNode == null) return Mono.just(error("Could not locate definition node for: " + name));

                    int startLine = defNode.getLine();
                    String nodeTypeName = routine.getNodeType().name();

                    List<String> sigLines = new ArrayList<>();
                    if ("PROCEDURE".equalsIgnoreCase(nodeTypeName)) {
                        sigLines.add(lines.get(startLine - 1));
                        Pattern paramPat = Pattern.compile(
                            "^\\s*DEFINE\\s+\\w+\\s+PARAMETER\\b", Pattern.CASE_INSENSITIVE);
                        boolean capped = true;
                        for (int i = startLine; i < Math.min(startLine + 30, lines.size()); i++) {
                            String ln = lines.get(i);
                            if (paramPat.matcher(ln).find()) {
                                sigLines.add(ln);
                            } else if (!ln.isBlank()
                                    && !ln.stripLeading().startsWith("//")
                                    && !ln.stripLeading().startsWith("/*")) {
                                capped = false;
                                break;
                            }
                        }
                        if (capped && sigLines.size() > 1) sigLines.add("[signature truncated at 30 lines]");
                    } else {
                        for (int i = startLine - 1; i < Math.min(startLine + 29, lines.size()); i++) {
                            String ln = lines.get(i);
                            sigLines.add(ln);
                            if (ln.stripTrailing().endsWith(":")) break;
                        }
                        if (!sigLines.isEmpty() && !sigLines.get(sigLines.size() - 1).stripTrailing().endsWith(":"))
                            sigLines.add("[signature truncated at 30 lines]");
                    }

                    return Mono.just(result(String.join("\n", sigLines)));
                } finally {
                    pu = null;
                    session = null;
                }
            } catch (Exception e) {
                return Mono.just(error("Parse error: " + describe(e)));
            }
        });
    }

    public AsyncToolSpecification getXrefTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("get_xref")
            .description("Parses a compiler-generated .xref.xml file and returns structured cross-reference data: table accesses, dynamic RUN calls, include files, class references, and defined procedures/functions. xref_path may be an absolute path (e.g. C:\\path\\to\\Xref\\module\\file.xref.xml) or a project-relative path.")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "project", Map.of("type", "string", "description", "Project name; required when xref_path is relative, optional for absolute paths"),
                    "xref_path", Map.of("type", "string", "description", "Absolute or project-relative path to the .xref.xml file")
                ),
                List.of("xref_path"), null, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            String projectName = param(req.arguments(), "project");
            String xrefPath = param(req.arguments(), "xref_path");
            if (xrefPath == null || xrefPath.isBlank()) return Mono.just(error("xref_path is required"));

            InputStream inputStream;
            java.io.File rawFile = new java.io.File(xrefPath);
            if (rawFile.isAbsolute()) {
                if (!rawFile.exists()) return Mono.just(error("File not found: " + xrefPath));
                try { inputStream = new java.io.FileInputStream(rawFile); }
                catch (java.io.IOException e) { return Mono.just(error("Cannot open file: " + describe(e))); }
            } else {
                if (projectName == null || projectName.isBlank()) return Mono.just(error("project is required for relative xref_path"));
                IFile eclipseFile;
                try { eclipseFile = resolveFile(projectName, xrefPath); }
                catch (IllegalArgumentException e) { return Mono.just(error(e.getMessage())); }
                try { inputStream = eclipseFile.getContents(); }
                catch (Exception e) { return Mono.just(error("Cannot read file: " + describe(e))); }
            }

            try (InputStream is = inputStream) {
                org.w3c.dom.Document doc = ToolSupport.parseXml(is);

                org.w3c.dom.NodeList sources = doc.getElementsByTagName("Source");
                String sourceFile = sources.getLength() > 0
                    ? ((org.w3c.dom.Element) sources.item(0)).getAttribute("File") : "";

                org.w3c.dom.NodeList refs = doc.getElementsByTagName("Reference");

                List<String> definitions = new ArrayList<>();
                Map<String, List<String>> tableAccesses = new LinkedHashMap<>();
                List<String> runs = new ArrayList<>();
                List<String> includes = new ArrayList<>();
                List<String> classes = new ArrayList<>();
                List<String> other = new ArrayList<>();

                for (int i = 0; i < refs.getLength(); i++) {
                    org.w3c.dom.Element ref = (org.w3c.dom.Element) refs.item(i);
                    String refType = ref.getAttribute("Reference-type");
                    String objectId = ref.getAttribute("Object-identifier");
                    String accessMode = ref.getAttribute("Access-mode");
                    switch (refType) {
                        case "DEFINE" -> definitions.add(objectId);
                        case "TABLE", "TEMPTABLE" -> {
                            tableAccesses.computeIfAbsent(objectId, k -> new ArrayList<>()).add(accessMode);
                        }
                        case "RUN" -> runs.add(objectId);
                        case "INCLUDE" -> includes.add(objectId);
                        case "CLASS", "CONSTRUCTOR", "INTERFACE" -> classes.add(refType + " " + objectId);
                        default -> {
                            if (!objectId.isBlank()) other.add(refType + ": " + objectId);
                        }
                    }
                }

                StringBuilder sb = new StringBuilder();
                sb.append("source_file: ").append(sourceFile).append("\n");
                if (!definitions.isEmpty()) sb.append("\ndefinitions:\n").append(String.join("\n", definitions.stream().map(d -> "  " + d).toList()));
                if (!tableAccesses.isEmpty()) {
                    sb.append("\ntable_accesses:\n");
                    tableAccesses.forEach((t, modes) -> sb.append("  ").append(t).append(": ").append(String.join(", ", modes)).append("\n"));
                }
                if (!runs.isEmpty()) sb.append("\nruns:\n").append(String.join("\n", runs.stream().map(r -> "  " + r).toList()));
                if (!includes.isEmpty()) sb.append("\nincludes:\n").append(String.join("\n", includes.stream().map(inc -> "  " + inc).toList()));
                if (!classes.isEmpty()) sb.append("\nclass_refs:\n").append(String.join("\n", classes.stream().map(c -> "  " + c).toList()));
                if (!other.isEmpty()) sb.append("\nother:\n").append(String.join("\n", other.stream().map(o -> "  " + o).toList()));

                return Mono.just(result(sb.toString().trim()));
            } catch (Exception e) {
                return Mono.just(error("XML parse error: " + describe(e)));
            }
        });
    }

    private org.prorefactor.proparse.support.IProparseEnvironment buildParseSession(String propath) {
        // proparse's ProparseSettings splits the propath on ',', but readPropath joins entries
        // with the OS pathSeparator (';' on Windows); convert so includes resolve across roots.
        String proparsePropath = propath.replace(java.io.File.pathSeparatorChar, ',');
        return new org.prorefactor.refactor.RefactorSession(
            new org.prorefactor.refactor.settings.ProparseSettings(proparsePropath),
            new org.prorefactor.core.schema.Schema());
    }
}
