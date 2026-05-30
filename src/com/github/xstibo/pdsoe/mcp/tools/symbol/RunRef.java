package com.github.xstibo.pdsoe.mcp.tools.symbol;

/** A {@code RUN} call to an external {@code .p}, with the line it appears on. */
class RunRef {
    final String target;
    final int line;

    RunRef(String target, int line) {
        this.target = target;
        this.line = line;
    }
}
