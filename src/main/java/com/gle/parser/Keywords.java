package com.gle.parser;

import com.gle.lexer.Token;
import com.gle.lexer.TokenType;

import java.util.List;
import java.util.Set;

final class Keywords {
    private Keywords() {}

    static final Set<String> TYPES = Set.of("int", "float", "double", "char", "void", "bool", "long", "short",
            "unsigned", "signed", "const", "static", "auto");
    static final Set<String> STATEMENT_KEYWORDS = Set.of("if", "else", "for", "while", "do", "switch", "case",
            "default", "break", "continue", "return");

    static boolean isType(Token t) { return t.type() == TokenType.KEYWORD && TYPES.contains(t.text()); }
    static boolean isStmtKw(Token t) { return t.type() == TokenType.KEYWORD && STATEMENT_KEYWORDS.contains(t.text()); }

    /** Index just after the '}' matching the '{' at index i (or limit if unmatched). */
    static int skipBraces(List<Token> t, int i, int limit) {
        int depth = 0;
        for (; i < limit; i++) {
            if (t.get(i).is("{")) depth++;
            else if (t.get(i).is("}") && --depth == 0) return i + 1;
        }
        return limit;
    }
}
