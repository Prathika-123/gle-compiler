package com.gle.compiler;

import com.gle.ast.AstNode;
import com.gle.error.SyntaxError;

import java.nio.file.Files;
import java.nio.file.Path;

/** Tiny command-line runner for the engine: java com.gle.compiler.GleCli file.cpp [--ast] */
public final class GleCli {
    public static void main(String[] args) throws Exception {
        String code = Files.readString(Path.of(args[0]));
        boolean ast = args.length > 1 && args[1].equals("--ast");
        CompilationResult r = new GleCompiler().compile(code);
        System.out.println(r.success() ? "OK: no syntax errors" : r.errors().size() + " error(s)");
        for (SyntaxError e : r.errors()) {
            System.out.printf("[P%d %s] %s  @%d:%d  unexpected='%s' expected=%s%n    why: %s%n    fix: %s%s%n", e.phase(), e.category(), e.title(),
                    e.line(), e.column(), e.unexpectedToken(), e.expected(), e.explanation(), e.suggestion(),
                    e.relatedLine() == null ? "" : "  (related line " + e.relatedLine() + ")");
        }
        if (ast) for (PhaseResult p : r.phases()) { System.out.println("== " + p.astName() + " " + p.status()); p.summary().forEach(s -> System.out.println("  " + s)); print(p.ast(), 0); }
    }

    private static void print(AstNode n, int d) {
        System.out.println("  " + "  ".repeat(d) + n.getKind() + " " + n.getLabel() + " @" + n.getLine());
        n.getChildren().forEach(c -> print(c, d + 1));
    }
}
