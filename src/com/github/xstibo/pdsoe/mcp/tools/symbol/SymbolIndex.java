package com.github.xstibo.pdsoe.mcp.tools.symbol;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** In-memory cross-file symbol index for one project, built from its {@code .xref.xml} files. */
class SymbolIndex {
    final Map<String, XrefRecord> byFile = new LinkedHashMap<>();
    final Map<String, XrefRecord> bySourceFile = new LinkedHashMap<>();
    final Map<String, Set<String>> runCallers = new LinkedHashMap<>();
    final Map<String, Set<String>> invokeCallers = new LinkedHashMap<>();
    final Map<String, String> classParent = new LinkedHashMap<>();
    final Map<String, List<String>> classInterfaces = new LinkedHashMap<>();
    /** files that failed to parse, keyed by normalised path -> mtime at failure; skipped silently on incremental runs */
    final Map<String, Long> knownBadFiles = new LinkedHashMap<>();
}
