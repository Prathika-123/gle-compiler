package com.gle.compiler;

import com.gle.error.SyntaxError;
import com.gle.lexer.Token;

import java.util.List;
import java.util.Map;

/** Everything the GLE compiler produces for one source file. Independent of Spring. */
public record CompilationResult(boolean success, int lineCount, int lexicalErrors, List<Token> tokens,
                                Map<String, Integer> tokenStats, List<PhaseResult> phases, List<SyntaxError> errors) {
}
