package com.gle.parser;

/**
 * A run of tokens that Phases 1 and 2 deliberately did not look into. Phase 3 parses each chunk as
 * statements or expressions. Range is [start, end) over the token list; "terminated" means it ends with ';'.
 */
public record StatementChunk(Kind kind, int start, int end, boolean terminated, String function, String context, int anchor) {
    public enum Kind { STATEMENT, GLOBAL_DECL, CONDITION, SWITCH_EXPR, CASE_VALUE, FOR_INIT, FOR_COND, FOR_INCR }
}
