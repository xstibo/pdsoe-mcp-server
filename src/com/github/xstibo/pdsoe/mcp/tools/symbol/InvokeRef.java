package com.github.xstibo.pdsoe.mcp.tools.symbol;

/** An {@code INVOKE} of a {@code ClassName:Method}, with the line it appears on. */
class InvokeRef {
    final String classMethod;
    final int line;

    InvokeRef(String classMethod, int line) {
        this.classMethod = classMethod;
        this.line = line;
    }
}
