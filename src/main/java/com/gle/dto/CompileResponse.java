package com.gle.dto;

import com.gle.compiler.CompilationResult;
import com.gle.compiler.PhaseResult;
import com.gle.error.SyntaxError;
import com.gle.lexer.Token;

import java.util.List;
import java.util.Map;

/** JSON returned by the compile endpoint. */
public record CompileResponse(
        boolean success,
        String status,
        String message,
        int errorCount,
        int lineCount,
        Lexical lexical,
        List<PhaseResult> phases,
        List<SyntaxError> errors) {

    public record Lexical(int tokenCount, int errorCount, Map<String, Integer> stats, List<Token> tokens) {}

    public static CompileResponse from(CompilationResult r) {
        int n = r.errors().size();
        return new CompileResponse(
                r.success(),
                r.success() ? "SUCCESS" : "SYNTAX_ERRORS",
                r.success() ? "No syntax errors found." : n + (n == 1 ? " syntax error found." : " syntax errors found."),
                n, r.lineCount(),
                new Lexical(r.tokens().size(), r.lexicalErrors(), r.tokenStats(), r.tokens()),
                r.phases(), r.errors());
    }

    public static CompileResponse rejected(String message) {
        return new CompileResponse(false, "REJECTED", message, 0, 0,
                new Lexical(0, 0, Map.of(), List.of()), List.of(), List.of());
    }
}
