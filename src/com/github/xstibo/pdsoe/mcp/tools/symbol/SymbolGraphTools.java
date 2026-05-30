package com.github.xstibo.pdsoe.mcp.tools.symbol;

import com.github.xstibo.pdsoe.mcp.tools.ToolProvider;
import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.describe;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.error;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.param;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.parseXml;
import static com.github.xstibo.pdsoe.mcp.tools.ToolSupport.result;

/**
 * Cross-reference symbol-graph tools backed by ABL-compiler {@code .xref.xml} files.
 * {@code build_symbol_index} populates a per-project in-memory index that the
 * caller/reference/hierarchy tools query. The index lives only for the JVM session.
 */
public class SymbolGraphTools implements ToolProvider {

    /** Per-project symbol indices, keyed by project name. Concurrent: MCP calls may overlap. */
    private final Map<String, SymbolIndex> projectIndices = new ConcurrentHashMap<>();

    @Override
    public List<AsyncToolSpecification> tools() {
        return List.of(getCalleesTool(), buildSymbolIndexTool(), getCallersTool(),
            findSymbolReferencesTool(), getTypeHierarchyTool());
    }

    public AsyncToolSpecification getCalleesTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("get_callees")
            .description("Returns the external RUN calls (.p files) and INVOKE (class method) calls made by a given ABL source file, read from its .xref.xml. Accepts an absolute xref_file_path, or falls back to the in-memory index built by build_symbol_index. Internal procedure calls (RUN without .p extension) are not covered by xref.")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "project", Map.of("type", "string"),
                    "file", Map.of("type", "string", "description", "Project-relative path to the source .p or .cls file"),
                    "xref_file_path", Map.of("type", "string", "description", "Optional: absolute path to the .xref.xml file (e.g. C:\\path\\to\\Xref\\module\\file.xref.xml)")
                ),
                List.of("project", "file"), null, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            String projectName = param(req.arguments(), "project");
            if (projectName == null || projectName.isBlank()) return Mono.just(error("project is required"));
            String filePath = param(req.arguments(), "file");
            if (filePath == null || filePath.isBlank()) return Mono.just(error("file is required"));
            String xrefFilePath = param(req.arguments(), "xref_file_path");

            if (xrefFilePath != null && !xrefFilePath.isBlank()) {
                java.io.File rawFile = new java.io.File(xrefFilePath);
                if (!rawFile.exists()) return Mono.just(error("xref file not found: " + xrefFilePath));
                try (InputStream xis = new java.io.FileInputStream(rawFile)) {
                    return Mono.just(parseCalleesFromXref(xis, filePath));
                } catch (Exception e) {
                    return Mono.just(error("Failed to read xref file: " + describe(e)));
                }
            }
            SymbolIndex idx = projectIndices.get(projectName);
            if (idx == null) return Mono.just(error("No index for project '" + projectName + "'. Run build_symbol_index first or supply xref_file_path."));
            String lookupKey = filePath.replace('\\', '/').toLowerCase();
            XrefRecord rec = idx.bySourceFile.get(lookupKey);
            if (rec == null) {
                final String suffix = "/" + lookupKey;
                rec = idx.bySourceFile.entrySet().stream()
                    .filter(e2 -> e2.getKey().endsWith(suffix) || e2.getKey().equals(lookupKey))
                    .map(java.util.Map.Entry::getValue)
                    .findFirst().orElse(null);
            }
            if (rec == null) return Mono.just(error("File not found in index: " + filePath + ". Run build_symbol_index first or supply xref_file_path."));
            StringBuilder sb2 = new StringBuilder();
            sb2.append("source_file: ").append(rec.sourceFile).append("\n");
            if (!rec.runs.isEmpty()) {
                sb2.append("\nExternal RUN calls (cross-file):\n");
                rec.runs.forEach(r -> sb2.append("  [line ").append(r.line).append("] ").append(r.target).append("\n"));
            } else {
                sb2.append("\nExternal RUN calls: (none)\n");
            }
            if (!rec.invokes.isEmpty()) {
                sb2.append("\nINVOKE (class method calls):\n");
                rec.invokes.forEach(inv -> sb2.append("  [line ").append(inv.line).append("] ").append(inv.classMethod).append("\n"));
            } else {
                sb2.append("\nINVOKE: (none)\n");
            }
            sb2.append("\nNote: Internal procedure calls (RUN without .p extension) are not available via xref.");
            return Mono.just(result(sb2.toString().trim()));
        });
    }

    private McpSchema.CallToolResult parseCalleesFromXref(InputStream is, String filePath) throws Exception {
        org.w3c.dom.Document doc = parseXml(is);
        org.w3c.dom.NodeList sources = doc.getElementsByTagName("Source");
        String sourceFile = sources.getLength() > 0
            ? ((org.w3c.dom.Element) sources.item(0)).getAttribute("File-name") : filePath;
        org.w3c.dom.NodeList refs = doc.getElementsByTagName("Reference");
        List<String> externalRuns = new ArrayList<>();
        List<String> invokeList = new ArrayList<>();
        for (int i = 0; i < refs.getLength(); i++) {
            org.w3c.dom.Element ref = (org.w3c.dom.Element) refs.item(i);
            String refType = ref.getAttribute("Reference-type");
            String objectId = ref.getAttribute("Object-identifier");
            org.w3c.dom.NodeList lineNums = ref.getElementsByTagName("Line-num");
            String lineAttr = (lineNums.getLength() > 0) ? lineNums.item(0).getTextContent().trim() : "";
            if ("RUN".equals(refType) && objectId.toLowerCase().contains(".p")) {
                externalRuns.add("  [line " + lineAttr + "] " + objectId);
            } else if ("INVOKE".equals(refType)) {
                invokeList.add("  [line " + lineAttr + "] " + objectId);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("source_file: ").append(sourceFile).append("\n");
        sb.append(externalRuns.isEmpty() ? "\nExternal RUN calls: (none)" :
            "\nExternal RUN calls (cross-file):\n" + String.join("\n", externalRuns));
        sb.append(invokeList.isEmpty() ? "\n\nINVOKE: (none)" :
            "\n\nINVOKE (class method calls):\n" + String.join("\n", invokeList));
        sb.append("\n\nNote: Internal procedure calls (RUN without .p extension) are not available via xref.");
        return result(sb.toString().trim());
    }

    /**
     * Walks the xref tree off the request thread (boundedElastic), parsing files in parallel and
     * merging into the per-project index serially (merge preserves walk order, so the index is
     * identical to a serial build). Emits throttled progress notifications when the caller supplies
     * a progressToken. Concurrent {@code build_symbol_index} calls for the <em>same</em> project
     * are still not supported; callers should not overlap them.
     */
    public AsyncToolSpecification buildSymbolIndexTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("build_symbol_index")
            .description("Builds an in-memory cross-file symbol index for a project by walking all .xref.xml files under xref_base_path. Subsequent calls are incremental: files unchanged since last index build are skipped. Required before using get_callers, find_symbol_references, or get_type_hierarchy.")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "project", Map.of("type", "string"),
                    "xref_base_path", Map.of("type", "string", "description", "Absolute path to the xref output directory, e.g. C:\\path\\to\\Xref"),
                    "scope", Map.of("type", "string", "description", "Optional subdirectory within xref_base_path to limit the scan, e.g. module\\submodule")
                ),
                List.of("project", "xref_base_path"), null, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> Mono.defer(() -> {
            String projectName = param(req.arguments(), "project");
            if (projectName == null || projectName.isBlank()) return Mono.just(error("project is required"));
            String xrefBasePath = param(req.arguments(), "xref_base_path");
            if (xrefBasePath == null || xrefBasePath.isBlank()) return Mono.just(error("xref_base_path is required"));
            String scope = param(req.arguments(), "scope");

            java.io.File baseDir = (scope != null && !scope.isBlank())
                ? new java.io.File(xrefBasePath, scope)
                : new java.io.File(xrefBasePath);
            if (!baseDir.exists() || !baseDir.isDirectory())
                return Mono.just(error("Directory not found: " + baseDir.getAbsolutePath()));

            SymbolIndex index = projectIndices.computeIfAbsent(projectName, k -> new SymbolIndex());

            // 1. collect candidate xref files
            List<java.nio.file.Path> candidates;
            try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(baseDir.toPath())) {
                candidates = walk.filter(p -> p.toString().endsWith(".xref.xml"))
                    .collect(java.util.stream.Collectors.toList());
            } catch (java.io.IOException e) {
                return Mono.just(error("Failed to walk directory: " + describe(e)));
            }

            // 2. serial pre-pass: skip unchanged / known-bad files, drop stale reverse-index
            //    entries for changed files, and build the parse worklist
            int skipped = 0;
            List<Task> worklist = new ArrayList<>();
            for (java.nio.file.Path p : candidates) {
                java.io.File f = p.toFile();
                long mtime = f.lastModified();
                String fileKey = p.toString().replace('\\', '/').toLowerCase();
                XrefRecord existing = index.byFile.get(fileKey);
                if (existing != null && existing.mtime == mtime) { skipped++; continue; }
                Long badMtime = index.knownBadFiles.get(fileKey);
                if (badMtime != null && badMtime == mtime) { skipped++; continue; }
                if (existing != null) {
                    existing.runs.forEach(r -> {
                        java.util.Set<String> callers = index.runCallers.get(r.target.replace('\\', '/').toLowerCase());
                        if (callers != null) callers.remove(fileKey);
                    });
                    existing.invokes.forEach(inv -> {
                        java.util.Set<String> callers = index.invokeCallers.get(inv.classMethod.toLowerCase());
                        if (callers != null) callers.remove(fileKey);
                    });
                }
                worklist.add(new Task(f, fileKey, mtime));
            }

            // 3. parse in parallel - no index access; progress is best-effort client keep-alive
            int total = worklist.size();
            Object progressToken = req.meta() == null ? null : req.meta().get("progressToken");
            java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
            List<ParseResult> results = worklist.parallelStream()
                .map(t -> {
                    ParseResult pr = parseOne(t.file(), t.fileKey(), t.mtime());
                    if (progressToken != null) {
                        int n = done.incrementAndGet();
                        if (n % 200 == 0 || n == total) {
                            exchange.progressNotification(new McpSchema.ProgressNotification(
                                progressToken, (double) n, (double) total, "Indexing xref... " + n + "/" + total))
                                .subscribe(v -> {}, err -> {});
                        }
                    }
                    return pr;
                })
                .collect(java.util.stream.Collectors.toList());

            // 4. serial merge into the index - encounter order is preserved, so insertion order
            //    (and therefore every tool's output ordering) is identical to a serial build
            int indexed = 0;
            List<String> warnings = new ArrayList<>();
            for (ParseResult pr : results) {
                if (pr.error != null) {
                    warnings.add("Failed to parse " + pr.fileName + ": " + pr.error);
                    index.knownBadFiles.put(pr.fileKey, pr.mtime);
                    continue;
                }
                XrefRecord rec = pr.record;
                String srcKey = rec.sourceFile.replace('\\', '/').toLowerCase();
                index.byFile.put(pr.fileKey, rec);
                index.bySourceFile.put(srcKey, rec);
                for (RunRef r : rec.runs) {
                    index.runCallers.computeIfAbsent(
                        r.target.replace('\\', '/').toLowerCase(),
                        k2 -> new java.util.LinkedHashSet<>()).add(pr.fileKey);
                }
                for (InvokeRef inv : rec.invokes) {
                    index.invokeCallers.computeIfAbsent(
                        inv.classMethod.toLowerCase(),
                        k2 -> new java.util.LinkedHashSet<>()).add(pr.fileKey);
                }
                String parent = rec.inheritance.get("INHERITS");
                if (parent != null) index.classParent.put(srcKey, parent);
                for (String iface : pr.interfaces) {
                    index.classInterfaces.computeIfAbsent(
                        srcKey, k2 -> new java.util.ArrayList<>()).add(iface);
                }
                indexed++;
            }

            if (index.byFile.isEmpty())
                return Mono.just(result("No .xref.xml files found under " + baseDir.getAbsolutePath()
                    + "\nEnable XREF XML in PDSOE: Project Properties -> OpenEdge Settings -> Compile -> Generate XREF-XML"));

            StringBuilder sb = new StringBuilder();
            sb.append("project: ").append(projectName).append("\n");
            sb.append("xref_base_path: ").append(baseDir.getAbsolutePath()).append("\n");
            sb.append("files_indexed: ").append(indexed).append("\n");
            sb.append("files_skipped (unchanged): ").append(skipped).append("\n");
            sb.append("total_in_index: ").append(index.byFile.size()).append("\n");
            if (!warnings.isEmpty()) {
                sb.append("warnings (").append(warnings.size()).append("):\n");
                warnings.stream().limit(20).forEach(w -> sb.append("  ").append(w).append("\n"));
                if (warnings.size() > 20) sb.append("  ... and ").append(warnings.size() - 20).append(" more\n");
            }
            return Mono.just(result(sb.toString().trim()));
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    /** A file queued for parsing, with its pre-computed index key and mtime. */
    private record Task(java.io.File file, String fileKey, long mtime) {}

    /** The result of parsing one xref file off-thread; merged into the index serially. */
    private static final class ParseResult {
        final String fileKey;
        final long mtime;
        final String fileName;
        final XrefRecord record;             // null on parse failure
        final java.util.List<String> interfaces;
        final String error;                  // null on success

        ParseResult(String fileKey, long mtime, String fileName,
                    XrefRecord record, java.util.List<String> interfaces, String error) {
            this.fileKey = fileKey;
            this.mtime = mtime;
            this.fileName = fileName;
            this.record = record;
            this.interfaces = interfaces;
            this.error = error;
        }
    }

    /** Parses one xref file into a local {@link XrefRecord} without touching the shared index. */
    private ParseResult parseOne(java.io.File f, String fileKey, long mtime) {
        try (InputStream xis = new java.io.FileInputStream(f)) {
            org.w3c.dom.Document doc = parseXml(xis);
            org.w3c.dom.NodeList srcs = doc.getElementsByTagName("Source");
            String sourceFile = srcs.getLength() > 0
                ? ((org.w3c.dom.Element) srcs.item(0)).getAttribute("File-name") : f.getName();
            XrefRecord rec = new XrefRecord();
            rec.sourceFile = sourceFile;
            rec.mtime = mtime;
            java.util.List<String> interfaces = new java.util.ArrayList<>();
            org.w3c.dom.NodeList refs2 = doc.getElementsByTagName("Reference");
            for (int i = 0; i < refs2.getLength(); i++) {
                org.w3c.dom.Element ref = (org.w3c.dom.Element) refs2.item(i);
                String refType = ref.getAttribute("Reference-type");
                String objectId = ref.getAttribute("Object-identifier");
                org.w3c.dom.NodeList lineNums = ref.getElementsByTagName("Line-num");
                String lineAttr = (lineNums.getLength() > 0) ? lineNums.item(0).getTextContent().trim() : "";
                int lineNum = 0;
                try { lineNum = Integer.parseInt(lineAttr); } catch (NumberFormatException ignore) {}
                switch (refType) {
                    case "RUN" -> {
                        if (objectId.toLowerCase().contains(".p")) rec.runs.add(new RunRef(objectId, lineNum));
                    }
                    case "INVOKE" -> rec.invokes.add(new InvokeRef(objectId, lineNum));
                    case "PROCEDURE", "PRIVATE-PROCEDURE" -> rec.procDecls.add(objectId);
                    case "INHERITS" -> rec.inheritance.put("INHERITS", objectId);
                    case "IMPLEMENTS" -> {
                        rec.inheritance.merge("IMPLEMENTS", objectId, (a, b) -> a + ", " + b);
                        interfaces.add(objectId);
                    }
                    default -> {}
                }
            }
            return new ParseResult(fileKey, mtime, f.getName(), rec, interfaces, null);
        } catch (Exception e) {
            return new ParseResult(fileKey, mtime, f.getName(), null, null, e.getMessage());
        }
    }

    public AsyncToolSpecification getCallersTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("get_callers")
            .description("Returns all files that call a given procedure or .p file, using the in-memory symbol index (run build_symbol_index first). Matches RUN calls by .p file path and INVOKE calls by ClassName:Method. Case-insensitive.")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "project", Map.of("type", "string"),
                    "procedure_or_file", Map.of("type", "string", "description", "Procedure name, .p file path, or ClassName:Method to find callers of")
                ),
                List.of("project", "procedure_or_file"), null, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            String projectName = param(req.arguments(), "project");
            if (projectName == null || projectName.isBlank()) return Mono.just(error("project is required"));
            String target = param(req.arguments(), "procedure_or_file");
            if (target == null || target.isBlank()) return Mono.just(error("procedure_or_file is required"));

            SymbolIndex idx = projectIndices.get(projectName);
            if (idx == null) return Mono.just(error("No index for project '" + projectName + "'. Run build_symbol_index first."));

            String lookupKey = target.replace('\\', '/').toLowerCase();
            java.util.Set<String> runSet = idx.runCallers.getOrDefault(lookupKey, java.util.Collections.emptySet());
            java.util.Set<String> invokeSet = idx.invokeCallers.getOrDefault(lookupKey, java.util.Collections.emptySet());

            if (runSet.isEmpty() && invokeSet.isEmpty())
                return Mono.just(result("No callers found for: " + target));

            StringBuilder sb = new StringBuilder();
            sb.append("callers of: ").append(target).append("\n");
            if (!runSet.isEmpty()) {
                sb.append("\nRUN callers (").append(runSet.size()).append("):\n");
                runSet.forEach(callerKey -> {
                    XrefRecord rec = idx.byFile.get(callerKey);
                    String src = rec != null ? rec.sourceFile : callerKey;
                    if (rec != null) {
                        rec.runs.stream()
                            .filter(r -> r.target.replace('\\', '/').toLowerCase().equals(lookupKey))
                            .forEach(r -> sb.append("  [line ").append(r.line).append("] ").append(src).append("\n"));
                    } else {
                        sb.append("  ").append(src).append("\n");
                    }
                });
            }
            if (!invokeSet.isEmpty()) {
                sb.append("\nINVOKE callers (").append(invokeSet.size()).append("):\n");
                invokeSet.forEach(callerKey -> {
                    XrefRecord rec = idx.byFile.get(callerKey);
                    String src = rec != null ? rec.sourceFile : callerKey;
                    if (rec != null) {
                        rec.invokes.stream()
                            .filter(inv -> inv.classMethod.toLowerCase().equals(lookupKey))
                            .forEach(inv -> sb.append("  [line ").append(inv.line).append("] ").append(src).append("\n"));
                    } else {
                        sb.append("  ").append(src).append("\n");
                    }
                });
            }
            return Mono.just(result(sb.toString().trim()));
        });
    }

    public AsyncToolSpecification findSymbolReferencesTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("find_symbol_references")
            .description("Finds all references to a symbol (procedure name, .p file path fragment, or ClassName:Method) across the project using the in-memory symbol index. Searches RUN calls, INVOKE calls, and procedure declarations. Run build_symbol_index first.")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "project", Map.of("type", "string"),
                    "symbol", Map.of("type", "string", "description", "Symbol to search for: procedure name, .p file path fragment, or ClassName:Method (case-insensitive substring match)")
                ),
                List.of("project", "symbol"), null, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            String projectName = param(req.arguments(), "project");
            if (projectName == null || projectName.isBlank()) return Mono.just(error("project is required"));
            String symbol = param(req.arguments(), "symbol");
            if (symbol == null || symbol.isBlank()) return Mono.just(error("symbol is required"));

            SymbolIndex idx = projectIndices.get(projectName);
            if (idx == null) return Mono.just(error("No index for project '" + projectName + "'. Run build_symbol_index first."));

            String symbolLower = symbol.toLowerCase();
            List<String> found = new ArrayList<>();
            idx.byFile.forEach((fileKey, rec) -> {
                rec.runs.forEach(r -> {
                    if (r.target.toLowerCase().contains(symbolLower))
                        found.add("  [RUN, line " + r.line + "] " + rec.sourceFile + " -> " + r.target);
                });
                rec.invokes.forEach(inv -> {
                    if (inv.classMethod.toLowerCase().contains(symbolLower))
                        found.add("  [INVOKE, line " + inv.line + "] " + rec.sourceFile + " -> " + inv.classMethod);
                });
                rec.procDecls.forEach(decl -> {
                    if (decl.toLowerCase().contains(symbolLower))
                        found.add("  [PROCEDURE decl] " + rec.sourceFile + ": " + decl);
                });
            });

            if (found.isEmpty()) return Mono.just(result("No references found for: " + symbol));
            StringBuilder sb = new StringBuilder();
            sb.append("references to '").append(symbol).append("' (").append(found.size()).append("):\n");
            found.forEach(line -> sb.append(line).append("\n"));
            return Mono.just(result(sb.toString().trim()));
        });
    }

    public AsyncToolSpecification getTypeHierarchyTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name("get_type_hierarchy")
            .description("Returns the inheritance hierarchy for an ABL class: its parent class, implemented interfaces, and all known subclasses. Uses the in-memory symbol index - run build_symbol_index first. class_name is matched case-insensitively against source file paths in the index.")
            .inputSchema(new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "project", Map.of("type", "string"),
                    "class_name", Map.of("type", "string", "description", "Class name or source file path fragment (case-insensitive), e.g. Doc/DocumentType or DocumentType.cls")
                ),
                List.of("project", "class_name"), null, null, null))
            .build();

        return new AsyncToolSpecification(tool, (exchange, req) -> {
            String projectName = param(req.arguments(), "project");
            if (projectName == null || projectName.isBlank()) return Mono.just(error("project is required"));
            String className = param(req.arguments(), "class_name");
            if (className == null || className.isBlank()) return Mono.just(error("class_name is required"));

            SymbolIndex idx = projectIndices.get(projectName);
            if (idx == null) return Mono.just(error("No index for project '" + projectName + "'. Run build_symbol_index first."));

            String classLower = className.replace('\\', '/').toLowerCase();
            String canonKey = idx.bySourceFile.containsKey(classLower) ? classLower :
                idx.bySourceFile.keySet().stream()
                    .filter(k2 -> k2.endsWith("/" + classLower) || k2.contains(classLower))
                    .findFirst().orElse(classLower);

            String parent = idx.classParent.getOrDefault(canonKey, null);
            java.util.List<String> ifaces = idx.classInterfaces.getOrDefault(canonKey, java.util.Collections.emptyList());
            List<String> subclasses = new ArrayList<>();
            idx.classParent.forEach((k2, v) -> {
                if (v.replace('\\', '/').toLowerCase().contains(classLower)) {
                    XrefRecord rec = idx.bySourceFile.get(k2);
                    subclasses.add(rec != null ? rec.sourceFile : k2);
                }
            });

            StringBuilder sb = new StringBuilder();
            sb.append("class: ").append(className).append("\n");
            sb.append("parent: ").append(parent != null ? parent : "(none / not in index)").append("\n");
            sb.append("interfaces: ").append(ifaces.isEmpty() ? "(none)" : String.join(", ", ifaces)).append("\n");
            sb.append("subclasses (").append(subclasses.size()).append("):\n");
            if (subclasses.isEmpty()) {
                sb.append("  (none found in index)\n");
            } else {
                subclasses.forEach(sc -> sb.append("  ").append(sc).append("\n"));
            }
            return Mono.just(result(sb.toString().trim()));
        });
    }
}
