package com.gle.compiler;

import com.gle.ast.AstNode;

import java.util.List;

/** Outcome of one GLE phase: status, human-readable summary lines and the AST it produced. */
public record PhaseResult(int phase, String name, String astName, String description, String status,
                          int errorCount, List<String> summary, AstNode ast) {
}
