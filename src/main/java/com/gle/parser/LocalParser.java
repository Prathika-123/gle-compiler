package com.gle.parser;

import com.gle.ast.AstNode;
import com.gle.error.ErrorCategory;
import com.gle.error.ErrorReporter;
import com.gle.lexer.Token;
import com.gle.parser.StatementChunk.Kind;

import java.util.*;

/**
 * Phase 2 - local parsing. Inside each function body (a token range from Phase 1) only the
 * structure of if / if-else / for / while / do-while / switch is validated: keywords, parentheses,
 * braces, header parts and bodies. Everything inside headers and plain statements is cut into
 * {@link StatementChunk}s for Phase 3 without being checked.
 */
public final class LocalParser {
    public record Result(AstNode ast, List<StatementChunk> chunks, List<String> summary) {}

    private record Header(int start, int end, int open, boolean ok) {}

    private static final Set<String> CONTINUATION = Set.of("&&", "||", "==", "!=", "<", ">", "<=", ">=", "/", "%", "?",
            "|", "^", "<<", ">>", ".", "->", "=", "+=", "-=", "*=", "/=", "%=");

    private final List<Token> t;
    private final Source src;
    private final ErrorReporter rep;
    private final List<StatementChunk> chunks = new ArrayList<>();
    private final List<String> ctx = new ArrayList<>();
    private int pos, limit;
    private FunctionInfo fn;

    public LocalParser(List<Token> tokens, Source src, ErrorReporter rep) { this.t = tokens; this.src = src; this.rep = rep; }

    private ErrorReporter.Builder err(ErrorCategory c) { return rep.report(2, c); }
    private Token cur() { return t.get(pos); }
    private Token at(int i) { return t.get(Math.min(i, t.size() - 1)); }
    private boolean curIs(String s) { return pos < limit && t.get(pos).is(s); }
    private String path(String part) { return String.join(" > ", ctx) + (part == null ? "" : " > " + part); }

    public Result parse(List<FunctionInfo> funcs) {
        AstNode root = new AstNode("PROGRAM_B", "Control structures", 1, 1);
        for (FunctionInfo f : funcs) {
            if (!f.definition) continue;
            fn = f; pos = f.bodyStart; limit = f.bodyEnd;
            ctx.clear(); ctx.add("function " + f.name);
            AstNode fnode = root.add(new AstNode("FUNCTION", f.signature(), f.line, f.column));
            fnode.endLine(f.endLine);
            parseList(fnode, 0);
        }
        List<String> summary = new ArrayList<>();
        collect(root, summary, 0);
        return new Result(root, chunks, summary);
    }

    /** mode 0 = function body, 1 = inside { }, 2 = inside a case/default group. */
    private void parseList(AstNode parent, int mode) {
        AstNode[] grp = new AstNode[1];
        while (pos < limit) {
            Token c = cur();
            if (mode >= 1 && c.is("}")) break;
            if (mode == 2 && (c.is("case") || c.is("default"))) break;
            if (mode == 0 && c.is("}")) { pos++; continue; }
            parseStatement(parent, grp);
        }
    }

    private void parseStatement(AstNode parent, AstNode[] grp) {
        Token c = cur();
        if (c.is(";")) { pos++; return; }
        if (c.is("{")) { grp[0] = null; AstNode b = parent.add(new AstNode("BLOCK", "block", c.line(), c.column())); parseBlockInto(b); return; }
        if (c.is("if")) { grp[0] = null; parseIf(parent); return; }
        if (c.is("for")) { grp[0] = null; parseFor(parent); return; }
        if (c.is("while")) { grp[0] = null; parseWhile(parent); return; }
        if (c.is("do")) { grp[0] = null; parseDo(parent); return; }
        if (c.is("switch")) { grp[0] = null; parseSwitch(parent); return; }
        if (c.is("else")) {
            err(ErrorCategory.INVALID_CONTROL).title("'else' without a matching 'if'").at(c).unexpected(c).expected("a statement")
                    .explain("An 'else' must directly follow the body of an 'if'. Here the previous 'if' was already complete, ended early (for example by an extra ';' or '}'), or there is none.")
                    .suggest("Check the braces and ';' before this 'else', or remove it.").state("local parsing: statement start").commit();
            pos++; return;
        }
        if (c.is("case") || c.is("default")) {
            err(ErrorCategory.INVALID_CONTROL).title("'" + c.text() + "' outside of a switch").at(c).unexpected(c).expected("a statement")
                    .explain("'" + c.text() + "' labels are only allowed inside the braces of a switch statement.")
                    .suggest("Move this label inside a switch (...) { ... } or remove it.").state("local parsing: statement start").commit();
            while (pos < limit && !cur().is(":") && !cur().is(";") && !cur().is("{")) pos++;
            if (curIs(":")) pos++;
            return;
        }
        simple(parent, grp);
    }

    private void simple(AstNode parent, AstNode[] grp) {
        int start = pos, i = pos + 1;
        boolean term = false;
        while (i < limit) {
            Token x = t.get(i);
            if (x.is(";")) { i++; term = true; break; }
            if (Keywords.isStmtKw(x) || x.is("}")) break;
            if (x.is("{")) {
                Token pv = t.get(i - 1);
                if (pv.is("=") || pv.is(",") || pv.is("{")) { i = Keywords.skipBraces(t, i, limit); continue; }
                break;
            }
            i++;
        }
        pos = i;
        chunks.add(new StatementChunk(Kind.STATEMENT, start, i, term, fn.name, path(null), start));
        Token first = t.get(start), last = t.get(i - 1);
        if (grp[0] == null) grp[0] = parent.add(new AstNode("STATEMENTS", "Statements", first.line(), first.column()));
        AstNode g = grp[0];
        String text = src.slice(first, last);
        g.add(new AstNode("STATEMENT", Source.shorten(text, 60), first.line(), first.column()).endLine(last.line())
                .attr("terminated", term ? "yes" : "no (checked in Phase 3)"));
        g.label("Statements (" + g.getChildren().size() + ")").endLine(last.line());
    }

    private void parseBlockInto(AstNode node) {
        Token open = cur();
        pos++;
        parseList(node, 1);
        if (curIs("}")) { node.endLine(cur().line()); pos++; }
        else {
            node.endLine(at(pos).line());
            if (!fn.unclosed) {
                err(ErrorCategory.MISSING_BRACE).title("Missing '}' for the block opened on line " + open.line()).at(at(pos)).unexpected(at(pos))
                        .expected("'}'").related(open.line())
                        .explain("The '{' on line " + open.line() + " is never closed inside function '" + fn.name + "'.")
                        .suggest("Add '}' where the block should end.").state("local parsing: inside block").commit();
            }
        }
    }

    // ---------------------------------------------------------------- headers

    private Header parseHeader(String kw, Token kwTok, boolean allowSemis) {
        if (!curIs("(")) {
            Token u = at(pos);
            err(ErrorCategory.MISSING_PAREN).title("Missing '(' after '" + kw + "'").at(u).unexpected(u).expected("'('")
                    .related(kwTok.line())
                    .explain("The condition of '" + kw + "' must be written inside parentheses, but '" + u.display() + "' follows '" + kw + "'.")
                    .suggest("Write " + kw + " ( ... ) with the " + (kw.equals("for") ? "three loop parts" : "condition") + " inside the parentheses.")
                    .state("local parsing: after '" + kw + "', expecting '('").commit();
            int s = pos, i = pos;
            while (i < limit && !t.get(i).is("{") && !t.get(i).is(";") && !Keywords.isStmtKw(t.get(i))) i++;
            pos = i;
            return new Header(s, i, -1, false);
        }
        int open = pos;
        pos++;
        int depth = 1, i = pos;
        while (i < limit) {
            Token x = t.get(i);
            if (x.is("(")) depth++;
            else if (x.is(")")) { if (--depth == 0) break; }
            else if (x.is("{") || x.is("}") || Keywords.isStmtKw(x)) break;
            else if (x.is(";") && !allowSemis) break;
            i++;
        }
        if (i < limit && t.get(i).is(")") && depth == 0) {
            pos = i + 1;
            return new Header(open + 1, i, open, true);
        }
        Token u = at(i);
        err(ErrorCategory.MISSING_PAREN).title("Missing ')' in the " + kw + " header").at(u).unexpected(u).expected("')'")
                .related(t.get(open).line())
                .explain("The '(' after '" + kw + "' on line " + t.get(open).line() + " is not closed before '" + u.display() + "'.")
                .suggest("Add ')' to end the " + kw + " header before '" + u.display() + "'.")
                .state("local parsing: inside " + kw + " header, expecting ')'").commit();
        pos = i;
        return new Header(open + 1, i, open, false);
    }

    private void addHeaderChunk(AstNode node, Header h, Kind kind, String part, int kwIdx) {
        String text = h.end() > h.start() ? src.slice(t.get(h.start()), t.get(h.end() - 1)) : "";
        node.attr("header", text);
        if (h.end() > h.start() || h.ok())
            chunks.add(new StatementChunk(kind, h.start(), h.end(), true, fn.name, path(part), h.ok() ? h.end() : kwIdx));
    }

    private void checkAfterHeader(String kw, Header h) {
        if (!h.ok() || pos >= limit) return;
        Token n = cur();
        if (n.is(")")) {
            err(ErrorCategory.MISSING_PAREN).title("Extra ')' after the " + kw + " condition").at(n).unexpected(n).expected("'{' or a statement")
                    .explain("The condition was already closed by the previous ')'. This second ')' has no matching '('.")
                    .suggest("Remove the extra ')'.").state("local parsing: after " + kw + " header").commit();
            pos++;
        } else if (n.type() == com.gle.lexer.TokenType.OPERATOR && CONTINUATION.contains(n.text())) {
            Token close = t.get(pos - 1);
            err(ErrorCategory.INVALID_CONTROL).title("The " + kw + " condition ends too early").at(n).unexpected(n)
                    .expected("'{' or a statement as the body of the " + kw).related(close.line())
                    .explain("The ')' on line " + close.line() + " closes the " + kw + " condition, so '" + n.text() + "' is left outside the parentheses. The whole condition must sit inside ONE pair of parentheses.")
                    .suggest("Move the ')' to the end of the complete condition, e.g. " + kw + " ((a) " + n.text() + " (b)) { ... }.")
                    .state("local parsing: after " + kw + " header, expecting the body").commit();
            while (pos < limit && !cur().is("{") && !cur().is(";") && !Keywords.isStmtKw(cur())) pos++;
        }
    }

    private void parseBody(AstNode ctrl, String label, Token kwTok, String what) {
        Token at = at(pos);
        AstNode body = ctrl.add(new AstNode("BODY", label, at.line(), at.column()));
        if (pos >= limit || cur().is("}")) {
            err(ErrorCategory.INVALID_CONTROL).title("Missing body after " + what).at(at).unexpected(at).expected("a statement or a block '{ ... }'")
                    .related(kwTok.line())
                    .explain("The " + what + " on line " + kwTok.line() + " has no body: the code ends (or the function is closed by '}') right after it.")
                    .suggest("Write the statement to repeat/run, or a block { ... }. If a '}' above closed the function too early, remove it.")
                    .state("local parsing: expecting body of " + what).commit();
            ctrl.attr("body", "missing");
            return;
        }
        if (cur().is("{")) {
            ctrl.attr("body", "block");
            body.attr("braces", "yes");
            parseBlockInto(body);
        } else {
            ctrl.attr("body", "single statement");
            body.attr("braces", "no");
            parseStatement(body, new AstNode[1]);
        }
        body.endLine(t.get(Math.max(0, pos - 1)).line());
    }

    // ------------------------------------------------------- control structures

    private void parseIf(AstNode parent) {
        Token kw = cur();
        int kwIdx = pos++;
        ctx.add("if (line " + kw.line() + ")");
        AstNode n = parent.add(new AstNode("IF", "if", kw.line(), kw.column()));
        Header h = parseHeader("if", kw, false);
        addHeaderChunk(n, h, Kind.CONDITION, "condition", kwIdx);
        checkAfterHeader("if", h);
        parseBody(n, "then", kw, "'if (...)'");
        if (curIs("else")) {
            Token e = cur();
            pos++;
            AstNode el = n.add(new AstNode("ELSE", "else", e.line(), e.column()));
            if (curIs("if")) { el.attr("form", "else if"); parseIf(el); }
            else parseBody(el, "else body", e, "'else'");
            el.endLine(t.get(Math.max(0, pos - 1)).line());
        }
        n.endLine(t.get(Math.max(0, pos - 1)).line());
        ctx.remove(ctx.size() - 1);
    }

    private void parseWhile(AstNode parent) {
        Token kw = cur();
        int kwIdx = pos++;
        ctx.add("while (line " + kw.line() + ")");
        AstNode n = parent.add(new AstNode("WHILE", "while", kw.line(), kw.column()));
        Header h = parseHeader("while", kw, false);
        addHeaderChunk(n, h, Kind.CONDITION, "condition", kwIdx);
        checkAfterHeader("while", h);
        parseBody(n, "body", kw, "'while (...)'");
        n.endLine(t.get(Math.max(0, pos - 1)).line());
        ctx.remove(ctx.size() - 1);
    }

    private void parseDo(AstNode parent) {
        Token kw = cur();
        pos++;
        ctx.add("do-while (line " + kw.line() + ")");
        AstNode n = parent.add(new AstNode("DO_WHILE", "do … while", kw.line(), kw.column()));
        parseBody(n, "body", kw, "'do'");
        if (curIs("while")) {
            Token w = cur();
            int wIdx = pos++;
            Header h = parseHeader("while", w, false);
            addHeaderChunk(n, h, Kind.CONDITION, "condition", wIdx);
            if (curIs(";")) pos++;
            else {
                Token u = at(pos);
                err(ErrorCategory.MISSING_SEMICOLON).title("Missing ';' after do-while").at(u).unexpected(u).expected("';'")
                        .related(t.get(pos - 1).line())
                        .explain("A do-while statement must end with a semicolon after 'while (...)'.")
                        .suggest("Add ';' after the closing ')' of while (...).").state("local parsing: end of do-while").commit();
            }
        } else {
            Token u = at(pos);
            err(ErrorCategory.INVALID_CONTROL).title("Missing 'while' after the do body").at(u).unexpected(u).expected("'while ( condition ) ;'")
                    .related(kw.line())
                    .explain("The 'do' on line " + kw.line() + " needs 'while (condition);' right after its body.")
                    .suggest("Add while (condition); after the body.").state("local parsing: after do body").commit();
        }
        n.endLine(t.get(Math.max(0, pos - 1)).line());
        ctx.remove(ctx.size() - 1);
    }

    private void parseFor(AstNode parent) {
        Token kw = cur();
        int kwIdx = pos++;
        ctx.add("for (line " + kw.line() + ")");
        AstNode n = parent.add(new AstNode("FOR", "for", kw.line(), kw.column()));
        Header h = parseHeader("for", kw, true);
        if (h.open() >= 0) {
            n.attr("header", h.end() > h.start() ? src.slice(t.get(h.start()), t.get(h.end() - 1)) : "");
            splitFor(n, h, kw);
        }
        parseBody(n, "body", kw, "'for (...)'");
        n.endLine(t.get(Math.max(0, pos - 1)).line());
        ctx.remove(ctx.size() - 1);
    }

    private void splitFor(AstNode n, Header h, Token kw) {
        List<Integer> semis = new ArrayList<>(), commas = new ArrayList<>();
        boolean colon = false;
        int depth = 0;
        for (int i = h.start(); i < h.end(); i++) {
            Token x = t.get(i);
            if (x.is("(") || x.is("[")) depth++;
            else if (x.is(")") || x.is("]")) depth--;
            else if (depth == 0) {
                if (x.is(";")) semis.add(i); else if (x.is(",")) commas.add(i); else if (x.is(":")) colon = true;
            }
        }
        if (colon && semis.isEmpty()) { n.attr("form", "range-based for (contents not checked)"); return; }
        Token closing = at(h.end());
        List<Integer> seps = new ArrayList<>();
        if (semis.size() >= 2) {
            seps.add(semis.get(0)); seps.add(semis.get(1));
            if (semis.size() > 2) {
                Token x = t.get(semis.get(2));
                err(ErrorCategory.INVALID_FOR_HEADER).title("Too many ';' in the for header").at(x).unexpected(x).expected("')'")
                        .explain("A for header has exactly two ';' (init; condition; update) but this one has " + semis.size() + ".")
                        .suggest("Remove the extra ';'.").state("local parsing: for header, after the update part").commit();
            }
        } else if (semis.isEmpty() && commas.size() >= 2) {
            Token x = t.get(commas.get(0));
            err(ErrorCategory.INVALID_FOR_HEADER).title("Commas used instead of ';' in the for header").at(x).unexpected(x).expected("';'")
                    .explain("The three parts of a for header are separated by semicolons, not commas, but ',' was found where the first ';' belongs.")
                    .suggest("Replace the commas with ';' like this: for (init; condition; update).")
                    .state("local parsing: for header, expecting ';' after the init part").commit();
            seps.add(commas.get(0)); seps.add(commas.get(1));
        } else if (semis.size() == 1) {
            int after = -1;
            for (int c : commas) if (c > semis.get(0)) { after = c; break; }
            seps.add(semis.get(0));
            if (after >= 0) {
                Token x = t.get(after);
                err(ErrorCategory.INVALID_FOR_HEADER).title("',' used instead of ';' in the for header").at(x).unexpected(x).expected("';'")
                        .explain("The second separator of a for header must be ';', but ',' was found.")
                        .suggest("Replace this ',' with ';'.").state("local parsing: for header, expecting the second ';'").commit();
                seps.add(after);
            } else reportMissingSemis(closing, kw, 1);
        } else {
            reportMissingSemis(closing, kw, 0);
            return;
        }
        int a = h.start();
        int[][] parts = new int[seps.size() + 1][2];
        for (int k = 0; k < parts.length; k++) {
            parts[k][0] = k == 0 ? a : seps.get(k - 1) + 1;
            parts[k][1] = k < seps.size() ? seps.get(k) : h.end();
        }
        Kind[] kinds = {Kind.FOR_INIT, Kind.FOR_COND, Kind.FOR_INCR};
        String[] names = {"init", "condition", "update"};
        for (int k = 0; k < parts.length && k < 3; k++) {
            if (parts[k][1] > parts[k][0]) {
                chunks.add(new StatementChunk(kinds[k], parts[k][0], parts[k][1], true, fn.name, path("for " + names[k]), parts[k][0]));
                n.add(new AstNode("FOR_" + names[k].toUpperCase(), names[k] + ": " + src.slice(t.get(parts[k][0]), t.get(parts[k][1] - 1)),
                        t.get(parts[k][0]).line(), t.get(parts[k][0]).column())).attr("status", "expression checked in Phase 3");
            }
        }
    }

    private void reportMissingSemis(Token at, Token kw, int found) {
        err(ErrorCategory.INVALID_FOR_HEADER).title("The for header needs two ';'").at(at).unexpected(at).expected("';'")
                .related(kw.line())
                .explain("A for header has three parts separated by two semicolons: for (init; condition; update). Only " + found + " ';' " + (found == 1 ? "was" : "were") + " found.")
                .suggest("Write it as for (i = 0; i < n; i++).").state("local parsing: for header, expecting ';' separators").commit();
    }

    private void parseSwitch(AstNode parent) {
        Token kw = cur();
        int kwIdx = pos++;
        ctx.add("switch (line " + kw.line() + ")");
        AstNode n = parent.add(new AstNode("SWITCH", "switch", kw.line(), kw.column()));
        Header h = parseHeader("switch", kw, false);
        addHeaderChunk(n, h, Kind.SWITCH_EXPR, "switch expression", kwIdx);
        checkAfterHeader("switch", h);
        if (curIs("{")) {
            Token open = cur();
            pos++;
            AstNode[] grp = new AstNode[1];
            boolean warned = false;
            while (pos < limit && !cur().is("}")) {
                if (cur().is("case") || cur().is("default")) parseCase(n);
                else {
                    if (!warned) {
                        warned = true;
                        err(ErrorCategory.INVALID_CONTROL).title("Statement is not under a case or default label").at(cur()).unexpected(cur())
                                .expected("'case value:' or 'default:'")
                                .explain("Inside a switch every statement must belong to a 'case' or 'default' label; this one comes before any label.")
                                .suggest("Put a 'case ...:' or 'default:' label above this statement.").state("local parsing: inside switch body").commit();
                    }
                    parseStatement(n, grp);
                }
            }
            if (curIs("}")) { n.endLine(cur().line()); pos++; }
            else if (!fn.unclosed) {
                err(ErrorCategory.MISSING_BRACE).title("Missing '}' for the switch opened on line " + open.line()).at(at(pos)).unexpected(at(pos))
                        .expected("'}'").related(open.line()).explain("The switch body is never closed.").suggest("Add '}' after the last case.")
                        .state("local parsing: inside switch body").commit();
            }
        } else {
            Token u = at(pos);
            err(ErrorCategory.MISSING_BRACE).title("Missing '{' after the switch header").at(u).unexpected(u).expected("'{'").related(kw.line())
                    .explain("A switch statement needs its case labels inside braces: switch (x) { case 1: ... }.")
                    .suggest("Add '{' after switch (...).").state("local parsing: after switch header").commit();
            if (pos < limit && !cur().is("}")) parseStatement(n, new AstNode[1]);
        }
        n.endLine(t.get(Math.max(0, pos - 1)).line());
        ctx.remove(ctx.size() - 1);
    }

    private void parseCase(AstNode sw) {
        Token kw = cur();
        int kwIdx = pos++;
        boolean isCase = kw.is("case");
        AstNode c = sw.add(new AstNode(isCase ? "CASE" : "DEFAULT", isCase ? "case" : "default", kw.line(), kw.column()));
        ctx.add(kw.text() + " (line " + kw.line() + ")");
        if (isCase) {
            int s = pos;
            while (pos < limit && !cur().is(":") && !cur().is(";") && !cur().is("{") && !cur().is("}") && !Keywords.isStmtKw(cur())
                    && !(pos > s && cur().line() > t.get(pos - 1).line())) pos++;
            if (pos > s) {
                chunks.add(new StatementChunk(Kind.CASE_VALUE, s, pos, true, fn.name, path("case value"), kwIdx));
                c.attr("header", src.slice(t.get(s), t.get(pos - 1)));
            } else {
                err(ErrorCategory.INVALID_CONTROL).title("Missing value after 'case'").at(at(pos)).unexpected(at(pos)).expected("a constant such as 1 or 'a'")
                        .explain("A case label needs the value to compare with.").suggest("Write case 1: (or another constant).")
                        .state("local parsing: case label").commit();
            }
        }
        if (curIs(":")) pos++;
        else {
            Token u = at(pos);
            err(ErrorCategory.UNEXPECTED_TOKEN).title("Missing ':' after '" + kw.text() + "' label").at(u).unexpected(u).expected("':'")
                    .related(kw.line())
                    .explain("A '" + kw.text() + "' label must end with a colon before its statements.")
                    .suggest("Add ':' after the " + (isCase ? "case value" : "'default'") + ".").state("local parsing: case label, expecting ':'").commit();
        }
        parseList(c, 2);
        c.endLine(t.get(Math.max(0, pos - 1)).line());
        ctx.remove(ctx.size() - 1);
    }

    // ---------------------------------------------------------------- summary

    private void collect(AstNode n, List<String> out, int depth) {
        String ind = "  ".repeat(depth);
        switch (n.getKind()) {
            case "FUNCTION" -> { out.add("Function " + n.getLabel() + " (lines " + n.getLine() + "–" + n.getEndLine() + ")"); depth = 1; }
            case "IF", "FOR", "WHILE", "DO_WHILE", "SWITCH" -> {
                String h = n.getAttributes().get("header");
                out.add(ind + n.getKind().toLowerCase().replace('_', '-') + " at line " + n.getLine()
                        + (h != null ? " (" + Source.shorten(h, 40) + ")" : "") + " — body: " + n.getAttributes().getOrDefault("body", "see children"));
                depth++;
            }
            case "ELSE" -> { out.add(ind + n.getAttributes().getOrDefault("form", "else") + " at line " + n.getLine()); depth++; }
            case "CASE", "DEFAULT" -> { out.add(ind + n.getLabel() + " at line " + n.getLine() + (n.getAttributes().containsKey("header") ? " (" + n.getAttributes().get("header") + ")" : "")); depth++; }
            default -> { }
        }
        for (AstNode c : n.getChildren()) collect(c, out, depth);
    }
}
