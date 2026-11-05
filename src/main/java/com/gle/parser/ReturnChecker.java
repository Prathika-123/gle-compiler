package com.gle.parser;

import com.gle.ast.AstNode;
import com.gle.error.ErrorCategory;
import com.gle.error.ErrorReporter;
import com.gle.error.SyntaxError;
import com.gle.lexer.Token;
import com.gle.lexer.TokenType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Matching-return check (part of Phase 3). For every function definition it compares the declared return
 * type (recorded by Phase 1) with what the body actually does:
 * <ul>
 *   <li>a {@code void} function must not return a value;</li>
 *   <li>a non-void function must not use a bare {@code return;};</li>
 *   <li>an obviously wrong literal is rejected (a string for an int, a number for a string);</li>
 *   <li>a non-void function must return a value on every path (never returning, or only on some paths, is reported).</li>
 * </ul>
 * The check is deliberately conservative so valid code is never flagged: infinite loops, switch, try, throw and
 * exit()/abort() count as "does not fall off the end", lambda bodies are ignored, {@code main} may omit its return
 * (C++ returns 0), and the path analysis is skipped for a function that already has another syntax error.
 */
public final class ReturnChecker {
    public record Result(List<String> summary, List<AstNode> nodes) {}

    private enum Kind { VOID, INTEGRAL, FLOATING, STRING, POINTER, UNKNOWN, AUTO }

    private static final Set<String> EXIT_FUNCS = Set.of("exit", "abort", "_Exit", "quick_exit");

    private final List<Token> t;
    private final Source src;
    private final ErrorReporter rep;

    public ReturnChecker(List<Token> tokens, Source src, ErrorReporter rep) { this.t = tokens; this.src = src; this.rep = rep; }

    private ErrorReporter.Builder err() { return rep.report(3, ErrorCategory.INVALID_RETURN); }

    // ------------------------------------------------------------------ driver

    public Result check(List<FunctionInfo> functions) {
        List<String> summary = new ArrayList<>();
        List<AstNode> nodes = new ArrayList<>();
        for (FunctionInfo f : functions) {
            if (!f.definition || f.returnType.isBlank() || f.unclosed) continue;
            Kind kind = classify(f.returnType);
            if (kind == Kind.AUTO) continue;
            List<Integer> returns = returnsIn(f);
            boolean errorsBefore = hasErrorsIn(f);
            int found = 0;

            for (int r : returns) found += checkReturn(f, kind, r);

            if (kind != Kind.VOID && !f.name.equals("main") && !errorsBefore && f.closeBrace >= 0 && !seqTerminates(f.bodyStart, f.bodyEnd)) {
                found += returns.isEmpty() ? reportNeverReturns(f, kind) : reportNotAllPaths(f, kind, t.get(returns.get(0)).line());
            }
            record(f, kind, returns.size(), found, summary, nodes);
        }
        return new Result(summary, nodes);
    }

    private void record(FunctionInfo f, Kind kind, int returns, int errors, List<String> summary, List<AstNode> nodes) {
        String what = kind == Kind.VOID ? "returns nothing" : "returns " + f.returnType;
        String res = errors == 0 ? "OK" : errors + (errors == 1 ? " problem" : " problems");
        summary.add("Return check (line " + f.line + "): " + f.signature() + " " + what + " — " + returns + " return statement" + (returns == 1 ? "" : "s") + ", " + res);
        AstNode n = new AstNode("RETURN_CHECK", f.signature(), f.line, f.column).endLine(f.endLine);
        n.attr("declaredReturnType", f.returnType).attr("returnStatements", String.valueOf(returns)).attr("result", res);
        nodes.add(n);
    }

    /** True if any error (any phase) is already reported inside this function: its structure may not be what it looks like. */
    private boolean hasErrorsIn(FunctionInfo f) {
        for (SyntaxError e : rep.sorted()) if (e.line() >= f.line && e.line() <= f.endLine) return true;
        return false;
    }

    // ------------------------------------------------------------------ return type

    private static Kind classify(String rt) {
        List<String> w = new ArrayList<>();
        for (String s : rt.split("[^A-Za-z0-9_:]+")) if (!s.isEmpty()) w.add(s);
        if (w.contains("auto")) return Kind.AUTO;
        if (rt.contains("*")) return Kind.POINTER;
        if (w.contains("void")) return Kind.VOID;
        if (w.contains("float") || w.contains("double")) return Kind.FLOATING;
        for (String s : List.of("int", "long", "short", "char", "bool", "unsigned", "signed")) if (w.contains(s)) return Kind.INTEGRAL;
        if (w.contains("string") || w.contains("std::string")) return Kind.STRING;
        return Kind.UNKNOWN;
    }

    private static String example(Kind k) {
        return switch (k) {
            case INTEGRAL -> "return 0;";
            case FLOATING -> "return 0.0;";
            case STRING -> "return \"\";";
            case POINTER -> "return nullptr;";
            default -> "return <value>;";
        };
    }

    // ------------------------------------------------------------------ individual return statements

    /** Indices of the 'return' keywords in the function body, ignoring lambda bodies. */
    private List<Integer> returnsIn(FunctionInfo f) {
        List<Integer> out = new ArrayList<>();
        for (int i = f.bodyStart; i < f.bodyEnd; i++) {
            Token x = t.get(i);
            if (x.is("{") && isLambdaBody(i)) { i = Math.max(i, Keywords.skipBraces(t, i, f.bodyEnd) - 1); continue; }
            if (x.type() == TokenType.KEYWORD && x.is("return")) out.add(i);
        }
        return out;
    }

    private boolean isLambdaBody(int brace) {
        if (brace == 0) return false;
        Token p = t.get(brace - 1);
        if (p.is("]")) return true;
        if (!p.is(")")) return false;
        int depth = 0;
        for (int q = brace - 1; q >= 0; q--) {
            if (t.get(q).is(")")) depth++;
            else if (t.get(q).is("(") && --depth == 0) return q > 0 && t.get(q - 1).is("]");
        }
        return false;
    }

    /** End (exclusive) of the value of the return at index r: stops at ';', at a closing bracket, or at a new statement on a later line. */
    private int valueEnd(int r, int limit) {
        int depth = 0, j = r + 1;
        for (; j < limit; j++) {
            Token x = t.get(j);
            if (x.is("(") || x.is("[") || x.is("{")) depth++;
            else if (x.is(")") || x.is("]") || x.is("}")) { if (depth == 0) break; depth--; }
            else if (depth == 0 && x.is(";")) break;
            else if (depth == 0 && j > r + 1 && (Keywords.isStmtKw(x) || Keywords.isType(x)) && x.line() > t.get(j - 1).line()) break;
        }
        return j;
    }

    /** Checks one return statement; returns how many problems were reported. */
    private int checkReturn(FunctionInfo f, Kind kind, int r) {
        int end = valueEnd(r, f.bodyEnd);
        boolean bare = end == r + 1;
        if (bare && !t.get(end).is(";")) return 0;          // "return }" - a missing ';' that Phase 3 already reports
        if (kind == Kind.VOID) return bare ? 0 : reportVoidReturnsValue(f, r, end);
        if (bare) return reportBareReturn(f, kind, t.get(end));
        return literalMismatch(f, kind, r, end);
    }

    private int literalMismatch(FunctionInfo f, Kind kind, int r, int end) {
        int v = r + 1;
        if (t.get(v).is("-") && end == r + 3) v++;
        else if (end != r + 2) return 0;                      // only single literals are judged: anything else could be valid
        Token lit = t.get(v);
        TokenType ty = lit.type();
        boolean isString = ty == TokenType.STRING_LITERAL;
        boolean isNumLike = ty == TokenType.INT_LITERAL || ty == TokenType.FLOAT_LITERAL || ty == TokenType.CHAR_LITERAL
                || (ty == TokenType.KEYWORD && (lit.is("true") || lit.is("false")));
        if ((kind == Kind.INTEGRAL || kind == Kind.FLOATING) && isString) {
            return mismatch(f, lit, "the string literal " + lit.text(),
                    "Return a number (for example 'return 0;'), or change the return type of '" + f.name + "' to 'string' (or 'const char*') if it should return text.");
        }
        if (kind == Kind.STRING && isNumLike) {
            String what = ty == TokenType.CHAR_LITERAL ? "the character " + lit.text() : ty == TokenType.KEYWORD ? "the bool value " + lit.text() : "the number " + lit.text();
            return mismatch(f, lit, what,
                    "Return text in double quotes (for example 'return \"" + lit.text().replace("\"", "") + "\";' or std::to_string(...)), or change the return type of '" + f.name + "' to a numeric type.");
        }
        return 0;
    }

    private int mismatch(FunctionInfo f, Token lit, String what, String suggestion) {
        err().title("Returned value does not match return type '" + f.returnType + "'").at(lit).unexpected(lit)
                .expected("a value of type '" + f.returnType + "'").related(f.line)
                .explain("'" + f.name + "' is declared to return '" + f.returnType + "' (line " + f.line + "), but this statement returns " + what + ".")
                .suggest(suggestion).state("return check: value type vs. declared return type of '" + f.name + "'").commit();
        return 1;
    }

    private int reportVoidReturnsValue(FunctionInfo f, int r, int end) {
        Token first = t.get(r + 1);
        String value = Source.shorten(src.slice(first, t.get(end - 1)), 40);
        err().title("Void function '" + f.name + "' returns a value").at(first).unexpected(first)
                .expected("';' (a void function returns nothing)").related(f.line)
                .explain("'" + f.name + "' is declared 'void' (line " + f.line + "), so it cannot give back a value, but this statement returns '" + value + "'.")
                .suggest("Write just 'return;' (or leave the return out), or change the return type of '" + f.name + "' from 'void' to the type of the value, for example 'int'.")
                .state("return check: inside void function '" + f.name + "'").commit();
        return 1;
    }

    private int reportBareReturn(FunctionInfo f, Kind kind, Token semi) {
        err().title("'return' without a value in function '" + f.name + "'").at(semi).unexpected(semi)
                .expected("a value of type '" + f.returnType + "'").related(f.line)
                .explain("'" + f.name + "' is declared to return '" + f.returnType + "' (line " + f.line + "), but 'return;' gives back nothing.")
                .suggest("Write 'return <value>;' with a " + f.returnType + " value, for example '" + example(kind) + "', or change the return type of '" + f.name + "' to 'void'.")
                .state("return check: inside function '" + f.name + "' returning " + f.returnType).commit();
        return 1;
    }

    // ------------------------------------------------------------------ every path returns

    private int reportNeverReturns(FunctionInfo f, Kind kind) {
        Token close = t.get(f.closeBrace);
        err().title("Function '" + f.name + "' never returns a value").at(close).unexpected(close)
                .expected("a 'return' statement before '}'").related(f.line)
                .explain("'" + f.name + "' is declared to return '" + f.returnType + "' (line " + f.line + "), but its body contains no return statement, so the caller would receive no value.")
                .suggest("Add '" + example(kind) + "' before the closing '}', or change the return type of '" + f.name + "' to 'void' if it should not return anything.")
                .state("return check: end of function '" + f.name + "'").commit();
        return 1;
    }

    private int reportNotAllPaths(FunctionInfo f, Kind kind, int firstReturnLine) {
        Token close = t.get(f.closeBrace);
        err().title("Not every path in function '" + f.name + "' returns a value").at(close).unexpected(close)
                .expected("a 'return' on every path").related(firstReturnLine)
                .explain("'" + f.name + "' returns '" + f.returnType + "' on some paths (for example the return on line " + firstReturnLine
                        + "), but execution can reach the closing '}' without returning, for instance when an 'if' condition is false.")
                .suggest("Add a final '" + example(kind) + "' before the closing '}', or an 'else' branch that also returns.")
                .state("return check: end of function '" + f.name + "'").commit();
        return 1;
    }

    // ------------------------------------------------------------------ path analysis (token based, conservative)

    /** True if control can never fall off the end of the statements in [s, e). */
    private boolean seqTerminates(int s, int e) {
        int i = s;
        while (i < e) {
            int end = stmtEnd(i, e);
            if (stmtTerminates(i, end)) return true;
            i = Math.max(end, i + 1);
        }
        return false;
    }

    private boolean stmtTerminates(int i, int end) {
        Token x = t.get(i);
        if (x.is("return") || x.is("throw")) return true;
        if (isExitCall(i)) return true;
        if (x.is("{")) return seqTerminates(i + 1, t.get(end - 1).is("}") ? end - 1 : end);
        if (x.is("switch") || x.is("try")) return true;            // conservative: never produces a false error
        if (x.is("if")) {
            int j = afterParen(i + 1, end);
            if (j < 0 || j >= end) return false;
            int e1 = stmtEnd(j, end);
            return e1 < end && t.get(e1).is("else") && seqTerminates(j, e1) && seqTerminates(e1 + 1, end);
        }
        if (x.is("while")) {
            int j = afterParen(i + 1, end);
            return j > 0 && isAlwaysTrue(i + 2, j - 1);
        }
        if (x.is("for")) {
            int j = afterParen(i + 1, end);
            return j > 0 && forIsInfinite(i + 2, j - 1);
        }
        if (x.is("do")) {
            int b = stmtEnd(i + 1, end);
            if (b < end && t.get(b).is("while")) {
                int j = afterParen(b + 1, end);
                return j > 0 && isAlwaysTrue(b + 2, j - 1);
            }
        }
        return false;
    }

    private boolean isExitCall(int i) {
        Token x = t.get(i);
        if (x.is("std") && i + 3 < t.size() && t.get(i + 1).is("::")) return EXIT_FUNCS.contains(t.get(i + 2).text()) && t.get(i + 3).is("(");
        return x.type() == TokenType.IDENTIFIER && EXIT_FUNCS.contains(x.text()) && i + 1 < t.size() && t.get(i + 1).is("(");
    }

    private boolean isAlwaysTrue(int a, int b) {
        if (b - a != 1) return false;
        Token x = t.get(a);
        return x.is("true") || (x.type() == TokenType.INT_LITERAL && !x.text().equals("0"));
    }

    private boolean forIsInfinite(int a, int b) {
        int first = -1, second = -1, depth = 0;
        for (int k = a; k < b; k++) {
            Token x = t.get(k);
            if (x.is("(") || x.is("[")) depth++;
            else if (x.is(")") || x.is("]")) depth--;
            else if (depth == 0 && x.is(";")) { if (first < 0) first = k; else { second = k; break; } }
        }
        if (first < 0 || second < 0) return false;
        return second == first + 1 || isAlwaysTrue(first + 1, second);
    }

    /** Index just after the ')' matching the '(' at k, or -1. */
    private int afterParen(int k, int limit) {
        if (k >= limit || !t.get(k).is("(")) return -1;
        int d = 0;
        for (int j = k; j < limit; j++) {
            if (t.get(j).is("(")) d++;
            else if (t.get(j).is(")") && --d == 0) return j + 1;
        }
        return -1;
    }

    /** End (exclusive) of the statement starting at i, never beyond lim and always past i. */
    private int stmtEnd(int i, int lim) {
        if (i >= lim) return lim;
        Token x = t.get(i);
        if (x.is(";")) return i + 1;
        if (x.is("{")) return Math.max(i + 1, Math.min(Keywords.skipBraces(t, i, lim), lim));
        if (x.is("if")) {
            int j = afterParen(i + 1, lim);
            if (j < 0 || j >= lim) return simpleEnd(i, lim);
            int e1 = stmtEnd(j, lim);
            if (e1 < lim && t.get(e1).is("else")) return stmtEnd(e1 + 1, lim);
            return e1;
        }
        if (x.is("for") || x.is("while") || x.is("switch")) {
            int j = afterParen(i + 1, lim);
            if (j < 0 || j >= lim) return simpleEnd(i, lim);
            return stmtEnd(j, lim);
        }
        if (x.is("do")) {
            int b = stmtEnd(i + 1, lim);
            if (b < lim && t.get(b).is("while")) {
                int j = afterParen(b + 1, lim);
                if (j > 0) return j < lim && t.get(j).is(";") ? j + 1 : j;
            }
            return Math.max(b, i + 1);
        }
        if (x.is("try")) {
            int e = stmtEnd(i + 1, lim);
            while (e < lim && t.get(e).is("catch")) {
                int j = afterParen(e + 1, lim);
                if (j < 0 || j >= lim) break;
                e = stmtEnd(j, lim);
            }
            return Math.max(e, i + 1);
        }
        if (x.is("case") || x.is("default")) {
            int j = i + 1;
            while (j < lim && !t.get(j).is(":")) j++;
            return Math.min(j + 1, lim);
        }
        return simpleEnd(i, lim);
    }

    private int simpleEnd(int i, int lim) {
        int depth = 0, j = i;
        for (; j < lim; j++) {
            Token x = t.get(j);
            if (x.is("(") || x.is("[")) depth++;
            else if (x.is(")") || x.is("]")) depth--;
            else if (x.is("{") && j > i && t.get(j - 1).is("=")) { j = Math.max(j, Keywords.skipBraces(t, j, lim) - 1); }
            else if (depth <= 0 && x.is(";")) return j + 1;
            else if (x.is("{") || x.is("}")) break;
        }
        return Math.max(j, i + 1);
    }
}
