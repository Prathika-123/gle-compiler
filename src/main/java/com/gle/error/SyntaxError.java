package com.gle.error;

/** One reported syntax (or lexical) error. phase 0 = lexical analysis, 1-3 = GLE phases. */
public record SyntaxError(
        String category,
        String title,
        int phase,
        int line,
        int column,
        String unexpectedToken,
        String expected,
        String explanation,
        String suggestion,
        Integer relatedLine,
        String parserState) {
}
