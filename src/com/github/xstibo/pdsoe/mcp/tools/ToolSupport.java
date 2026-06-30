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

import com.github.xstibo.pdsoe.mcp.Activator;
import com.github.xstibo.pdsoe.mcp.preferences.PreferenceConstants;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
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

    // --- text-format preservation -------------------------------------------

    /**
     * The on-disk text format of a file: its charset, dominant line separator, and whether
     * it ends with a trailing newline. Edits capture this before rewriting so they preserve
     * the file's terminators - a forced-LF rewrite of a CRLF file makes SVN/Git report every
     * line changed (see CLAUDE.md Gotchas).
     */
    public record TextFormat(Charset charset, String lineSeparator, boolean endsWithNewline) {
        /** Fallback when the format cannot be read (e.g. a brand-new file). */
        public static final TextFormat DEFAULT =
            new TextFormat(StandardCharsets.UTF_8, System.lineSeparator(), true);
    }

    /** The file's effective Eclipse charset (file -&gt; container -&gt; workspace default); UTF-8 on failure. */
    public static Charset fileCharset(IFile file) {
        try {
            return Charset.forName(file.getCharset());
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    /**
     * Detects {@code file}'s on-disk {@link TextFormat} by reading its raw content once. For a
     * file that does not exist yet, returns the inherited charset with platform-default EOL.
     */
    public static TextFormat detectFormat(IFile file) throws CoreException, IOException {
        Charset cs = fileCharset(file);
        if (!file.exists()) return new TextFormat(cs, System.lineSeparator(), true);
        byte[] bytes;
        try (InputStream is = file.getContents()) {
            bytes = is.readAllBytes();
        }
        String content = new String(bytes, cs);
        String sep = content.contains("\r\n") ? "\r\n"
            : content.indexOf('\r') >= 0 ? "\r"
            : "\n";
        char last = content.isEmpty() ? '\0' : content.charAt(content.length() - 1);
        boolean endsWithNewline = last == '\n' || last == '\r';
        return new TextFormat(cs, sep, endsWithNewline);
    }

    /** Rewrites every line ending in {@code s} to {@code sep} (collapsing CRLF/CR/LF first). */
    public static String normalizeLineEndings(String s, String sep) {
        String lf = s.replace("\r\n", "\n").replace("\r", "\n");
        return sep.equals("\n") ? lf : lf.replace("\n", sep);
    }

    // --- small-file IO ------------------------------------------------------

    public static List<String> readAllLines(IFile file) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getContents(), fileCharset(file)))) {
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
            return lines;
        }
    }

    /**
     * Writes {@code lines} back to {@code file}, preserving the file's existing charset, line
     * separator, and trailing-newline state (detected from the still-unmodified on-disk
     * content). {@code readLine()} strips terminators, so without this the file would be
     * rewritten with LF and a forced trailing newline - see {@link TextFormat}.
     */
    public static void writeAllLines(IFile file, List<String> lines, IProgressMonitor monitor) throws CoreException {
        TextFormat fmt;
        try {
            fmt = detectFormat(file);
        } catch (CoreException | IOException e) {
            fmt = TextFormat.DEFAULT;
        }
        StringBuilder sb = new StringBuilder(String.join(fmt.lineSeparator(), lines));
        if (fmt.endsWithNewline()) sb.append(fmt.lineSeparator());
        byte[] bytes = sb.toString().getBytes(fmt.charset());
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
            boolean superClassCompileError = false;
            for (IMarker m : markers) {
                int severity = (Integer) m.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
                String label = switch (severity) {
                    case IMarker.SEVERITY_ERROR -> "ERROR";
                    case IMarker.SEVERITY_WARNING -> "WARNING";
                    default -> "INFO";
                };
                String msg = (String) m.getAttribute(IMarker.MESSAGE, "");
                if (msg.contains("(12918)")) superClassCompileError = true;
                int line = (Integer) m.getAttribute(IMarker.LINE_NUMBER, 0);
                String file = m.getResource().getProjectRelativePath().toString();
                sb.append(label).append(" ").append(file).append(":").append(line).append(" ").append(msg).append("\n");
            }
            if (superClassCompileError) {
                // The OpenEdge compiler validates an interface/implementation mismatch in a
                // super class only when a subclass is compiled. The super may build clean on
                // its own, or (on newer compilers) surface its own error on the offending member;
                // either way the 12918 on the subclass points away from the real cause.
                sb.append("\nHint: error 12918 (could not compile a super class) usually means an")
                  .append(" interface/implementation mismatch inside that super class - a method-signature")
                  .append(" or property accessor-visibility difference (e.g. interface 'GET. SET.' vs class")
                  .append(" 'PUBLIC GET. PUBLIC SET.'). Build the super class on its own and compare it against")
                  .append(" the interfaces it implements; it may also report its own error (e.g. 12942) on")
                  .append(" the offending member.\n");
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

    // --- svn executable resolution ------------------------------------------

    /**
     * Resolves the Subversion command-line executable to run, in priority order:
     * (1) the configured {@code svn.executable} preference, when it points at a real
     * file; (2) the platform "svn" found on the {@code PATH}; (3) a well-known install
     * location ({@link #svnProbeLocations()}). Returns null if none is found - callers
     * should surface {@link #svnNotFoundMessage()} to the user.
     *
     * <p>Using absolute paths from (3) sidesteps the "IDE inherited a stale PATH at
     * startup" problem: a freshly installed svn is found without restarting the IDE.
     */
    public static String resolveSvnExecutable() {
        String configured = Activator.getDefault().getPreferenceStore()
            .getString(PreferenceConstants.KEY_SVN_EXECUTABLE);
        if (configured != null && !configured.isBlank()) {
            File f = new File(configured.trim());
            if (f.isFile()) return f.getAbsolutePath();
            // Configured but missing: fall through to auto-detection rather than hard-fail.
        }
        String onPath = findExecutableOnPath(svnExeName());
        if (onPath != null) return onPath;
        for (String candidate : svnProbeLocations()) {
            if (new File(candidate).isFile()) return candidate;
        }
        return null;
    }

    /** User-facing message describing how svn was looked for and how to fix a miss. */
    public static String svnNotFoundMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append("Could not find the Subversion command-line client ('")
          .append(svnExeName()).append("'). Looked on the PATH and at:\n");
        for (String candidate : svnProbeLocations()) {
            sb.append("  ").append(candidate).append("\n");
        }
        sb.append("\nTo fix, do one of:\n")
          .append("  - Install a Subversion CLI (e.g. SlikSVN, or enable TortoiseSVN's\n")
          .append("    'command line client tools' component), then restart the IDE.\n")
          .append("  - Set the full path to svn under Window > Preferences > PDSOE MCP Server\n")
          .append("    (Version Control).\n")
          .append("Note: the IDE inherits the PATH from when it started - if svn was just\n")
          .append("installed and is on the PATH, restart the IDE (or set the path above).");
        return sb.toString();
    }

    private static String svnExeName() {
        return isWindowsOs() ? "svn.exe" : "svn";
    }

    private static boolean isWindowsOs() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** Scans the {@code PATH} for an executable, returning its absolute path or null. */
    private static String findExecutableOnPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) continue;
            File f = new File(dir.trim(), exe);
            if (f.isFile()) return f.getAbsolutePath();
        }
        return null;
    }

    /** Well-known absolute locations of the svn client, OS-specific (for probing + diagnostics). */
    public static List<String> svnProbeLocations() {
        List<String> out = new ArrayList<>();
        if (isWindowsOs()) {
            String pf = System.getenv("ProgramFiles");
            String pfx86 = System.getenv("ProgramFiles(x86)");
            if (pf == null || pf.isBlank()) pf = "C:\\Program Files";
            if (pfx86 == null || pfx86.isBlank()) pfx86 = "C:\\Program Files (x86)";
            for (String base : new String[] { pf, pfx86 }) {
                out.add(base + "\\TortoiseSVN\\bin\\svn.exe");
                out.add(base + "\\SlikSvn\\bin\\svn.exe");
                out.add(base + "\\Subversion\\bin\\svn.exe");
                out.add(base + "\\CollabNet\\Subversion Client\\svn.exe");
                out.add(base + "\\VisualSVN\\bin\\svn.exe");
            }
        } else {
            out.add("/usr/bin/svn");
            out.add("/usr/local/bin/svn");
            out.add("/opt/homebrew/bin/svn");
            out.add("/opt/local/bin/svn");
        }
        return out;
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
