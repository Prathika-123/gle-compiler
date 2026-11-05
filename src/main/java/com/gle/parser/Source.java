package com.gle.parser;

import com.gle.lexer.Token;

/** Source text helper used to quote code fragments in AST nodes and error messages. */
public final class Source {
    private final String text;
    public Source(String text) { this.text = text; }

    /** Source between two tokens (inclusive), with whitespace collapsed. */
    public String slice(Token first, Token last) {
        if (last.offset() < first.offset()) return "";
        return text.substring(first.offset(), Math.min(text.length(), last.endOffset())).replaceAll("\\s+", " ").trim();
    }

    public static String shorten(String s, int max) { return s.length() <= max ? s : s.substring(0, max - 1) + "…"; }
}
