package com.gle.lexer;

/** A lexical token. Line and column are 1-based; offset is the index in the source text. */
public record Token(TokenType type, String text, int line, int column, int offset) {

    /** True for keywords, identifiers, operators and punctuation whose text equals s. */
    public boolean is(String s) {
        return (type == TokenType.OPERATOR || type == TokenType.PUNCTUATION
                || type == TokenType.KEYWORD || type == TokenType.IDENTIFIER) && text.equals(s);
    }

    public int endOffset() { return offset + text.length(); }

    public boolean atEof() { return type == TokenType.EOF; }

    /** Text used in error messages. */
    public String display() { return type == TokenType.EOF ? "end of file" : text; }
}
