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

54 MCP tools, split by domain into `ToolProvider` classes (see Architecture; the original
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

Two top-level classes in `src/com/github/xstibo/pdsoe/mcp/`, plus `tools` and `preferences`
subpackages:

| File | Role |
|---|---|
| `Activator.java` | `AbstractUIPlugin` (+ `IStartup`) — entry point. Eclipse calls `start()` at IDE startup (registered in `plugin.xml` as an `org.eclipse.ui.startup` extension). Creates `McpServerManager` and starts it **only if** the `server.enabled` preference is true. Extends `AbstractUIPlugin` (not plain `Plugin`) so the preference page gets an `IPreferenceStore` via `getPreferenceStore()`. Exposes `isServerRunning()` and `applyServerConfiguration()` for the preference page. |
| `McpServerManager.java` | Wires Jetty 12 + MCP Java SDK. Binds `127.0.0.1` on the configured port (read from preferences at start), streamable HTTP at `/mcp`. Aggregates every `ToolProvider`'s tools (flat-map over a `List<ToolProvider>`), **filters out any tool whose name is in the `tools.disabled` preference**, and registers the rest in one `.tools(...)` call; logs the registered count. Also exposes a static `toolsByDomain()` (returning `LinkedHashMap<String, List<ToolInfo>>`) that the preference page uses to populate its filtering tree from the same `PROVIDERS` list. `start()` is log-only (won't break the bundle at IDE startup); `startServer()` throws so a bind failure can be surfaced; `applyConfiguration()` reconciles the running server to the saved prefs (tracked via `boundPort`/`boundDisabledTools`): a **disabled-tool-set change is applied live** (no restart) via `reconcileTools()` which calls the SDK's `addTool`/`removeTool` + `notifyToolsListChanged` on the running `McpAsyncServer`; only a **port change** (or enable->disable) restarts/stops. Restart-time `stop()` uses a **hard Jetty stop** (`setStopTimeout(0)`) so the socket releases deterministically instead of waiting on a connected client's stream to drain (that wait always timed out, logging "Error stopping Jetty server"). |

**Tool providers** — `src/com/github/xstibo/pdsoe/mcp/tools/`:

| File | Role |
|---|---|
| `ToolProvider.java` | Interface: `List<AsyncToolSpecification> tools()` plus `String domain()` (a human-readable label used to group the provider's tools in the preference page's tool-filtering tree). One implementation per domain. |
| `ToolSupport.java` | Stateless shared helpers (statically imported): `result`/`error`/`param`/`require`, `resolveProject`/`resolveFile`/`requireContainedPath`/`resolveEditorFile`, `readPropath`, small-file IO, marker collection, a JSON-schema DSL (`tool(...)`, `str()`/`bool()`/`integer()`), the hardened XXE-safe `parseXml`, and shared constants (`MAX_READ_BYTES`, `MAX_SEARCH_MATCHES`, `ABL_BUILDER_ID`). |
| `WorkspaceTools` (18), `ReadingTools` (6), `EditorStateTools` (7), `DiagnosticsTools` (7), `EditingTools` (7), `FileHistoryTools` (3), `SvnTools` (1) | The 54 tools grouped by the categories below; each calls Eclipse workspace APIs (`IProject`, `IFile`, `IMarker`, `WorkspaceJob`). `SvnTools` is the exception: it shells out to the `svn` CLI (see below). |
| `tools/symbol/` | `SymbolGraphTools` (5 tools) plus its package-private data types `RunRef`/`InvokeRef`/`XrefRecord`/`SymbolIndex`. Owns the per-project in-memory index (`SymbolGraphTools.projectIndices`, a `ConcurrentHashMap`). |

**Preferences** — `src/com/github/xstibo/pdsoe/mcp/preferences/`:

| File | Role |
|---|---|
| `PreferenceConstants.java` | Store keys: `server.enabled`, `server.port`, `tools.disabled` (comma-separated tool names to skip registering; storing the *disabled* set means new tools default to enabled), `svn.executable` (full path to the svn CLI; `""` = auto-detect). |
| `PreferenceInitializer.java` | `AbstractPreferenceInitializer` (registered via `org.eclipse.core.runtime.preferences`). Seeds defaults: `enabled=true`, `port=Integer.getInteger("pdsoe.mcp.port", 8123)` (so the old `-D` system property still sets the *default*), `tools.disabled=""` (everything enabled), `svn.executable=""` (auto-detect). |
| `McpPreferencePage.java` | `PreferencePage` (registered via `org.eclipse.ui.preferencePages`, "PDSOE MCP Server"). Hand-built layout: a "Server Configuration" group (Enable checkbox + Port field, manual range validation 1024-65535) a "Server Status" group (read-only status label), a "Version Control" group (svn-executable path field + Browse button; persisted to `svn.executable`, read by `SvnTools` at call time so no server reconcile is needed), and a "Tools" group (a `CheckboxTreeViewer` of domains -> tools for filtering). `performOk()` persists the fields then reconciles the server on a background `Job` (off the UI thread — graceful close can block ~10s) and reports a bind failure (e.g. port in use) via the status line + an error dialog (parented to the workbench window shell so it survives Apply-and-Close). Apply / Apply-and-Close both route through `performOk()`. Plain `PreferencePage` (not `FieldEditorPreferencePage`) because field editors fight over the shared parent's grid layout, which precludes clean section groups. The tools tree uses an `ICheckStateProvider` driven by a working copy of the disabled-tool set (not the widget's own check state), so domain tristate (gray = partially enabled) stays correct even for collapsed/never-realized nodes. |

**Plugin wiring**: `plugin.xml` registers `Activator` as a startup extension, the preference
page (`org.eclipse.ui.preferencePages`), and the preference initializer
(`org.eclipse.core.runtime.preferences`). `MANIFEST.MF`
declares OSGi `Require-Bundle` deps (`org.eclipse.core.runtime`, `core.resources`,
`debug.core`, `ui`, `ui.ide`, `swt`, `jface.text`, `ui.workbench.texteditor`, `search`,
`ui.console`) and lists every JAR in `lib/` on the bundle classpath. Note: `org.eclipse.ui.ide`
is required for `IFileEditorInput` (a split package — the interface ships in `ui.ide`, not
`ui.workbench`).

## MCP tools

**The full per-tool list lives in `README.md` (Available tools) - that is the single source
of truth; update it when tools change.** The eight domains, their provider class, and the
Eclipse API each is built on:

| Domain | Provider (count) | Built on |
|---|---|---|
| Workspace & file navigation | `WorkspaceTools` (18) | `IWorkspace` / `IProject` / `IFile` |
| Reading code | `ReadingTools` (6) | `IFile.getContents()` + proparse parser |
| Symbol graph | `SymbolGraphTools` (5) | xref-xml index (run `build_symbol_index` first) |
| Editor state | `EditorStateTools` (7) | `IWorkbench` / `ITextEditor` / `ITextSelection` |
| Diagnostics & build | `DiagnosticsTools` (7) | `IMarker` + `WorkspaceJob` |
| Code editing | `EditingTools` (7) | `IFile.setContents()` + Eclipse local history |
| File history | `FileHistoryTools` (3) | Eclipse local history |
| Version control (SVN) | `SvnTools` (1) | `svn` CLI (resolved via preference -> PATH -> known locations), run in the project's working copy |

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
  Use `ResourcesPlugin.getWorkspace().getRoot()` as the `WorkspaceJob` rule. This applies to
  `CLEAN_BUILD` too: `clean_project` originally set a `P/<project>` rule and threw
  `Attempted to beginRule: R/, does not match outer scope rule: P/<project>` at runtime
  (fixed in v1.2.3) — any tool that calls `project.build(...)` needs the root rule.
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
- **`search_in_files` takes an optional `path` (project-relative folder/file) to scope the search**
  and reads the search term from `query` *or* `search` (some clients send `search`); the scope root
  is `project.findMember(path)` fed to `TextSearchScope`, defaulting to the whole project (BUG-017).
  A regex needle (`regex: true`) is compiled with `Pattern.MULTILINE` so caller `^`/`$` anchors match
  at line boundaries - `TextSearchEngine` applies the pattern to the whole file buffer, so without
  MULTILINE an anchored regex matched nothing (BUG-021).
- **The outline / method-source / method-signature tools parse *syntactically* (`ParseUnit.parse()`),
  not `treeParser01()`.** treeParser01 does symbol/type resolution, which needs the DB schema; the
  plugin runs with an empty `Schema()`, so a `DEFINE BUFFER lb FOR <db-table>` makes it throw
  `NullPointerException: ...TableBuffer.getTable() ... "ourBuffer" is null` and the whole file
  could not be outlined at all (BUG-020). The syntactic tree is enough:
  `getTopNode().queryStateHead(METHOD, PROCEDURE, FUNCTION, CONSTRUCTOR, DESTRUCTOR)` returns the
  routine heads in source order (`queryMainFile` also re-reports the `END METHOD` keyword node - use
  `queryStateHead`). A routine's **end line is the max `getLine()` over the head node's direct
  children** - the `END`/`PERIOD` terminator tokens carry it; `getEndLine()` on the head returns
  only its opening line, and an earlier `getDefineNode().getNextSibling()` walk ran to end-of-file,
  so `get_method_source` returned the method plus every routine after it (BUG-015). Routine name:
  METHOD carries it right before `PARAMETER_LIST` (after modifiers + return type),
  CONSTRUCTOR/DESTRUCTOR use the class `TYPE_NAME`, PROCEDURE/FUNCTION use the first `ID` child.
- **Edit tools must preserve each file's original EOL, trailing-newline state, and charset.**
  ABL files on Windows are CRLF; `BufferedReader.readLine()` strips terminators, so a naive
  rewrite with `\n` flips every line and SVN/Git report the whole file changed (a real bug hit
  in the field). `ToolSupport.writeAllLines` now calls `detectFormat(file)` (reads the still-
  on-disk content) and re-joins with the original separator, only appending a trailing newline
  if the file had one; `readAllLines`/`writeAllLines`/`replace_in_file`/`write_file` use
  `fileCharset(file)` (the Eclipse `IFile.getCharset()`), not hardcoded UTF-8. `write_file`
  normalizes incoming content to the existing file's EOL via `normalizeLineEndings`. Keep all
  write paths routed through these helpers when touching the editing tools.
- **`write_file` on a *new* file must normalize to the workspace's "new file" line delimiter**, not
  write the client's content verbatim. The MCP client sends LF-joined content, so a brand-new file
  was born with LF even on a CRLF (Windows) workspace - and because every later rewrite preserves
  the file's *existing* EOL, once born LF it stayed LF forever, diffing as fully changed in SVN/Git
  (BUG-019). The new-file branch now runs content through `ToolSupport.newFileLineSeparator(project)`,
  which resolves Eclipse's `line.separator` preference (project scope -> instance scope -> OS
  default) - the same "New text file line delimiter" setting the editors use. The existing-file
  branch was already correct (it preserves the detected on-disk EOL).
- **`replace_in_file` must normalize the *search* string's EOLs too, not just the replacement's.**
  A multi-line search literal arrives LF-joined from the MCP client and silently matched nothing
  against CRLF file content ("Replaced 0 occurrence(s)" despite the text existing). The literal
  path now runs the needle through `normalizeLineEndings(search, sep)` before `Pattern.quote`;
  the literal replacement also goes through `Matcher.quoteReplacement` (a `$`/`\` would
  otherwise be taken as a group reference), the count comes from a real `Matcher` loop, and a
  0-match call skips the `setContents` write entirely. Regex needles are the caller's job
  (`\r?\n`).
- **The ABL incremental builder skips files it considers unchanged, leaving stale state.** Two
  field failures: (1) stale error markers persisted on a file whose only problem had been a
  then-missing dependency that later built clean; (2) a dependent class kept compiling against
  an interface's stale r-code after the interface was edited via the MCP edit tools. `build_file`
  therefore calls `file.touch(monitor)` before the `INCREMENTAL_BUILD` so the requested file is
  always in the build delta and really recompiles (regenerating its r-code). Keep the touch if
  reworking the build tools.
- **OpenEdge surfaces an interface/impl mismatch in a super class only when a subclass is
  compiled.** A base class with the mismatch (e.g. accessor visibility: interface `GET. SET.`
  vs class `PUBLIC GET. PUBLIC SET.`) fails with error 12918 ("Could not compile '<Base>',
  which is a super class") pointing at the subclass's INHERITS line. On older PDSOE the super
  builds clean standalone; on PDSOE 12.8 the super often surfaces its own error
  (e.g. 12942) on the offending member too, so the 12918 is no longer reliably paired with a
  clean super. Not fixable in the plugin; `collectMarkers` appends a diagnostic hint when a
  12918 marker is present, worded to cover both cases (super clean vs super self-erroring).
- **To trigger a native PDSOE/OpenEdge menu action, execute its registered workbench command -
  do not reimplement the behavior.** `rebuild_files_with_errors` runs OpenEdge's own
  "Recompile Files that Have Errors" command (defined in `com.openedge.pdt.text`): on the UI
  thread (`Display.syncExec`) it gets `ICommandService`/`IHandlerService` from
  `PlatformUI.getWorkbench()` and calls `executeCommandInContext(...)`. The project-scoped
  command id is `com.openedge.pdt.text.compilefileswitherror` (handler
  `CompileFilesWithErrorHandler`); a workspace-wide variant exists at
  `com.openedge.pdt.text.compileallfileswitherrors`. The project-scoped command's `visibleWhen`
  needs a selection that iterates `IResource`, so the call builds an `EvaluationContext` with the
  `IProject` as `ISources.ACTIVE_CURRENT_SELECTION_NAME` - without that selection the handler has
  no target. This needs `org.eclipse.core.commands` + `org.eclipse.core.expressions` in
  `Require-Bundle`. (Command ids verified against PDSOE 12.8.11; pass `command_id` to override.)

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

**Live config-apply (preference page Apply):** A change to *only* the disabled-tool set is
reconciled **on the running server without a restart** - `reconcileTools()` diffs the desired
set vs. `boundDisabledTools` and calls the SDK's `addTool`/`removeTool` then
`notifyToolsListChanged`, so the connected client's tool list updates live. A restart is avoided
here because a graceful Jetty stop waits (up to its stop-timeout) for open connections to drain,
and a connected MCP client holds a long-lived streamable-HTTP/SSE stream that never drains in the
window - so the stop *always* timed out (`TimeoutException`, logged as "Error stopping Jetty
server"). The restart path that remains (a **port change**, or enable->disable) therefore uses a
**hard stop**: `stop(false)` sets `jetty.setStopTimeout(0)` (and shortens the MCP
`closeGracefully` block to 2s) so the socket releases immediately and a same-port rebind is
deterministic (`ServerConnector` defaults to `SO_REUSEADDR`).

## Design decisions

**Single MCP server, single endpoint** — all tools operate on one domain (the ABL workspace),
so one server at `/mcp` is simpler than splitting into multiple logical servers. The set has
grown to ~54 tools without navigation problems; revisit only if it grows much larger.

**Loopback-only bind, no authorization token** — binds to `127.0.0.1`, so it's not
network-reachable. A token would add `claude mcp add` friction with no security benefit on a
single-user machine. **The bind address is intentionally fixed to loopback and not made
configurable.** This is a single-user IDE plugin, and the file read/write/build tools would be
an unauthenticated remote primitive if exposed; binding to `0.0.0.0` is never the right answer.
The one legitimate cross-host case (PDSOE in a VM/container/WSL or on a remote workstation,
driven from another machine) is solved with SSH port forwarding
(`ssh -L 8123:127.0.0.1:8123 ...`) — the server stays loopback-only on both ends and inherits
the tunnel's encryption and the remote host's auth. So neither a configurable bind address nor
a bearer token is on the roadmap.

**Settings via Eclipse Preferences** — a plain `PreferencePage` +
`AbstractPreferenceInitializer` under *Window > Preferences > PDSOE MCP Server* exposes an
Enable toggle, the bind port, a read-only status line, a per-tool filter, and the svn-executable
path (see Architecture > Preferences). Changes apply only on **Apply / Apply-and-Close** (`performOk()`),
reconciled on a background `Job` (off the UI thread), surfacing bind failures. A **tool-filter
change is applied live** on the running server (no restart); a **port change** restarts with a
hard stop (see Runtime lifecycle). The old `-Dpdsoe.mcp.port` system property survives as
the *default* port (overridden by a saved preference). The bind address is intentionally NOT a
preference (loopback-only — see above). Per-project/per-path file access control was
considered and **decided against** (see Roadmap): on a loopback single-user server it is a
guard rail, not a security control, so it is not worth the added complexity.

**Version control via the `svn` CLI, not an Eclipse SVN integration** — `SvnTools` shells out to
the `svn` command-line client (run with the Eclipse project's on-disk location as the working
directory) rather than depending on Subversive/Subclipse, whose Java APIs are neither public nor
stable across PDSOE installs. The CLI is the one interface every SVN setup has. The executable is
located by `ToolSupport.resolveSvnExecutable()` in priority order: the `svn.executable` preference
(if it points at a real file) -> `svn` on the `PATH` -> well-known install locations
(`svnProbeLocations()`: TortoiseSVN/SlikSVN/Subversion/CollabNet/VisualSVN on Windows;
`/usr/bin`, `/usr/local/bin`, `/opt/homebrew/bin`, `/opt/local/bin` elsewhere). Probing absolute
locations matters because the IDE inherits the `PATH` from when it started, so a just-installed
`svn` is found without an IDE restart; a common Windows gotcha is **TortoiseSVN installed without
its "command line client tools" component**, so there is no `svn.exe` at all. If nothing resolves,
`svnNotFoundMessage()` lists what was tried and how to fix it (install a CLI, or set the path in
Preferences). The process is drained on a separate thread (so a large diff cannot deadlock on a
full pipe buffer), killed past a 120s timeout, and its output capped at `MAX_READ_BYTES`. Read-only
so far (`svn_diff`); the same shell-out pattern (and the shared resolver) can add `svn status` /
`svn log` later.

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
- [x] Cut a `v1.0.0` GitHub Release with the exported plugin JAR attached
  (https://github.com/xstibo/pdsoe-mcp-server/releases/tag/v1.0.0). No CLI build - the JAR is
  exported from Eclipse (Deployable plug-ins and fragments) and uploaded via `gh release create`.
- [x] Cut a `v1.1.0` GitHub Release (adds the Eclipse Preferences page: enable toggle, port,
  live restart, status line). Same export-from-Eclipse + `gh release create` flow as v1.0.0.
- [x] Cut a `v1.2.0` GitHub Release (adds per-tool filtering via a `CheckboxTreeViewer`,
  applied live with no restart; plus the hard Jetty stop on port-change restart). Same
  export-from-Eclipse + `gh release create` flow as v1.0.0.
- [x] Cut a `v1.2.1` GitHub Release (bugfix: edit tools now preserve each file's line endings,
  trailing-newline state, and Eclipse charset instead of forcing LF/UTF-8 - a forced-LF rewrite
  made SVN/Git report every line changed on CRLF files; see Gotchas). Same export-from-Eclipse +
  `gh release create` flow as v1.0.0.
- [x] Cut a `v1.2.2` GitHub Release (bugfixes, all verified live on PDSOE 12.8):
  (1) `replace_in_file` now normalizes a literal multi-line search's EOLs so it matches CRLF
  files instead of silently replacing 0 (and quotes `$`/`\` in literal replacements, skips the
  write on a 0-match); (2) `build_file` touches the file before the incremental build so it
  always recompiles - clearing stale markers and regenerating r-code a dependent then sees;
  (3) `collectMarkers` appends a hint on error 12918 (super-class compile failure). Same
  export-from-Eclipse + `gh release create` flow as v1.0.0
  (https://github.com/xstibo/pdsoe-mcp-server/releases/tag/v1.2.2).
- [x] Cut a `v1.3.0` GitHub Release (two new tools + a bugfix). New tools: `svn_diff` (the
  `SvnTools` provider, shelling out to the `svn` CLI resolved via preference -> PATH -> known
  locations) and `rebuild_files_with_errors` (runs OpenEdge's own "Recompile Files that Have
  Errors" workbench command). Bugfix: `clean_project` set a project scheduling rule
  (`P/<project>`), but its `CLEAN_BUILD` runs the ABL builder, which acquires the workspace-root
  rule (`R/`) - the narrower project rule cannot nest inside it, so the tool threw
  `Attempted to beginRule: R/, does not match outer scope rule: P/<project>`; now uses
  `ResourcesPlugin.getWorkspace().getRoot()` like the other build tools. Minor bump (not
  1.2.3) because it adds tools, not just fixes. Same export-from-Eclipse + `gh release create`
  flow as v1.0.0 (https://github.com/xstibo/pdsoe-mcp-server/releases/tag/v1.3.0).
- [x] Cut a `v1.3.1` GitHub Release (bugfixes only, no new tools; the first four verified live on
  PDSOE 12.8, the fifth (BUG-021) verified compiled-in, pending a live regex-anchor check):
  (1) `get_method_source` returned the method plus
  everything to end-of-file - the end line now comes from the max line over the routine head's
  direct children (its `END`/`PERIOD` tokens) instead of a next-sibling walk (BUG-015);
  (2) `get_file_outline`/`get_method_signature`/`get_method_source` threw a `NullPointerException`
  (`TableBuffer.getTable()`) on any class with `DEFINE BUFFER ... FOR <db-table>` - the three ABL
  tools now parse syntactically (`ParseUnit.parse()`, no `treeParser01()` symbol resolution)
  (BUG-020); (3) `search_in_files` gained an optional `path` scope and accepts `search` as an alias
  for `query` (BUG-017); (4) `write_file` on a new file now normalizes to the workspace's "new file"
  line delimiter (CRLF on Windows) instead of the client's LF (BUG-019); (5) `search_in_files` regex
  needles now compile with `Pattern.MULTILINE` so `^`/`$` anchors match per line (BUG-021). Patch
  bump (1.3.1) because it is fixes only. Same export-from-Eclipse + `gh release create` flow as
  v1.0.0 (https://github.com/xstibo/pdsoe-mcp-server/releases/tag/v1.3.1).
- [ ] (maybe) Automated release on tag push - a `.github/workflows/release.yml` that fires on
  `v*` tags and cuts the GitHub Release. The blocker is there is **no headless build**: a PDE
  plugin needs a Tycho/Maven build to produce the JAR in CI, which means authoring a `pom.xml`
  (packaging `eclipse-plugin`) + a target platform (`.target` / p2 repos) that has every
  `Require-Bundle` dep at PDSOE-12.8-compatible versions. Hardest parts: (1) reproducing the
  IDE classpath given the embedded `lib/` JARs on `Bundle-ClassPath` and the `ui.ide`
  split-package quirk; (2) JDK 21 in CI (`ui.console` needs JavaSE-21 though our BREE is 17);
  (3) reconciling the git tag <-> Maven version <-> `Bundle-Version: x.y.z.qualifier`. Cheaper
  fallback if Tycho is not worth it: keep exporting the JAR from Eclipse and only automate the
  release-creation/upload step (tag-triggered notes + manual `gh release upload`).

### Settings & configuration
An Eclipse Preferences page (`FieldEditorPreferencePage` + `AbstractPreferenceInitializer`)
under *Window > Preferences > PDSOE MCP Server* exposing plugin settings:
- [x] Enable/disable the server (`server.enabled`, default on) with a live status line
- [x] Server port (`server.port`; default still `Integer.getInteger("pdsoe.mcp.port", 8123)`).
  Changes apply on Apply/Apply-and-Close via a live restart.
- [x] Tool filtering — enable/disable individual tools (or whole domains) via a
  `CheckboxTreeViewer`; disabled tool names are stored in `tools.disabled` and the server skips
  registering them (applied **live on change, no restart** via the SDK's
  `addTool`/`removeTool` + `notifyToolsListChanged`)
- File access control (per-project/per-path allow/deny) — **decided against** for now.
  On a single-user loopback server it is a guard rail, not a security control: project-only
  gating is too blunt to be useful, and path/file-level gating (making `requireContainedPath`
  project-aware plus a glob UI) is a lot of surface area for little practical benefit. Revisit
  only if a concrete need to fence off a specific project or path arises.

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
  (`MessageConsole` + `IOConsoleOutputStream`, hooked in `Activator.start()`), shifting routine
  logging there instead of `Platform.getLog()` (which is meant for errors); a setting would
  control verbosity / whether the console is the primary log target.
