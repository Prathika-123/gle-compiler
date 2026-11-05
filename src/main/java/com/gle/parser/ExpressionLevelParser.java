package com.gle.parser;

import com.gle.ast.AstNode;
import com.gle.error.ErrorCategory;
import com.gle.error.ErrorReporter;
import com.gle.lexer.Token;
import com.gle.lexer.TokenType;

import java.util.*;

/**
 * Phase 3 - expression-level parsing. Parses every {@link StatementChunk} left by Phases 1 and 2:
 * declarations, assignments, arithmetic / relational / logical expressions, function calls, return
 * statements and I/O statements. Recovery: errors are contained inside the chunk; a missing ';'
 * is reported and parsing continues as if it were present (so one slip yields one message).
 */
public final class ExpressionLevelParser {
    public record Result(AstNode ast, List<String> summary) {}

    private static final class Abort extends RuntimeException {
        Abort() { super(null, null, false, false); }
    }

    private static final Map<String, Integer> PREC = Map.ofEntries(
            Map.entry("||", 1), Map.entry("&&", 2), Map.entry("|", 3), Map.entry("^", 4), Map.entry("&", 5),
            Map.entry("==", 6), Map.entry("!=", 6), Map.entry("<", 7), Map.entry(">", 7), Map.entry("<=", 7), Map.entry(">=", 7),
            Map.entry("<<", 8), Map.entry(">>", 8), Map.entry("+", 9), Map.entry("-", 9),
            Map.entry("*", 10), Map.entry("/", 10), Map.entry("%", 10));
    private static final Set<String> ASSIGN_OPS = Set.of("=", "+=", "-=", "*=", "/=", "%=", "<<=", ">>=", "&=", "|=", "^=");
    private static final Set<String> UNARY_OPS = Set.of("!", "-", "+", "~", "++", "--", "*", "&");
    private static final Set<String> IO_FUNCS = Set.of("printf", "scanf", "puts", "gets", "getchar", "putchar", "fprintf", "fscanf", "sprintf");
    private static final Set<String> STREAMS = Set.of("cout", "cerr", "clog", "cin");

    private final List<Token> toks;
    private final Source src;
    private final ErrorReporter rep;
    private int lo, hi, i;
    private String label = "statement";

    public ExpressionLevelParser(List<Token> tokens, Source src, ErrorReporter rep) { this.toks = tokens; this.src = src; this.rep = rep; }

    // ------------------------------------------------------------------ driver

    public Result parse(List<StatementChunk> chunks) {
        List<StatementChunk> sorted = new ArrayList<>(chunks);
        sorted.sort(Comparator.comparingInt(StatementChunk::start));
        AstNode root = new AstNode("PROGRAM_C", "Statements and expressions", 1, 1);
        Map<String, AstNode> holders = new LinkedHashMap<>();
        List<String> summary = new ArrayList<>();
        for (StatementChunk ch : sorted) {
            Token first = toks.get(ch.start());
            String key = ch.function() == null ? "(global scope)" : "function " + ch.function();
            AstNode holder = holders.computeIfAbsent(key,
                    k -> root.add(new AstNode(ch.function() == null ? "GLOBAL" : "FUNCTION", k, first.line(), first.column())));
            lo = ch.start(); hi = ch.end(); i = lo;
            label = switch (ch.kind()) {
                case CONDITION -> "condition";
                case SWITCH_EXPR -> "switch expression";
                case CASE_VALUE -> "case value";
                case FOR_INIT -> "for-loop initialisation";
                case FOR_COND -> "for-loop condition";
                case FOR_INCR -> "for-loop update";
                default -> "statement";
            };
            List<AstNode> out = new ArrayList<>();
            try { parseChunk(ch, out); } catch (Abort ignored) { /* resynchronise at the end of this chunk */ }
            for (AstNode n : out) {
                n.attr("context", ch.context());
                holder.add(n);
                holder.endLine(n.getEndLine());
                summary.add("line " + n.getLine() + ": " + n.getKind().toLowerCase().replace('_', ' ') + " — " + Source.shorten(n.getText() == null ? n.getLabel() : n.getText(), 60));
            }
        }
        return new Result(root, summary);
    }

    private void parseChunk(StatementChunk ch, List<AstNode> out) {
        switch (ch.kind()) {
            case STATEMENT, GLOBAL_DECL -> { while (!atEnd()) { AstNode n = statement(); if (n != null) out.add(n); } }
            case CONDITION, SWITCH_EXPR, CASE_VALUE, FOR_COND -> out.add(valueChunk(ch, false));
            case FOR_INCR -> out.add(valueChunk(ch, true));
            case FOR_INIT -> {
                int s = i;
                AstNode n = new AstNode("FOR_INIT", "initialisation", toks.get(lo).line(), toks.get(lo).column());
                n.add(isDeclStart() ? parseDeclaration() : parseExpressionList());
                if (!atEnd()) leftover();
                n.text(text(s, i));
                out.add(n);
            }
        }
    }

    private AstNode valueChunk(StatementChunk ch, boolean list) {
        Token anchor = toks.get(Math.min(ch.anchor(), hi));
        if (lo == hi) {
            throw fail(err(ErrorCategory.INVALID_EXPRESSION).title("Empty " + label).at(toks.get(hi)).unexpected(toks.get(hi))
                    .expected("an expression").explain("Nothing was written inside the parentheses of the " + label + ".")
                    .suggest("Write the " + label + " between '(' and ')'.").state("expression level: " + label), toks.get(hi));
        }
        Token first = toks.get(lo);
        AstNode n = new AstNode(ch.kind().name(), label, first.line(), first.column());
        n.add(list ? parseExpressionList() : parseAssignment());
        if (!atEnd()) leftover();
        n.text(text(lo, i));
        return n;
    }

    // -------------------------------------------------------------- statements

    private AstNode statement() {
        int start = i;
        Token t = toks.get(i);
        if (t.is(";")) { i++; return null; }
        AstNode n;
        if (t.is("return")) {
            i++;
            n = node("RETURN", "return", t);
            if (!atEnd() && !peekIs(";")) n.add(parseExpressionList());
            int e = i;
            end(start, "return statement", "return");
            return n.text(text(start, e)).endLine(toks.get(e - 1).line());
        }
        if (t.is("break") || t.is("continue")) {
            i++;
            n = node("JUMP", t.text(), t);
            int e = i;
            end(start, t.text() + " statement", "return");
            return n.text(text(start, e));
        }
        String desc, ctx;
        if (isDeclStart()) { n = parseDeclaration(); desc = "variable declaration"; ctx = "declaration"; }
        else if (isStreamStart()) {
            n = parseStream();
            boolean o = n.getKind().startsWith("OUTPUT");
            desc = o ? "output statement" : "input statement"; ctx = o ? "output" : "input";
        } else {
            AstNode e = parseExpressionList();
            n = classify(e);
            desc = switch (n.getKind()) {
                case "ASSIGNMENT" -> "assignment";
                case "FUNCTION_CALL" -> "function call";
                case "IO_CALL" -> "input/output call";
                default -> "statement";
            };
            ctx = "expression";
        }
        int e = i;
        end(start, desc, ctx);
        return n.text(text(start, e)).endLine(toks.get(e - 1).line());
    }

    /** Consumes ';' or reports why it is missing. Missing ';' is recoverable; anything else aborts the chunk. */
    private void end(int start, String desc, String ctx) {
        if (peekIs(";")) { i++; return; }
        Token u = here();
        Token last = toks.get(i - 1);
        boolean newStmt = atEnd() || Keywords.isType(u) || Keywords.isStmtKw(u) || (u.line() > last.line() && canStart(u));
        String stmtText = text(start, i);
        if (newStmt) {
            ErrorReporter.Builder b = err(ErrorCategory.MISSING_SEMICOLON).title("Missing semicolon").at(u).unexpected(u).expected("';'")
                    .explain(u.line() > last.line()
                            ? "The " + desc + " on line " + last.line() + " is not terminated with a semicolon."
                            : "A ';' is needed to end the " + desc + " before '" + u.display() + "'.")
                    .suggest("Add ';' after " + Source.shorten(stmtText, 70) + ".")
                    .related(last.line()).state("expression level: expecting ';' after " + desc);
            if (u.type() != TokenType.INVALID) b.commit();
            return;
        }
        if (u.is(")")) {
            throw fail(err(ErrorCategory.UNEXPECTED_TOKEN).title("Unmatched ')'").at(u).unexpected(u).expected("an operator or ';'")
                    .explain("This ')' closes a parenthesis that was never opened.")
                    .suggest("Remove the ')' or add the missing '(' earlier in the " + desc + ".").state("expression level: after " + desc), u);
        }
        String exp = switch (ctx) {
            case "declaration" -> "',', '=' or ';'";
            case "output" -> "'<<' or ';'";
            case "input" -> "'>>' or ';'";
            case "return" -> "';'";
            default -> "an operator or ';'";
        };
        throw fail(err(ErrorCategory.UNEXPECTED_TOKEN).title("Unexpected '" + u.display() + "'").at(u).unexpected(u).expected(exp)
                .explain("The " + desc + " looks complete before '" + u.display() + "', where " + exp + " was expected. Two values cannot stand next to each other without an operator, ',' or ';' between them.")
                .suggest(ctx.equals("declaration") ? "Separate variable names with commas (int a, b;) or end the declaration with ';'."
                        : "Remove '" + u.display() + "', or put an operator (for example +, -, <<) or a ';' before it.")
                .state("expression level: after " + desc), u);
    }

    private void leftover() {
        Token u = here();
        if (u.is(")")) {
            throw fail(err(ErrorCategory.UNEXPECTED_TOKEN).title("Unmatched ')'").at(u).unexpected(u).expected("an operator")
                    .explain("This ')' has no matching '(' in the " + label + ".").suggest("Remove the extra ')'.")
                    .state("expression level: " + label), u);
        }
        throw fail(err(ErrorCategory.UNEXPECTED_TOKEN).title("Unexpected '" + u.display() + "' in the " + label).at(u).unexpected(u)
                .expected("an operator or the end of the " + label)
                .explain("The " + label + " is complete before '" + u.display() + "', which cannot continue it.")
                .suggest("Remove '" + u.display() + "' or add an operator before it.").state("expression level: " + label), u);
    }

    private AstNode classify(AstNode e) {
        AstNode n;
        switch (e.getKind()) {
            case "ASSIGN" -> n = wrap("ASSIGNMENT", "assignment (" + e.getLabel() + ")", e);
            case "CALL" -> n = wrap(IO_FUNCS.contains(e.getAttributes().get("function")) ? "IO_CALL" : "FUNCTION_CALL", e.getLabel(), e);
            default -> n = wrap("EXPRESSION_STATEMENT", "expression", e);
        }
        n.add(e);
        return n;
    }

    // ------------------------------------------------------------ declarations

    private boolean isDeclStart() {
        Token t = toks.get(i);
        if (Keywords.isType(t)) return true;
        if (t.type() != TokenType.IDENTIFIER || STREAMS.contains(t.text())) return false;
        int k = i;
        if (t.is("std") && k + 2 < hi && toks.get(k + 1).is("::") && toks.get(k + 2).type() == TokenType.IDENTIFIER) {
            if (STREAMS.contains(toks.get(k + 2).text())) return false;
            k += 2;
        }
        return k + 1 < hi && toks.get(k + 1).type() == TokenType.IDENTIFIER;
    }

    private AstNode parseDeclaration() {
        int ts = i;
        Token first = toks.get(i);
        while (!atEnd() && Keywords.isType(toks.get(i))) i++;
        if (i == ts) { i++; while (peekIs("::") && i + 1 < hi && toks.get(i + 1).type() == TokenType.IDENTIFIER) i += 2; }
        String type = text(ts, i);
        AstNode d = node("DECLARATION", type, first).attr("type", type);
        do {
            boolean ptr = false;
            while (peekIs("*") || peekIs("&")) { i++; ptr = true; }
            Token u = here();
            if (atEnd() || u.type() != TokenType.IDENTIFIER) {
                throw fail(err(ErrorCategory.INVALID_DECLARATION).title("Missing variable name after '" + type + "'").at(u).unexpected(u)
                        .expected("a variable name")
                        .explain("A declaration needs a name for the variable, but '" + u.display() + "' was found after the type.")
                        .suggest("Write a name after the type, for example: " + type + " count;").state("expression level: declaration, expecting a name"), u);
            }
            i++;
            AstNode v = d.add(node("VARIABLE", u.text(), u));
            if (ptr) v.attr("pointer", "yes");
            while (peekIs("[")) {
                Token open = toks.get(i++);
                v.attr("array", "yes");
                if (!peekIs("]")) v.add(parseExpressionList());
                close("]", open, false);
            }
            if (peekIs("=")) {
                i++;
                v.add(peekIs("{") ? parseBraceInit() : parseAssignment());
            } else if (peekIs("(")) {
                Token open = toks.get(i++);
                AstNode args = v.add(node("CONSTRUCTOR_ARGS", "(…)", open));
                if (!peekIs(")")) do { args.add(parseAssignment()); } while (accept(","));
                close(")", open, true);
            }
        } while (accept(","));
        return d;
    }

    private AstNode parseBraceInit() {
        Token open = toks.get(i++);
        AstNode l = node("INITIALIZER_LIST", "{…}", open);
        if (!peekIs("}")) do { l.add(peekIs("{") ? parseBraceInit() : parseAssignment()); } while (accept(","));
        if (!accept("}")) {
            Token u = here();
            throw fail(err(ErrorCategory.MISSING_BRACE).title("Missing '}' in initializer list").at(u).unexpected(u).expected("'}'")
                    .related(open.line()).explain("The '{' on line " + open.line() + " is not closed.").suggest("Add '}' after the last value.")
                    .state("expression level: initializer list"), u);
        }
        return l;
    }

    // ---------------------------------------------------------------------- I/O

    private boolean isStreamStart() {
        int k = i;
        if (k + 1 < hi && toks.get(k).is("std") && toks.get(k + 1).is("::")) k += 2;
        return k < hi && toks.get(k).type() == TokenType.IDENTIFIER && STREAMS.contains(toks.get(k).text());
    }

    private AstNode parseStream() {
        if (toks.get(i).is("std")) i += 2;
        Token name = toks.get(i++);
        boolean out = !name.text().equals("cin");
        String op = out ? "<<" : ">>";
        AstNode n = node(out ? "OUTPUT_STATEMENT" : "INPUT_STATEMENT", name.text() + " " + op, name);
        if (!peekIs(op)) {
            Token u = here();
            throw fail(err(ErrorCategory.UNEXPECTED_TOKEN).title("Expected '" + op + "' after '" + name.text() + "'").at(u).unexpected(u).expected("'" + op + "'")
                    .explain("To " + (out ? "print" : "read") + " values with " + name.text() + " use the '" + op + "' operator, but '" + u.display() + "' was found.")
                    .suggest("Write " + name.text() + " " + op + " value;").state("expression level: stream statement"), u);
        }
        while (peekIs(op)) {
            i++;
            n.add(out ? parseBinary(9) : parseUnary());
        }
        return n;
    }

    // ------------------------------------------------------------- expressions

    private AstNode parseExpressionList() {
        AstNode e = parseAssignment();
        if (!peekIs(",")) return e;
        AstNode l = wrap("EXPRESSION_LIST", "comma expression", e);
        l.add(e);
        while (accept(",")) l.add(parseAssignment());
        return l;
    }

    private AstNode parseAssignment() {
        AstNode left = parseTernary();
        if (!atEnd() && toks.get(i).type() == TokenType.OPERATOR && ASSIGN_OPS.contains(toks.get(i).text())) {
            Token op = toks.get(i);
            if (!isLvalue(left)) {
                throw fail(err(ErrorCategory.INVALID_EXPRESSION).title("Cannot assign to this expression").at(op).unexpected(op)
                        .expected("a variable on the left of '" + op.text() + "'")
                        .explain("The left side of '" + op.text() + "' must be a variable (or array element) that can store a value, but it is " + describe(left) + ".")
                        .suggest("Put the variable on the left: x " + op.text() + " value. To compare two values use '==' instead of '='.")
                        .state("expression level: assignment"), op);
            }
            i++;
            AstNode right = parseAssignment();
            AstNode a = wrap("ASSIGN", op.text(), left);
            a.add(left); a.add(right);
            return a;
        }
        return left;
    }

    private String describe(AstNode n) {
        return switch (n.getKind()) {
            case "LITERAL" -> "the constant " + n.getLabel();
            case "BINARY_EXPR" -> "a calculation";
            case "CALL" -> "a function call";
            default -> "not a variable";
        };
    }

    private boolean isLvalue(AstNode n) {
        return switch (n.getKind()) {
            case "IDENTIFIER", "QUALIFIED_NAME", "INDEX", "MEMBER" -> true;
            case "UNARY_EXPR" -> n.getLabel().equals("*");
            default -> false;
        };
    }

    private AstNode parseTernary() {
        AstNode c = parseBinary(1);
        if (!peekIs("?")) return c;
        Token q = toks.get(i++);
        AstNode a = parseAssignment();
        if (!accept(":")) {
            Token u = here();
            throw fail(err(ErrorCategory.UNEXPECTED_TOKEN).title("Missing ':' in conditional expression").at(u).unexpected(u).expected("':'")
                    .related(q.line()).explain("The '?' needs a matching ':' to separate the two results.").suggest("Write condition ? valueIfTrue : valueIfFalse.")
                    .state("expression level: conditional"), u);
        }
        AstNode b = parseTernary();
        AstNode t = wrap("CONDITIONAL_EXPR", "?:", c);
        t.add(c); t.add(a); t.add(b);
        return t;
    }

    private AstNode parseBinary(int min) {
        AstNode left = parseUnary();
        while (!atEnd()) {
            Token op = toks.get(i);
            if (op.type() != TokenType.OPERATOR) break;
            Integer pr = PREC.get(op.text());
            if (pr == null || pr < min) break;
            i++;
            AstNode right = parseBinary(pr + 1);
            AstNode b = wrap("BINARY_EXPR", op.text(), left);
            String cat = switch (op.text()) {
                case "+", "-", "*", "/", "%" -> "arithmetic";
                case "<", ">", "<=", ">=", "==", "!=" -> "relational";
                case "&&", "||" -> "logical";
                case "<<", ">>" -> "shift / stream";
                default -> "bitwise";
            };
            b.attr("category", cat);
            b.add(left); b.add(right);
            left = b;
        }
        return left;
    }

    private AstNode parseUnary() {
        if (!atEnd()) {
            Token u = toks.get(i);
            if (u.type() == TokenType.OPERATOR && UNARY_OPS.contains(u.text())) {
                i++;
                AstNode n = node("UNARY_EXPR", u.text(), u);
                n.add(parseUnary());
                return n;
            }
            if (u.is("(") && i + 1 < hi && Keywords.isType(toks.get(i + 1))) {
                int j = i + 1;
                while (j < hi && (Keywords.isType(toks.get(j)) || toks.get(j).is("*"))) j++;
                if (j < hi && toks.get(j).is(")")) {
                    String ty = text(i + 1, j);
                    i = j + 1;
                    AstNode c = node("CAST", "(" + ty + ")", u);
                    c.add(parseUnary());
                    return c;
                }
            }
        }
        return parsePostfix(primary());
    }

    private AstNode parsePostfix(AstNode e) {
        while (!atEnd()) {
            Token u = toks.get(i);
            if (u.is("(") && (e.getKind().equals("IDENTIFIER") || e.getKind().equals("QUALIFIED_NAME"))) {
                i++;
                AstNode call = wrap("CALL", e.getLabel() + "()", e);
                call.attr("function", e.getLabel());
                if (!peekIs(")")) do { call.add(parseAssignment()); } while (accept(","));
                close(")", u, true);
                e = call;
            } else if (u.is("[")) {
                i++;
                AstNode idx = wrap("INDEX", "[ ]", e);
                idx.add(e);
                idx.add(parseExpressionList());
                close("]", u, false);
                e = idx;
            } else if (u.is(".") || u.is("->")) {
                i++;
                if (atEnd() || toks.get(i).type() != TokenType.IDENTIFIER) {
                    Token x = here();
                    throw fail(err(ErrorCategory.UNEXPECTED_TOKEN).title("Expected a member name after '" + u.text() + "'").at(x).unexpected(x)
                            .expected("a member name").explain("'" + u.text() + "' must be followed by the name of a member.")
                            .suggest("Write object" + u.text() + "member.").state("expression level: member access"), x);
                }
                AstNode m = wrap("MEMBER", u.text() + toks.get(i++).text(), e);
                m.add(e);
                e = m;
            } else if (u.is("++") || u.is("--")) {
                i++;
                AstNode p = wrap("POSTFIX_EXPR", u.text(), e);
                p.add(e);
                e = p;
            } else break;
        }
        return e;
    }

    private AstNode primary() {
        Token u = here();
        if (u.type() == TokenType.INVALID) throw new Abort();
        if (atEnd()) throw missingValue();
        switch (u.type()) {
            case INT_LITERAL, FLOAT_LITERAL, STRING_LITERAL, CHAR_LITERAL -> {
                i++;
                return node("LITERAL", u.text(), u).attr("type", u.type().name().toLowerCase().replace("_literal", ""));
            }
            case KEYWORD -> {
                if (u.is("true") || u.is("false")) { i++; return node("LITERAL", u.text(), u).attr("type", "bool"); }
                throw missingValue();
            }
            case IDENTIFIER -> {
                i++;
                StringBuilder name = new StringBuilder(u.text());
                boolean q = false;
                while (peekIs("::") && i + 1 < hi && toks.get(i + 1).type() == TokenType.IDENTIFIER) {
                    name.append("::").append(toks.get(i + 1).text());
                    i += 2;
                    q = true;
                }
                return node(q ? "QUALIFIED_NAME" : "IDENTIFIER", name.toString(), u);
            }
            default -> {
                if (u.is("(")) {
                    i++;
                    AstNode e = parseExpressionList();
                    close(")", u, false);
                    return e.attr("parenthesized", "yes");
                }
                throw missingValue();
            }
        }
    }

    private Abort missingValue() {
        Token u = here();
        Token pv = i > lo ? toks.get(i - 1) : null;
        boolean afterOp = pv != null && pv.type() == TokenType.OPERATOR;
        ErrorReporter.Builder b = err(ErrorCategory.INVALID_EXPRESSION).at(u).unexpected(u).state("expression level: " + label);
        if (afterOp) {
            b.title("Missing value after '" + pv.text() + "'").expected("a variable, number, call or '(' expression ')'")
                    .explain("The operator '" + pv.text() + "' needs a value on its right side, but '" + u.display() + "' comes next.")
                    .suggest(pv.is("=") ? "Write the value to store after '=', for example: x = 0;"
                            : "Write a value after '" + pv.text() + "' (for example a variable or number) or remove the operator.").related(pv.line());
        } else {
            b.title("Expected an expression, found '" + u.display() + "'").expected("a variable, number, call or '(' expression ')'")
                    .explain("A value (variable, number, function call or parenthesised expression) is needed here, but '" + u.display() + "' was found.")
                    .suggest("Remove '" + u.display() + "' or write the missing value before it.");
        }
        return fail(b, u);
    }

    /** Consumes the closing bracket or reports an unclosed bracket / missing separator. */
    private void close(String closer, Token opener, boolean list) {
        if (peekIs(closer)) { i++; return; }
        Token u = here();
        String openS = closer.equals(")") ? "(" : "[";
        boolean operandLike = !atEnd() && (u.type() == TokenType.IDENTIFIER || u.type() == TokenType.INT_LITERAL
                || u.type() == TokenType.FLOAT_LITERAL || u.type() == TokenType.STRING_LITERAL || u.type() == TokenType.CHAR_LITERAL);
        if (operandLike) {
            String exp = list ? "',' or '" + closer + "'" : "an operator or '" + closer + "'";
            throw fail(err(ErrorCategory.UNEXPECTED_TOKEN).title("Unexpected '" + u.display() + "'").at(u).unexpected(u).expected(exp).related(opener.line())
                    .explain("Either an operator" + (list ? " or ','" : "") + " is missing before '" + u.display() + "', or the '" + openS + "' on line " + opener.line() + " is never closed with '" + closer + "'.")
                    .suggest("Add the missing operator" + (list ? " or ','" : "") + ", or add '" + closer + "' before '" + u.display() + "'.")
                    .state("expression level: inside '" + openS + "' … '" + closer + "'"), u);
        }
        throw fail(err(closer.equals(")") ? ErrorCategory.MISSING_PAREN : ErrorCategory.UNEXPECTED_TOKEN).title("Missing '" + closer + "'").at(u).unexpected(u)
                .expected("'" + closer + "'").related(opener.line())
                .explain("The '" + openS + "' on line " + opener.line() + " is never closed. The parser reached '" + u.display() + "' while it still expected '" + closer + "'.")
                .suggest("Add '" + closer + "' to close the '" + openS + "' before '" + u.display() + "'.")
                .state("expression level: inside '" + openS + "' … '" + closer + "'"), u);
    }

    // ----------------------------------------------------------------- helpers

    private boolean canStart(Token u) {
        return switch (u.type()) {
            case IDENTIFIER, INT_LITERAL, FLOAT_LITERAL, STRING_LITERAL, CHAR_LITERAL, KEYWORD -> true;
            default -> u.is("++") || u.is("--");
        };
    }

    private boolean atEnd() { return i >= hi; }
    private Token here() { return toks.get(Math.min(i, hi)); }
    private boolean peekIs(String s) { return !atEnd() && toks.get(i).is(s); }
    private boolean accept(String s) { if (peekIs(s)) { i++; return true; } return false; }
    private ErrorReporter.Builder err(ErrorCategory c) { return rep.report(3, c); }
    private AstNode node(String kind, String lbl, Token t) { return new AstNode(kind, lbl, t.line(), t.column()); }
    private AstNode wrap(String kind, String lbl, AstNode at) { return new AstNode(kind, lbl, at.getLine(), at.getColumn()); }
    private String text(int from, int toExcl) { return toExcl > from ? src.slice(toks.get(from), toks.get(toExcl - 1)) : ""; }

    private Abort fail(ErrorReporter.Builder b, Token at) {
        if (at.type() != TokenType.INVALID) b.commit();
        return new Abort();
    }
}
