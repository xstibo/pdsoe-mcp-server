# CLAUDE.md

Guidance for Claude Code (claude.ai/code) when working in this repository.

## What this project is

An Eclipse OSGi plugin that embeds an MCP (Model Context Protocol) server inside **Progress
Developer Studio for OpenEdge (PDSOE)**. On IDE startup the plugin launches a Jetty HTTP
server on `http://127.0.0.1:8123` that exposes Eclipse workspace operations as MCP tools, so
AI assistants can interact with ABL (OpenEdge ABL) projects.

> **Not to be confused with the dev tooling.** The `eclipse-coder` / `eclipse-ide` MCP tools
> *used to develop this plugin* are a separate, generic product. This project builds a
> purpose-built MCP server *for PDSOE*; it has nothing to do with that tooling.

## Status

52 MCP tools, split by domain into `ToolProvider` classes (see Architecture; the original
monolithic `AblTools` was refactored away). Compiles clean, runs in PDSOE, and is verified
live against PDSOE 12.8 on a large production ABL project.
No known open bugs. Published (MIT) at https://github.com/xstibo/pdsoe-mcp-server as a single
clean commit (the pre-publish history is kept only on the local `main-archive-private` branch,
never pushed, because it contained work-project IDs and a dev bearer token). Building additional
features next; remaining work is under "Roadmap".

## Working agreement

**Update this file as you go — not at the end of the session.** When you add a tool, change
the architecture, add a dependency, or change setup: update the relevant section here, and
update `README.md` whenever a tool is added or setup/instructions change.

## Tooling instructions

**For Java source (`.java`), use the Eclipse MCP server tools, not the built-in Read/Edit/Write:**
- Read: `mcp__eclipse-ide__getSource`, `getFilteredSource`, `getMethodSource`, `getClassOutline`
- Find: `mcp__eclipse-ide__findFiles`, `fileSearch`
- Edit: `mcp__eclipse-coder__replaceString`, `replaceFileContent`, `insertIntoFile`, `deleteLinesInFile`
- Create: `mcp__eclipse-coder__createFile`
- Verify: `mcp__eclipse-ide__getCompilationErrors` after edits

For all other files (CLAUDE.md, MANIFEST.MF, plugin.xml, build.properties, README.md) use the
built-in `Read` / `Edit` / `Write` tools.

Notes:
- **Use ASCII punctuation in Java code/comments** — em/en dashes (`—`/`–`) round-trip to
  mojibake through these tools and then can't be matched back. Use `-`, `;`, `->`.
- `.mcp.json` is gitignored — it holds the local Eclipse MCP credentials (bearer token,
  `localhost:8124`) needed during development. Keep it local; never commit it.

## Build & run

This is an **Eclipse PDE** project — no Maven/Gradle/CLI build. Everything happens in Eclipse:
- **Build**: `Project > Build Project` (or `Alt+B`). Compiled classes go to `bin/` (gitignored).
- **Run/Debug**: `Run > Run As > Eclipse Application` (PDE launch).
- **Export**: `File > Export > Plug-in Development > Deployable plug-ins and fragments` →
  installable JAR. Install by dropping it into PDSOE's `dropins/` (delete the old versioned
  JAR first — the version qualifier changes each export) and restarting PDSOE.

## Architecture

Two top-level classes in `src/com/github/xstibo/pdsoe/mcp/`, plus a `tools` subpackage holding
the 52 tools split by domain:

| File | Role |
|---|---|
| `Activator.java` | OSGi `BundleActivator` — entry point. Eclipse calls `start()` at IDE startup (registered in `plugin.xml` as an `org.eclipse.ui.startup` extension). Creates and starts `McpServerManager`. |
| `McpServerManager.java` | Wires Jetty 12 + MCP Java SDK. Binds `127.0.0.1:8123`, streamable HTTP at `/mcp`. Aggregates every `ToolProvider`'s tools (flat-map over a `List<ToolProvider>`) and registers them in one `.tools(...)` call; logs the registered count. |

**Tool providers** — `src/com/github/xstibo/pdsoe/mcp/tools/`:

| File | Role |
|---|---|
| `ToolProvider.java` | Interface: `List<AsyncToolSpecification> tools()`. One implementation per domain. |
| `ToolSupport.java` | Stateless shared helpers (statically imported): `result`/`error`/`param`/`require`, `resolveProject`/`resolveFile`/`requireContainedPath`/`resolveEditorFile`, `readPropath`, small-file IO, marker collection, a JSON-schema DSL (`tool(...)`, `str()`/`bool()`/`integer()`), the hardened XXE-safe `parseXml`, and shared constants (`MAX_READ_BYTES`, `MAX_SEARCH_MATCHES`, `ABL_BUILDER_ID`). |
| `WorkspaceTools` (18), `ReadingTools` (6), `EditorStateTools` (7), `DiagnosticsTools` (6), `EditingTools` (7), `FileHistoryTools` (3) | The 52 tools grouped by the categories below; each calls Eclipse workspace APIs (`IProject`, `IFile`, `IMarker`, `WorkspaceJob`). |
| `tools/symbol/` | `SymbolGraphTools` (5 tools) plus its package-private data types `RunRef`/`InvokeRef`/`XrefRecord`/`SymbolIndex`. Owns the per-project in-memory index (`SymbolGraphTools.projectIndices`, a `ConcurrentHashMap`). |

**Plugin wiring**: `plugin.xml` registers `Activator` as a startup extension. `MANIFEST.MF`
declares OSGi `Require-Bundle` deps (`org.eclipse.core.runtime`, `core.resources`,
`debug.core`, `ui`, `ui.ide`, `swt`, `jface.text`, `ui.workbench.texteditor`, `search`,
`ui.console`) and lists every JAR in `lib/` on the bundle classpath. Note: `org.eclipse.ui.ide`
is required for `IFileEditorInput` (a split package — the interface ships in `ui.ide`, not
`ui.workbench`).

## MCP tools

### Workspace & file navigation
_(standard `IWorkspace` / `IProject` / `IFile` APIs)_

| Tool | Description |
|---|---|
| `list_projects` | All projects in the workspace (open/closed) |
| `list_abl_files` | ABL files (`.p`, `.cls`, `.i`, `.w`, `.t`) in a project |
| `list_all_files` | All files in a project with optional extension filter |
| `search_files` | Find files by name or glob pattern (bare names auto-wrap to `**/*name*`) |
| `search_in_files` | Grep-like content search (`TextSearchEngine`); capped at `MAX_SEARCH_MATCHES` (500) |
| `get_project_info` | Project natures, description, and properties |
| `get_propath` | Configured PROPATH; parses `.propath` XML, resolves `@{ROOT}`, one entry per line |
| `list_includes` | `.i` files directly referenced in a source file (regex scan, no xref needed) |
| `find_files_including` | All ABL files that include a given `.i` file (reverse of `list_includes`) |
| `get_file_info` | File metadata: size, exists, last modified, line count |
| `delete_file` / `move_file` / `copy_file` | Delete / rename-move / duplicate a file |
| `create_folder` / `delete_folder` | Create / delete a folder (and contents) |
| `refresh_project` | Re-sync a project with the file system after external changes |
| `get_project_layout` | Tree view of all files and folders in a project |
| `file_search_regexp` | Find files whose names match a Java regex (project-relative results) |

### Reading code
_(standard `IFile.getContents()` + ABL proparse parser for structure)_

| Tool | Description |
|---|---|
| `read_file` | Full file content (capped at `MAX_READ_BYTES`, 1 MB) |
| `read_lines` | A line range, each line prefixed `N: content` |
| `get_file_outline` | Procedures, functions, classes in a file (proparse) |
| `get_method_source` | Source of a named procedure/function/method (proparse) |
| `get_method_signature` | Signature only (header + parameters, no body) (proparse) |
| `get_xref` | Parsed `.xref.xml` cross-reference data; absolute or project-relative path |

### Symbol graph
_(xref-xml backed; run `build_symbol_index` first for the project-wide tools)_

| Tool | Description |
|---|---|
| `get_callees` | External RUN (`.p`) and INVOKE (class method) calls from a file; single `.xref.xml` or the in-memory index |
| `build_symbol_index` | Walks all `.xref.xml` under `xref_base_path`, builds an in-memory cross-file index; incremental (mtime); parses in parallel |
| `get_callers` | Files that RUN/INVOKE a given procedure/`.p`/class method (requires index) |
| `find_symbol_references` | All references (RUN/INVOKE/PROCEDURE decl) to a symbol (requires index) |
| `get_type_hierarchy` | Parent class, interfaces, known subclasses of an ABL class (requires index) |

### Editor state
_(standard `IWorkbench` / `ITextEditor` / `ITextSelection` APIs)_

| Tool | Description |
|---|---|
| `get_open_file` | Workspace path of the active editor file |
| `get_cursor_position` / `get_selection` | Cursor line/column / selected text and range |
| `list_open_editors` | All open editor tabs |
| `navigate_to` | Open a file in the editor, optionally at a line |
| `save_file` / `save_all` | Save one open editor (triggers ABL compile) / all dirty editors |

### Diagnostics & build
_(standard `IMarker` + `WorkspaceJob`)_

| Tool | Description |
|---|---|
| `get_markers` / `get_markers_for_file` | Problem markers for a project / a file |
| `build_project` | Full or incremental build via the ABL builder; returns markers |
| `build_file` | Incremental build, returns only that file's markers |
| `clean_project` | Clean build — removes output and rebuilds |
| `get_console_output` | Content of the last active Eclipse console |

### Code editing
_(standard `IFile.setContents()` + Eclipse local history)_

| Tool | Description |
|---|---|
| `write_file` | Write full content (creates the file if missing) |
| `insert_at_line` / `replace_lines` / `delete_lines_in_file` | Line-based edits |
| `replace_in_file` | Find-and-replace (text or regex) |
| `apply_patch` | Apply a unified diff |
| `undo_edit` | Restore the previous state from local history |
| `list_file_history` / `get_file_history_content` / `diff_file` | List / read / diff local-history snapshots |

### Not feasible (no public PDSOE API)
- **PROPATH-aware name resolution** (resolving `RUN procedure-name` without `.p` to a specific
  source file) would need Progress's internal PDSOE Java APIs — undocumented, unsupported.
- **Internal procedure call sites**: xref records *declarations*, not call sites for procedures
  within the same file. Full `get_callees` coverage of internal calls needs a proparse AST walk
  (deferred — see Roadmap).

## Adding new tools

1. Pick the provider class for the tool's domain (`WorkspaceTools`, `ReadingTools`,
   `SymbolGraphTools`, `EditorStateTools`, `DiagnosticsTools`, `EditingTools`,
   `FileHistoryTools`) and add a `public AsyncToolSpecification myTool()` method.
2. Add `myTool()` to that provider's `tools()` list. **No `McpServerManager` change needed** —
   it flat-maps over every provider automatically.
3. Build the tool with the `ToolSupport` DSL (statically imported):
   ```java
   McpSchema.Tool tool = tool("tool_name", "What the tool does",
       Map.of("project", str(), "path", str("Relative path")),
       List.of("project", "path"));

   return new AsyncToolSpecification(tool, (exchange, req) -> {
       String project = require(req.arguments(), "project"); // throws IllegalArgumentException if missing
       // access workspace, do work
       return Mono.just(result("output"));
   });
   ```
4. Use `result(String)` / `error(String)` for returns; `resolveProject` / `resolveFile`
   (containment-checked) for lookups; `parseXml(InputStream)` for any XML.
5. For long operations, wrap in a `WorkspaceJob` (**rule: workspace root** — see Gotchas) and
   bridge to `Mono` via `Mono.create()`; for CPU/IO-bound work, offload with
   `Mono.defer(() -> {...}).subscribeOn(Schedulers.boundedElastic())`.
6. If the tool touches a new Eclipse package, add the bundle to `Require-Bundle` in
   `MANIFEST.MF` — **edit it through Eclipse** so PDE refreshes the dependency classpath.

## Performance goal

**Every tool call should feel instant.**
- Never block the Jetty/MCP thread — offload to a `WorkspaceJob`, `Display.asyncExec`, or
  `boundedElastic`.
- Read only what's needed (`read_lines` must not load the whole file).
- Use Eclipse's indexed `TextSearchEngine` for content search.
- Keep startup cost zero — no preloading or caching on startup.

## Gotchas / lessons

Durable findings (verified against PDSOE 12.8 on a large production ABL project) — keep in mind when touching the code:

- **xref `<Source>` uses attribute `File-name`, not `File`** (`getAttribute("File")` is empty).
- **xref `<Line-num>` is a child element, not an attribute** — read via
  `ref.getElementsByTagName("Line-num").item(0).getTextContent()`.
- **Build jobs must use the workspace-root scheduling rule**, not a project rule — the ABL
  builder acquires the root rule (`R/`), which cannot nest inside a narrower `P/<project>` rule.
  Use `ResourcesPlugin.getWorkspace().getRoot()` as the `WorkspaceJob` rule.
- **PDSOE's ABL editor input is `IFileEditorInput`, not `IAdaptable`** — `getAdapter(IFile.class)`
  returns null. Resolve via `ToolSupport.resolveEditorFile` (`instanceof IFileEditorInput`).
- **`IFileEditorInput` lives in the `org.eclipse.ui.ide` bundle** (split package); it must be in
  `Require-Bundle`.
- **proparse propath is comma-delimited.** `ProparseSettings` splits on `,`; `readPropath` joins
  on the OS `pathSeparator` (`;` on Windows). `buildParseSession` converts `;`→`,`, else includes
  in other propath roots fail to resolve.
- **Some `.xref.xml` files contain raw control chars (U+0001–U+0004)** that XML 1.0 forbids;
  `parseXml` strips illegal control bytes (a no-op on clean XML) so those files still parse.
- **`parseXml` must buffer its source stream before the illegal-byte filter.** An unbuffered
  `FileInputStream` behind the `FilterInputStream` meant one syscall *per byte* (the strip
  filter's bulk read looped the single-byte `read()`), making `build_symbol_index` take ~15 min
  on a large project. `parseXml` now wraps the stream in a `BufferedInputStream` and the strip filter's
  bulk read works block-at-a-time — keep both when touching that code.
- **Unified-diff hunk length comes from `origCount`** in `@@ -start,origCount @@`, not the count
  of `-` lines.

## Runtime lifecycle

**Shutdown:** `McpServerManager.stop(boolean frameworkStopping)` is called by `Activator.stop()`,
which computes the flag from the system bundle's state
(`context.getBundle(0).getState() == Bundle.STOPPING`). On **full IDE/framework shutdown** the
manager no-ops (drops refs, returns) — the OS frees the socket and threads anyway, and any
cleanup work (`closeGracefully().block()` / `jetty.stop()`) would lazily load reactor/jetty
classes from bundle JARs already removed during teardown, producing ~85 spurious
`FrameworkEvent ERROR` entries (`NoSuchFileException` opening zip). The graceful-close +
`jetty.stop()` path is preserved for **individual bundle stop/update** while the IDE keeps
running (JARs still present, clean port release matters). The MCP SDK offers no reactor-free
shutdown, so skipping the call entirely during teardown is the only fix.

## Design decisions

**Single MCP server, single endpoint** — all tools operate on one domain (the ABL workspace),
so one server at `/mcp` is simpler than splitting into multiple logical servers. The set has
grown to ~52 tools without navigation problems; revisit only if it grows much larger.

**No authorization token** — binds to `127.0.0.1` (loopback only), so it's not network-reachable.
A token would add `claude mcp add` friction with no security benefit on a single-user machine.
**If the bind host is ever changed to `0.0.0.0`, a token becomes mandatory** — the file
read/write/build tools would otherwise be a remote primitive.

**Settings hardcoded for now** — port is `Integer.getInteger("pdsoe.mcp.port", 8123)`. Full
Eclipse Preferences (a `FieldEditorPreferencePage` + `AbstractPreferenceInitializer`) is the
long-term plan for port/bind-address (see Roadmap).

### Symbol graph

- **Backing store:** xref-xml. xref schema 0005 captures `RUN`, `INVOKE`,
  `PROCEDURE`/`PRIVATE-PROCEDURE` declarations, `INCLUDE`, table `ACCESS`/`UPDATE`/`SEARCH`,
  class `NEW`/`DELETE-INSTANCE`, `INHERITS`/`IMPLEMENTS`. Internal procedure call sites are
  **not** in xref (declarations only) — a proparse fallback for `get_callees` is deferred.
- **Xref path:** the xref output dir is configured in PDSOE settings, outside the source tree,
  so symbol tools take it as an explicit parameter (`xref_base_path` / `xref_file_path`).
  Extension is `.xref.xml` (double-dot). `get_xref` also accepts absolute paths.
- **Index (`SymbolGraphTools.projectIndices`, per JVM session):** `SymbolIndex` holds forward
  lookups (`byFile`/`bySourceFile` → `XrefRecord`), reverse indexes (`runCallers`,
  `invokeCallers`), and inheritance (`classParent`, `classInterfaces`).
- **Build:** mtime-based incremental (unchanged files skipped; stale reverse entries cleaned
  before re-indexing). Files are parsed **in parallel** and merged serially in walk order
  (so the index is identical to a serial build); progress notifications are emitted when the
  caller supplies a progressToken. Concurrent builds for the *same* project are not supported.
  A large project can have several thousand xref files (multiple MB each). If none are found, the tool tells the user to
  enable XREF-XML in PDSOE (Project Properties → OpenEdge → Compile → Generate XREF-XML).

## Key dependencies (all in `lib/`)

- `mcp-core-1.0.0.jar` — MCP Java SDK core (`io.modelcontextprotocol.*`)
- `mcp-json-jackson3-1.0.0.jar` + `tools-jackson-core/databind-3.0.3.jar` — Tools Jackson 3
  (`tools.jackson.*`), the MCP SDK's JSON mapper
- `json-schema-validator-3.0.0.jar` — required at runtime by `mcp-json-jackson3`
- `jackson-databind/core-2.20.0.jar` + `jackson-annotations-2.20.jar` — Jackson 2
  (`com.fasterxml`), runtime dep of `json-schema-validator`; **must be 2.20+** (2.18 lacked
  `JsonFormat.Shape.POJO` → `NoSuchFieldError` HTTP 500). Our provider schema code uses Jackson 2.
- `reactor-core-3.7.6.jar` + `reactive-streams-1.0.4.jar` — MCP transport
- `slf4j-api-2.0.17.jar` — logging facade for MCP core
- `jetty-*-12.0.14.jar` (server/http/io/util/session/security/xml/util-ajax) — embedded Jetty 12
- `jetty-ee10-servlet-12.0.14.jar` + `servlet-api-6.0.0.jar` — servlet container for
  `HttpServletStreamableServerTransportProvider`
- `proparse-3.8.0.jar` + `rcode-reader-3.8.0.jar` + `antlr4-runtime` (+ guava/gson/kryo/etc.) —
  ABL parser for the Reading-code tools

> Tools Jackson 3 (`tools.jackson.*`) and Jackson 2 (`com.fasterxml.jackson.*`) are different
> JAR sets; both are needed.

## Java version

Java 17 (`.settings/org.eclipse.jdt.core.prefs`, `MANIFEST.MF` `Bundle-RequiredExecutionEnvironment:
JavaSE-17`). Uses text blocks, sealed switch expressions, records, and pattern matching.

## Roadmap

### Release
- [x] Publish to GitHub (https://github.com/xstibo/pdsoe-mcp-server)
- [ ] Cut a `v1.0.0` GitHub Release with the exported plugin JAR attached (export from Eclipse;
  no CLI build) so users can download it without building from source
- [ ] Eclipse Preferences page for user-configurable port / bind address (see Design decisions)

### Performance
- [ ] Offload the remaining synchronous-on-the-Jetty-thread tools to `boundedElastic` (as done
  for `build_symbol_index`): `get_file_outline`, `get_method_source`, `get_method_signature`
  (single-file proparse) and `search_in_files`. Bounded operations, so lower priority.
- [ ] Cache the last-parsed `ParseUnit` per file (keyed by mtime) to skip re-parsing unchanged
  files across repeated outline/method-source calls.
- [ ] Stream `read_file` output in chunks instead of buffering the whole `String`.

### Symbol graph
- [ ] Proparse fallback for `get_callees` — internal procedure call sites (`RUN` without `.p`);
  requires an AST walk.

### Observability
- [ ] Surface server startup/shutdown and tool activity in a dedicated Eclipse Console
  (`MessageConsole` + `IOConsoleOutputStream`, hooked in `Activator.start()`), alongside the
  existing `Platform.getLog()` error entries.
