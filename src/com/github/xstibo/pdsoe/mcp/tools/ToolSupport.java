package com.github.xstibo.pdsoe.mcp.tools;

import io.modelcontextprotocol.spec.McpSchema;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IFileEditorInput;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Stateless helpers shared by every {@link ToolProvider}: result/error wrappers,
 * argument extraction, project/file resolution with path containment, marker
 * collection, small-file IO, a JSON-schema DSL, and a hardened (XXE-safe) XML parser.
 *
 * <p>Designed to be statically imported (e.g. {@code import static ...ToolSupport.*})
 * so call sites read as {@code result(...)}, {@code require(...)}, {@code tool(...)}.
 */
public final class ToolSupport {

    private ToolSupport() {}

    // --- shared constants ---------------------------------------------------

    /** Maximum size (bytes) {@code read_file} will load in one call. */
    public static final long MAX_READ_BYTES = 1_048_576;
    /** Maximum number of matches {@code search_in_files} returns before truncating. */
    public static final int MAX_SEARCH_MATCHES = 500;
    /** PDSOE's ABL incremental builder id (confirmed against PDSOE 12.8). */
    public static final String ABL_BUILDER_ID = "com.openedge.pdt.text.progressBuilder";

    // --- result wrappers ----------------------------------------------------

    public static McpSchema.CallToolResult result(String text) {
        return McpSchema.CallToolResult.builder().addTextContent(text).isError(false).build();
    }

    public static McpSchema.CallToolResult error(String message) {
        return McpSchema.CallToolResult.builder().addTextContent("Error: " + message).isError(true).build();
    }

    // --- argument extraction ------------------------------------------------

    /** @return the trimmed value of {@code key}, or {@code null} if absent. */
    public static String param(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        return v == null ? null : v.toString().strip();
    }

    /**
     * @return the trimmed, non-blank value of {@code key}.
     * @throws IllegalArgumentException carrying a user-facing "&lt;key&gt; is required" message.
     */
    public static String require(Map<String, Object> args, String key) {
        String v = param(args, key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException(key + " is required");
        return v;
    }

    public static String describe(Exception e) {
        String msg = e.getMessage();
        return e.getClass().getSimpleName() + (msg != null ? ": " + msg : "");
    }

    // --- project / file resolution -----------------------------------------

    public static IProject resolveProject(String projectName) {
        if (projectName == null || projectName.isBlank())
            throw new IllegalArgumentException("projectName is required");
        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!p.exists())
            throw new IllegalArgumentException("Project not found: " + projectName);
        if (!p.isOpen())
            throw new IllegalArgumentException("Project is closed: " + projectName);
        return p;
    }

    public static IFile resolveFile(String projectName, String filePath) {
        IProject p = resolveProject(projectName);
        if (filePath == null || filePath.isBlank())
            throw new IllegalArgumentException("filePath is required");
        requireContainedPath(filePath);
        IFile f = p.getFile(filePath);
        if (!f.exists())
            throw new IllegalArgumentException("File not found: " + filePath);
        return f;
    }

    /**
     * Rejects project-relative paths that try to escape the project via {@code ..} segments.
     * The tool surface is reachable over HTTP, so every caller-supplied path must be contained.
     *
     * <p>Uses a segment check rather than canonical-filesystem containment so that legitimate
     * Eclipse <em>linked</em> resources - common in PDSOE for shared PROPATH source whose
     * on-disk location is outside the project tree - are still reachable by their normal
     * in-workspace path.
     */
    public static void requireContainedPath(String filePath) {
        String normalized = filePath.replace('\\', '/');
        for (String segment : normalized.split("/")) {
            if (segment.equals(".."))
                throw new IllegalArgumentException("path must not contain '..' segments: " + filePath);
        }
    }

    /**
     * Resolves the {@link IFile} behind an editor input. PDSOE's ABL editor input does not
     * adapt to {@link IFile}, so {@link IFileEditorInput} must be checked first (BUG-007).
     *
     * @return the file, or {@code null} if the input is not file-backed.
     */
    public static IFile resolveEditorFile(IEditorInput input) {
        if (input instanceof IFileEditorInput fei) return fei.getFile();
        return input == null ? null : input.getAdapter(IFile.class);
    }

    /**
     * Reads and flattens a project's {@code .propath} into a platform-path-separated string,
     * resolving {@code @{ROOT}} to the project's filesystem location. Returns "" if absent or
     * unreadable (PROPATH is optional).
     */
    public static String readPropath(IProject project) {
        try {
            IFile propathFile = project.getFile(".propath");
            if (!propathFile.exists()) return "";
            Document doc;
            try (InputStream is = propathFile.getContents()) {
                doc = parseXml(is);
            }
            String projectRoot = project.getLocation().toOSString();
            org.w3c.dom.NodeList entries = doc.getElementsByTagName("propathentry");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < entries.getLength(); i++) {
                org.w3c.dom.Element entry = (org.w3c.dom.Element) entries.item(i);
                String path = entry.getAttribute("path").replace("@{ROOT}", projectRoot);
                if (sb.length() > 0) sb.append(java.io.File.pathSeparator);
                sb.append(path);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // --- small-file IO ------------------------------------------------------

    public static List<String> readAllLines(IFile file) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getContents(), StandardCharsets.UTF_8))) {
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
            return lines;
        }
    }

    public static void writeAllLines(IFile file, List<String> lines, IProgressMonitor monitor) throws CoreException {
        byte[] bytes = (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
        file.setContents(new ByteArrayInputStream(bytes), IResource.KEEP_HISTORY, monitor);
    }

    public static void ensureParentFolders(IFile file, IProgressMonitor monitor) throws CoreException {
        IContainer parent = file.getParent();
        if (parent instanceof IFolder folder) ensureFolder(folder, monitor);
    }

    public static void ensureFolder(IFolder folder, IProgressMonitor monitor) throws CoreException {
        if (!folder.exists()) {
            if (folder.getParent() instanceof IFolder parent) ensureFolder(parent, monitor);
            folder.create(IResource.NONE, true, monitor);
        }
    }

    public static int countOccurrences(String text, String search) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(search, idx)) >= 0) { count++; idx += search.length(); }
        return count;
    }

    public static String collectMarkers(IResource resource) {
        try {
            IMarker[] markers = resource.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
            if (markers.length == 0) return "No problems found.";
            StringBuilder sb = new StringBuilder();
            for (IMarker m : markers) {
                int severity = (Integer) m.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
                String label = switch (severity) {
                    case IMarker.SEVERITY_ERROR -> "ERROR";
                    case IMarker.SEVERITY_WARNING -> "WARNING";
                    default -> "INFO";
                };
                String msg = (String) m.getAttribute(IMarker.MESSAGE, "");
                int line = (Integer) m.getAttribute(IMarker.LINE_NUMBER, 0);
                String file = m.getResource().getProjectRelativePath().toString();
                sb.append(label).append(" ").append(file).append(":").append(line).append(" ").append(msg).append("\n");
            }
            return sb.toString().trim();
        } catch (CoreException e) {
            return "Error reading markers: " + describe(e);
        }
    }

    // --- JSON-schema DSL ----------------------------------------------------

    public static Map<String, Object> str() {
        return Map.of("type", "string");
    }

    public static Map<String, Object> str(String description) {
        return Map.of("type", "string", "description", description);
    }

    public static Map<String, Object> bool(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    public static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "description", description);
    }

    /** Builds a tool with an object input schema. */
    public static McpSchema.Tool tool(String name, String description,
                                      Map<String, Object> properties, List<String> required) {
        return McpSchema.Tool.builder()
            .name(name)
            .description(description)
            .inputSchema(new McpSchema.JsonSchema("object", properties, required, false, null, null))
            .build();
    }

    /** Builds a tool that takes no arguments. */
    public static McpSchema.Tool tool(String name, String description) {
        return tool(name, description, Map.of(), null);
    }

    // --- hardened XML -------------------------------------------------------

    /**
     * Parses XML with external entities and DOCTYPE declarations disabled (XXE-safe).
     * The caller owns the stream lifecycle; wrap the call in try-with-resources.
     *
     * <p>The source stream is buffered before the illegal-byte filter so the parser's
     * byte reads hit memory instead of one syscall per byte - critical for large
     * unbuffered {@code FileInputStream}s (e.g. multi-MB {@code .xref.xml} files).
     */
    public static Document parseXml(InputStream is)
            throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);
        return dbf.newDocumentBuilder().parse(stripIllegalXml(new BufferedInputStream(is, 1 << 16)));
    }

    /**
     * Wraps a stream to drop XML-1.0-illegal control bytes (`< 0x20` except tab/LF/CR) that
     * some OpenEdge-generated `.xref.xml` files embed in attribute values (raw U+0001 to U+0004).
     * The illegal bytes are single-byte ASCII and never occur inside a UTF-8 multibyte sequence,
     * so byte-level filtering is encoding-safe. A no-op on clean XML.
     */
    private static InputStream stripIllegalXml(InputStream in) {
        return new FilterInputStream(in) {
            @Override
            public int read() throws IOException {
                int b;
                while ((b = super.read()) != -1 && b < 0x20 && b != '\t' && b != '\n' && b != '\r') {
                    // skip illegal control byte
                }
                return b;
            }

            @Override
            public int read(byte[] buf, int off, int len) throws IOException {
                // Bulk-read a block from the (buffered) underlying stream, then compact illegal
                // control bytes out in place. Reading by block avoids the per-byte syscall cost
                // of looping the single-byte read() over an unbuffered source.
                int n = super.read(buf, off, len);
                if (n <= 0) return n;
                int w = off;
                for (int i = off; i < off + n; i++) {
                    int b = buf[i] & 0xFF;
                    if (b >= 0x20 || b == '\t' || b == '\n' || b == '\r') buf[w++] = buf[i];
                }
                int kept = w - off;
                // If the whole block was illegal control bytes (kept == 0) but the stream is not
                // at EOF, retry rather than report a misleading 0 from a non-empty stream.
                return kept == 0 ? read(buf, off, len) : kept;
            }
        };
    }
}
