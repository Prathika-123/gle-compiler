package com.gle.ast;

import java.util.*;

/** Generic AST node used by all three GLE phases (AST-A, AST-B, AST-C). */
public class AstNode {
    private final String kind;
    private String label;
    private final int line;
    private final int column;
    private int endLine;
    private String text;
    private final Map<String, String> attributes = new LinkedHashMap<>();
    private final List<AstNode> children = new ArrayList<>();

    public AstNode(String kind, String label, int line, int column) {
        this.kind = kind; this.label = label; this.line = line; this.column = column; this.endLine = line;
    }

    public AstNode add(AstNode child) { children.add(child); return child; }
    public AstNode attr(String k, String v) { attributes.put(k, v); return this; }
    public AstNode label(String l) { this.label = l; return this; }
    public AstNode text(String t) { this.text = t; return this; }
    public AstNode endLine(int l) { this.endLine = Math.max(l, line); return this; }

    public String getKind() { return kind; }
    public String getLabel() { return label; }
    public int getLine() { return line; }
    public int getColumn() { return column; }
    public int getEndLine() { return endLine; }
    public String getText() { return text; }
    public Map<String, String> getAttributes() { return attributes; }
    public List<AstNode> getChildren() { return children; }
}
