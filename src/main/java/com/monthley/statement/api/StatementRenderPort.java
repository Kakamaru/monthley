package com.monthley.statement.api;

/** Penulis penyata. Satu model, banyak format (ADR 0010 keputusan 7). */
public interface StatementRenderPort {

    /** PDF. Templat boleh diedit tanpa recompile. */
    byte[] renderPdf(StatementModel model);
}
