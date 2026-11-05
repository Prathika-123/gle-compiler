package com.gle.compiler;

import com.gle.error.ErrorReporter;
import com.gle.lexer.Lexer;
import com.gle.lexer.Token;
import com.gle.lexer.TokenType;
import com.gle.parser.*;

import java.util.*;

/** Coordinates lexical analysis and the three GLE parsing phases. No dependency on Spring. */
public final class GleCompiler {

    public CompilationResult compile(String raw) {
        String source = raw == null ? "" : raw.replace("\r\n", "\n").replace('\r', '\n');
        ErrorReporter rep = new ErrorReporter();
        Source src = new Source(source);

        List<Token> all = new Lexer(source, rep).tokenize();
        int lexErrors = rep.countPhase(0);

        GlobalParser.Result g = new GlobalParser(all, src, rep).parse();
        LibraryChecker.Result lib = new LibraryChecker(all, rep, g.functions(), g.globalChunks()).check();
        List<String> globalSummary = new ArrayList<>(g.summary());
        globalSummary.addAll(lib.summary());
        LocalParser.Result l = new LocalParser(all, src, rep).parse(g.functions());
        List<StatementChunk> chunks = new ArrayList<>(g.globalChunks());
        chunks.addAll(l.chunks());
        ExpressionLevelParser.Result e = new ExpressionLevelParser(all, src, rep).parse(chunks);
        ReturnChecker.Result ret = new ReturnChecker(all, src, rep).check(g.functions());
        List<String> exprSummary = new ArrayList<>(e.summary());
        exprSummary.addAll(ret.summary());
        ret.nodes().forEach(e.ast()::add);

        List<PhaseResult> phases = List.of(
                phase(1, "Global parsing", "AST-A",
                        "Outer structure: function headers, return types, parameters and body boundaries, plus #include / namespace checks. Bodies are kept as text.", rep, globalSummary, g.ast()),
                phase(2, "Local parsing", "AST-B",
                        "Control structures inside function bodies: if, if-else, for, while, do-while, switch. Headers and bodies are checked structurally.", rep, l.summary(), l.ast()),
                phase(3, "Expression-level parsing", "AST-C",
                        "Declarations, assignments, expressions, function calls, return and I/O statements left over from phases 1 and 2, plus the check that every return matches its function's return type.", rep, exprSummary, e.ast()));

        List<Token> tokens = new ArrayList<>();
        Map<String, Integer> stats = new LinkedHashMap<>();
        for (Token t : all) {
            if (t.type() == TokenType.EOF) continue;
            tokens.add(t);
            stats.merge(t.type().name(), 1, Integer::sum);
        }
        int lines = source.isEmpty() ? 0 : source.split("\n", -1).length;
        return new CompilationResult(rep.count() == 0, lines, lexErrors, tokens, stats, phases, rep.sorted());
    }

    private PhaseResult phase(int n, String name, String ast, String desc, ErrorReporter rep, List<String> summary, com.gle.ast.AstNode tree) {
        int errs = rep.countPhase(n);
        return new PhaseResult(n, name, ast, desc, errs == 0 ? "PASSED" : "FAILED", errs, summary, tree);
    }
}
