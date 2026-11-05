package com.gle.lexer;

import com.gle.error.ErrorCategory;
import com.gle.error.ErrorReporter;

import java.util.*;

/** Hand-written lexical analyzer for a C/C++-like language. */
public final class Lexer {
    private static final Set<String> KEYWORDS = Set.of(
            "int", "float", "double", "char", "void", "bool", "long", "short", "unsigned", "signed",
            "const", "static", "auto", "if", "else", "for", "while", "do", "switch", "case", "default",
            "break", "continue", "return", "struct", "class", "enum", "typedef", "using", "namespace",
            "true", "false");
    private static final String[] OPS3 = {"<<=", ">>=", "..."};
    private static final String[] OPS2 = {"->", "++", "--", "<<", ">>", "<=", ">=", "==", "!=", "&&", "||",
            "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "::"};
    private static final String OPS1 = "+-*/%=<>!&|^~?.";
    private static final String PUNCT = "(){}[];,:";

    private final String src;
    private final ErrorReporter rep;
    private final List<Token> out = new ArrayList<>();
    private int pos = 0, line = 1, col = 1;
    private boolean lineStart = true;

    public Lexer(String src, ErrorReporter rep) { this.src = src == null ? "" : src; this.rep = rep; }

    public List<Token> tokenize() {
        int n = src.length();
        while (pos < n) {
            char c = src.charAt(pos);
            if (c == '\n') { pos++; line++; col = 1; lineStart = true; continue; }
            if (c == ' ' || c == '\t' || c == '\f') { pos++; col++; continue; }
            if (c == '/' && peek(1) == '/') { while (pos < n && src.charAt(pos) != '\n') { pos++; col++; } continue; }
            if (c == '/' && peek(1) == '*') { skipBlockComment(); continue; }
            if (c == '#' && lineStart) { readPreprocessor(); continue; }
            lineStart = false;
            if (Character.isLetter(c) || c == '_') readWord();
            else if (Character.isDigit(c) || (c == '.' && Character.isDigit(peek(1)))) readNumber();
            else if (c == '"') readQuoted('"', TokenType.STRING_LITERAL, "string");
            else if (c == '\'') readQuoted('\'', TokenType.CHAR_LITERAL, "character");
            else readSymbol();
        }
        if (out.isEmpty()) {
            out.add(new Token(TokenType.EOF, "", 1, 1, n));
        } else {
            // EOF is placed right after the last real token so "end of input" errors point at real code
            Token last = out.get(out.size() - 1);
            out.add(new Token(TokenType.EOF, "", last.line(), last.column() + last.text().length(), n));
        }
        return out;
    }

    private char peek(int k) { return pos + k < src.length() ? src.charAt(pos + k) : '\0'; }
    private void adv() { pos++; col++; }
    private void emit(TokenType t, String text, int l, int c, int off) { out.add(new Token(t, text, l, c, off)); }

    private void skipBlockComment() {
        int sl = line, sc = col;
        pos += 2; col += 2;
        while (pos < src.length() && !(src.charAt(pos) == '*' && peek(1) == '/')) {
            if (src.charAt(pos) == '\n') { line++; col = 1; pos++; } else adv();
        }
        if (pos >= src.length()) {
            rep.report(0, ErrorCategory.LEXICAL_ERROR).title("Unterminated comment")
                    .at(new Token(TokenType.INVALID, "/*", sl, sc, 0)).unexpected("/*").expected("'*/'")
                    .explain("A comment that starts with '/*' on line " + sl + " is never closed, so the rest of the file is treated as a comment.")
                    .suggest("Add '*/' where the comment should end.").state("lexical analysis: inside block comment").commit();
        } else { pos += 2; col += 2; }
    }

    private void readPreprocessor() {
        int start = pos, sc = col;
        while (pos < src.length() && src.charAt(pos) != '\n') adv();
        emit(TokenType.PREPROCESSOR, src.substring(start, pos).trim(), line, sc, start);
        lineStart = false;
    }

    private void readWord() {
        int start = pos, sc = col;
        while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) adv();
        String w = src.substring(start, pos);
        emit(KEYWORDS.contains(w) ? TokenType.KEYWORD : TokenType.IDENTIFIER, w, line, sc, start);
    }

    private void readNumber() {
        int start = pos, sc = col;
        while (pos < src.length()) {
            char ch = src.charAt(pos);
            if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '.') adv();
            else if ((ch == '+' || ch == '-') && pos > start) {
                char p = src.charAt(pos - 1);
                boolean hex = src.startsWith("0x", start) || src.startsWith("0X", start);
                if ((p == 'e' || p == 'E') && !hex) adv(); else break;
            } else break;
        }
        String text = src.substring(start, pos);
        if (text.matches("0[xX][0-9a-fA-F]+[uUlL]*") || text.matches("\\d+[uUlL]*")) {
            emit(TokenType.INT_LITERAL, text, line, sc, start);
        } else if (text.matches("(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?[fFlL]?")) {
            emit(TokenType.FLOAT_LITERAL, text, line, sc, start);
        } else {
            Token bad = new Token(TokenType.INVALID, text, line, sc, start);
            out.add(bad);
            boolean digitFirst = Character.isDigit(text.charAt(0));
            rep.report(0, ErrorCategory.LEXICAL_ERROR).title("Invalid identifier or number").at(bad).unexpected(text)
                    .expected(digitFirst ? "a number, or a name that starts with a letter or '_'" : "a valid number")
                    .explain(digitFirst ? "'" + text + "' starts with a digit but contains letters, so it is neither a number nor a valid name. Names cannot start with a digit."
                            : "'" + text + "' is not a valid number.")
                    .suggest(digitFirst ? "Rename it so it starts with a letter or '_' (for example 'x5'), or separate the number from the name with an operator."
                            : "Check the digits and the decimal point.")
                    .state("lexical analysis: number").commit();
        }
    }

    private void readQuoted(char q, TokenType type, String name) {
        int start = pos, sl = line, sc = col;
        adv();
        boolean closed = false;
        while (pos < src.length()) {
            char ch = src.charAt(pos);
            if (ch == '\n') break;
            if (ch == '\\' && pos + 1 < src.length() && src.charAt(pos + 1) != '\n') { adv(); adv(); continue; }
            adv();
            if (ch == q) { closed = true; break; }
        }
        String text = src.substring(start, pos);
        if (closed) {
            emit(type, text, sl, sc, start);
        } else {
            Token bad = new Token(TokenType.INVALID, text, sl, sc, start);
            out.add(bad);
            rep.report(0, ErrorCategory.LEXICAL_ERROR).title("Unterminated " + name + " literal").at(bad).unexpected("end of line")
                    .expected("closing " + q)
                    .explain("The " + name + " that starts on line " + sl + " has no closing " + q + " before the line ends.")
                    .suggest("Add " + q + " at the end of the " + name + ".").state("lexical analysis: inside " + name + " literal").commit();
        }
    }

    private void readSymbol() {
        int sc = col, start = pos;
        for (String op : OPS3) if (src.startsWith(op, pos)) { pos += 3; col += 3; emit(TokenType.OPERATOR, op, line, sc, start); return; }
        for (String op : OPS2) if (src.startsWith(op, pos)) { pos += 2; col += 2; emit(TokenType.OPERATOR, op, line, sc, start); return; }
        char c = src.charAt(pos);
        adv();
        if (OPS1.indexOf(c) >= 0) emit(TokenType.OPERATOR, String.valueOf(c), line, sc, start);
        else if (PUNCT.indexOf(c) >= 0) emit(TokenType.PUNCTUATION, String.valueOf(c), line, sc, start);
        else {
            Token bad = new Token(TokenType.INVALID, String.valueOf(c), line, sc, start);
            out.add(bad);
            rep.report(0, ErrorCategory.LEXICAL_ERROR).title("Unexpected character").at(bad).unexpected(String.valueOf(c))
                    .expected("a valid C/C++ token")
                    .explain("The character '" + c + "' is not part of the C/C++ language outside strings and comments.")
                    .suggest("Remove the character, or put it inside a string literal.").state("lexical analysis").commit();
        }
    }
}
