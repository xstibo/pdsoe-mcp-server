package com.github.xstibo.pdsoe.mcp.tools.symbol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The cross-reference data extracted from one {@code .xref.xml} file. */
class XrefRecord {
    String sourceFile;
    long mtime;
    final List<RunRef> runs = new ArrayList<>();
    final List<InvokeRef> invokes = new ArrayList<>();
    final List<String> procDecls = new ArrayList<>();
    final Map<String, String> inheritance = new LinkedHashMap<>();
}
