package com.gle.parser;

import com.gle.lexer.Token;

import java.util.ArrayList;
import java.util.List;

/** Result of Phase 1 for one function declaration or definition. Token indices refer to the shared token list. */
public final class FunctionInfo {
    public String returnType = "";
    public String name = "";
    public Token nameTok;
    public final List<String> params = new ArrayList<>();
    public int line, column, endLine;
    public int headerStart, headerEnd;      // [start, end) includes the closing ')'
    public int openBrace = -1, closeBrace = -1;
    public int bodyStart, bodyEnd;          // [start, end) excludes the outer braces
    public boolean definition;
    public boolean unclosed;

    public String signature() { return (returnType.isEmpty() ? "" : returnType + " ") + name + "(" + String.join(", ", params) + ")"; }
}
