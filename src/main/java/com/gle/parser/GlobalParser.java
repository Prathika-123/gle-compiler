package com.gle.parser;

import com.gle.ast.AstNode;
import com.gle.error.ErrorCategory;
import com.gle.error.ErrorReporter;
import com.gle.lexer.Token;
import com.gle.lexer.TokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 1 - global parsing. Recognizes only the outer skeleton of the program: directives, global
 * declarations and function headers/boundaries. Function bodies are skipped by brace matching and
 * handed to Phase 2 untouched (AST-A stores them as unparsed text).
 */
public final class GlobalParser {
    public record Result(AstNode ast, List<FunctionInfo> functions, List<StatementChunk> globalChunks, List<String> summary) {}

    private final List<Token> t;
    private final Source src;
    private final ErrorReporter rep;
    private int p = 0;
    private boolean strayRun = false;
    private final AstNode root = new AstNode("PROGRAM_A", "Program structure", 1, 1);
    private final List<FunctionInfo> functions = new ArrayList<>();
    private final List<StatementChunk> chunks = new ArrayList<>();
    private final List<String> summary = new ArrayList<>();

    public GlobalParser(List<Token> tokens, Source src, ErrorReporter rep) { this.t = tokens; this.src = src; this.rep = rep; }

    private Token cur() { return t.get(p); }
    private Token at(int i) { return t.get(Math.min(i, t.size() - 1)); }
    private ErrorReporter.Builder err(ErrorCategory c) { return rep.report(1, c); }

    public Result parse() {
        while (!cur().atEof()) {
            Token c = cur();
            if (c.type() == TokenType.PREPROCESSOR) {
                root.add(new AstNode("DIRECTIVE", c.text(), c.line(), c.column()));
                summary.add("Directive (line " + c.line() + "): " + c.text());
                p++;
            } else if (c.is("using")) parseUsing();
            else if (c.is("}")) { unmatchedBrace(c); p++; }
            else if (c.is(";")) p++;
            else if (c.type() == TokenType.INVALID) p++;
            else if (startsDeclaration(c)) { parseDeclarationOrFunction(false); strayRun = false; }
            else if (c.type() == TokenType.IDENTIFIER && at(p + 1).is("(")) {
                err(ErrorCategory.INVALID_FUNCTION).title("Missing return type for function '" + c.text() + "'").at(c)
                        .unexpected(c).expected("a return type such as 'int' or 'void'")
                        .explain("A function definition starts with its return type, but '" + c.text() + "' appears without one.")
                        .suggest("Write the return type before the name, for example: int " + c.text() + "(...) { ... }")
                        .state("global parsing: expecting declaration").commit();
                parseDeclarationOrFunction(true);
                strayRun = false;
            } else if (isUnsupportedTop(c)) skipUnsupported();
            else strayCode();
        }
        return new Result(root, functions, chunks, summary);
    }

    private boolean startsDeclaration(Token c) {
        if (Keywords.isType(c)) return true;
        if (c.type() != TokenType.IDENTIFIER) return false;
        Token n = at(p + 1);
        return n.type() == TokenType.IDENTIFIER || (c.is("std") && n.is("::"));
    }

    private boolean isUnsupportedTop(Token c) {
        return c.is("struct") || c.is("class") || c.is("enum") || c.is("typedef") || c.is("namespace");
    }

    private void skipUnsupported() {
        Token c = cur();
        summary.add("Skipped unsupported construct '" + c.text() + "' (line " + c.line() + ")");
        root.add(new AstNode("UNSUPPORTED", c.text() + " … (not analysed)", c.line(), c.column()));
        while (!cur().atEof()) {
            if (cur().is("{")) { p = Keywords.skipBraces(t, p, t.size() - 1); if (cur().is(";")) p++; return; }
            if (cur().is(";")) { p++; return; }
            p++;
        }
    }

    private void parseUsing() {
        Token u = cur();
        int start = p;
        p++;
        while (cur().is("namespace") || cur().is("::") || cur().type() == TokenType.IDENTIFIER) p++;
        if (cur().is(";")) p++;
        else {
            err(ErrorCategory.MISSING_SEMICOLON).at(cur()).unexpected(cur()).expected("';'").related(u.line())
                    .explain("The 'using' directive on line " + u.line() + " is not terminated with a semicolon.")
                    .suggest("Add ';' after " + src.slice(u, t.get(p - 1)) + ".")
                    .state("global parsing: using directive").commit();
        }
        root.add(new AstNode("DIRECTIVE", src.slice(u, t.get(p - 1)), u.line(), u.column()));
        summary.add("Directive (line " + u.line() + "): " + src.slice(u, t.get(p - 1)));
    }

    private void unmatchedBrace(Token c) {
        if (strayRun) return;
        strayRun = true;
        ErrorReporter.Builder b = err(ErrorCategory.MISSING_BRACE).title("Unmatched '}'").at(c).unexpected(c)
                .expected("a function or declaration, or end of file")
                .explain("This '}' has no matching '{'. Either an earlier '}' already closed the function, or the function's opening '{' is missing.")
                .suggest("Remove this '}' or add the missing '{'. Every '{' needs exactly one '}'.")
                .state("global parsing: between top-level declarations");
        if (!functions.isEmpty()) b.related(functions.get(functions.size() - 1).line);
        b.commit();
    }

    private void strayCode() {
        Token c = cur();
        if (!strayRun) {
            strayRun = true;
            String prevFn = functions.isEmpty() ? null : functions.get(functions.size() - 1).name;
            ErrorReporter.Builder b = err(ErrorCategory.MISPLACED_CODE).title("Statement outside of any function").at(c).unexpected(c)
                    .expected("a function or global declaration, for example 'int main() { ... }'")
                    .explain("'" + c.display() + "' starts a statement, but statements can only be written inside a function body."
                            + (prevFn != null ? " The function '" + prevFn + "' ended before this line, so an extra or misplaced '}' above is the most likely cause." : ""))
                    .suggest("Check the braces above this line: each '{' needs one '}', and the code should sit inside the function's { }.")
                    .state("global parsing: between top-level declarations");
            if (!functions.isEmpty()) b.related(functions.get(functions.size() - 1).endLine);
            b.commit();
        }
        int depth = 0;
        while (!cur().atEof()) {
            Token x = cur();
            if (x.is("{")) depth++;
            else if (x.is("}")) { if (depth == 0) break; depth--; if (depth == 0) { p++; break; } }
            else if (x.is(";") && depth == 0) { p++; break; }
            p++;
        }
    }

    private void parseDeclarationOrFunction(boolean noType) {
        int start = p, typeStart = p;
        String typeText = "";
        if (!noType) {
            boolean sawType = false;
            while (Keywords.isType(cur())) { p++; sawType = true; }
            if (!sawType) { p++; while (cur().is("::") && at(p + 1).type() == TokenType.IDENTIFIER) p += 2; }
            while (cur().is("*") || cur().is("&")) p++;
            typeText = src.slice(t.get(typeStart), t.get(p - 1));
        }
        if (cur().type() == TokenType.IDENTIFIER) {
            if (at(p + 1).is("(")) { Token nameTok = cur(); p++; parseFunction(typeStart, typeText, nameTok); }
            else parseGlobalVariable(start);
        } else if (cur().is("(")) {
            err(ErrorCategory.INVALID_FUNCTION).title("Missing function name").at(cur()).unexpected(cur()).expected("a function name")
                    .explain("A return type '" + typeText + "' is followed directly by '(' without a function name.")
                    .suggest("Write the function name between '" + typeText + "' and '(', e.g. " + typeText + " myFunction(...).")
                    .state("global parsing: function header").commit();
            p = Math.min(Keywords.skipBraces(t, p, t.size() - 1), t.size() - 1);
            while (!cur().atEof() && !cur().is("{") && !cur().is(";")) p++;
            if (cur().is("{")) p = Keywords.skipBraces(t, p, t.size() - 1); else if (cur().is(";")) p++;
        } else {
            err(ErrorCategory.INVALID_DECLARATION).title("Missing name after type '" + typeText + "'").at(cur()).unexpected(cur())
                    .expected("a variable or function name")
                    .explain("The type '" + typeText + "' must be followed by a name, but '" + cur().display() + "' was found.")
                    .suggest("Write a name after the type, e.g. " + typeText + " count;")
                    .state("global parsing: declaration").commit();
            while (!cur().atEof() && !cur().is(";") && !cur().is("{")) p++;
            if (cur().is(";")) p++;
            else if (cur().is("{")) p = Keywords.skipBraces(t, p, t.size() - 1);
        }
    }

    private void parseGlobalVariable(int start) {
        int i = p;
        boolean term = false;
        while (!t.get(i).atEof()) {
            Token x = t.get(i);
            if (x.is(";")) { i++; term = true; break; }
            if (x.is("{")) {
                if (t.get(i - 1).is("=")) { i = Keywords.skipBraces(t, i, t.size() - 1); continue; }
                break;
            }
            if (x.is("}")) break;
            if (i > start && Keywords.isType(x) && x.line() > t.get(i - 1).line() && !t.get(i - 1).is(",")) break;
            i++;
        }
        chunks.add(new StatementChunk(StatementChunk.Kind.GLOBAL_DECL, start, i, term, null, "global scope", start));
        Token f = t.get(start);
        AstNode n = root.add(new AstNode("GLOBAL_DECLARATION", Source.shorten(src.slice(f, t.get(i - 1)), 60), f.line(), f.column()));
        n.attr("status", "contents checked in Phase 3");
        summary.add("Global declaration (line " + f.line() + "): " + Source.shorten(src.slice(f, t.get(i - 1)), 60));
        p = i;
    }

    private void parseFunction(int typeStart, String typeText, Token nameTok) {
        FunctionInfo f = new FunctionInfo();
        f.returnType = typeText;
        f.name = nameTok.text();
        f.nameTok = nameTok;
        f.headerStart = typeStart;
        Token first = t.get(typeStart);
        f.line = first.line();
        f.column = first.column();
        int open = p;
        p++;
        int depth = 1, i = p;
        while (!t.get(i).atEof()) {
            Token x = t.get(i);
            if (x.is("(")) depth++;
            else if (x.is(")")) { if (--depth == 0) break; }
            else if (x.is("{") || x.is("}") || x.is(";")) break;
            i++;
        }
        boolean closed = t.get(i).is(")") && depth == 0;
        if (!closed) {
            err(ErrorCategory.MISSING_PAREN).title("Missing ')' in the header of function '" + f.name + "'").at(t.get(i)).unexpected(t.get(i))
                    .expected("')'").related(t.get(open).line())
                    .explain("The '(' that starts the parameter list on line " + t.get(open).line() + " is not closed before '" + t.get(i).display() + "'.")
                    .suggest("Add ')' at the end of the parameter list: " + f.name + "(...)")
                    .state("global parsing: function header, inside parameter list").commit();
        }
        validateParams(open + 1, i, f);
        p = closed ? i + 1 : i;
        f.headerEnd = p;

        if (cur().is(";")) {
            f.definition = false;
            p++;
        } else {
            f.definition = true;
            if (cur().is("{")) { f.openBrace = p; p++; }
            else if (closed) {
                err(ErrorCategory.MISSING_BRACE).title("Missing '{' to start the body of function '" + f.name + "'").at(cur()).unexpected(cur())
                        .expected("'{'").related(t.get(f.headerEnd - 1).line())
                        .explain("After the header of '" + f.name + "' the parser expects either ';' (a declaration) or '{' to start the function body, but found '" + cur().display() + "'.")
                        .suggest("Add '{' after " + f.signature() + ".")
                        .state("global parsing: after function header").commit();
            }
            f.bodyStart = p;
            int d = 1, j = p;
            while (!t.get(j).atEof()) {
                if (t.get(j).is("{")) d++;
                else if (t.get(j).is("}") && --d == 0) break;
                j++;
            }
            if (t.get(j).is("}") && d == 0) { f.closeBrace = j; f.bodyEnd = j; p = j + 1; }
            else {
                f.unclosed = true;
                f.bodyEnd = j;
                p = j;
                int openLine = f.openBrace >= 0 ? t.get(f.openBrace).line() : f.line;
                err(ErrorCategory.MISSING_BRACE).title("Missing '}' at the end of function '" + f.name + "'").at(t.get(j)).unexpected(t.get(j))
                        .expected("'}'").related(openLine)
                        .explain("The body of '" + f.name + "' opened on line " + openLine + " is never closed: the file ends while " + d + " '{' " + (d == 1 ? "is" : "are") + " still open. One of the blocks inside the function is probably missing its '}'.")
                        .suggest("Add the missing '}' (check each if/for/while block inside '" + f.name + "' for a matching '}').")
                        .state("global parsing: scanning function body for the closing brace").commit();
            }
        }
        f.endLine = t.get(Math.max(0, p - 1)).line();
        functions.add(f);
        buildNode(f);
    }

    private void buildNode(FunctionInfo f) {
        AstNode fn = root.add(new AstNode("FUNCTION", f.signature(), f.line, f.column));
        fn.endLine(f.endLine);
        fn.attr("returnType", f.returnType.isEmpty() ? "(missing)" : f.returnType).attr("name", f.name)
                .attr("parameters", String.valueOf(f.params.size())).attr("form", f.definition ? "definition" : "declaration");
        Token hs = t.get(f.headerStart), he = t.get(f.headerEnd - 1);
        fn.add(new AstNode("HEADER", src.slice(hs, he), hs.line(), hs.column())).attr("child", "1 (header)").attr("status", "validated in Phase 1");
        if (f.definition) {
            Token bs = f.openBrace >= 0 ? t.get(f.openBrace) : t.get(Math.min(f.bodyStart, t.size() - 1));
            AstNode body = fn.add(new AstNode("BODY", f.openBrace >= 0 ? "{ … }" : "(body without '{')", bs.line(), bs.column()));
            body.endLine(f.endLine).attr("child", "2 (body)").attr("status", "kept as text, parsed in Phase 2");
            if (f.bodyEnd > f.bodyStart) body.text(Source.shorten(src.slice(t.get(f.bodyStart), t.get(f.bodyEnd - 1)), 90));
        }
        summary.add((f.definition ? "Function definition" : "Function declaration") + " (lines " + f.line + "–" + f.endLine + "): " + f.signature()
                + (f.definition ? " — body deferred to Phase 2" : ""));
    }

    private void validateParams(int from, int to, FunctionInfo f) {
        if (from >= to) return;
        if (to - from == 1 && t.get(from).is("void")) { f.params.add("void"); return; }
        int s = from;
        for (int i = from; i <= to; i++) {
            if (i == to || t.get(i).is(",")) {
                if (i == s) {
                    err(ErrorCategory.INVALID_FUNCTION).title("Empty parameter").at(at(i)).unexpected(at(i)).expected("a parameter such as 'int a'")
                            .explain("There is a ',' with no parameter before or after it in the parameter list of '" + f.name + "'.")
                            .suggest("Remove the extra ',' or write the missing parameter.").state("global parsing: parameter list").commit();
                } else {
                    validateOne(s, i, f);
                    f.params.add(src.slice(t.get(s), t.get(i - 1)));
                }
                s = i + 1;
            }
        }
    }

    private void validateOne(int s, int e, FunctionInfo f) {
        int k = s;
        boolean hasType = false;
        while (k < e && Keywords.isType(t.get(k))) { k++; hasType = true; }
        if (!hasType && t.get(k).type() == TokenType.IDENTIFIER) {
            k++;
            while (k + 1 < e && t.get(k).is("::") && t.get(k + 1).type() == TokenType.IDENTIFIER) k += 2;
            hasType = k < e;
            if (!hasType) k = s;
        }
        while (k < e && (t.get(k).is("*") || t.get(k).is("&"))) k++;
        if (hasType && k < e && t.get(k).type() == TokenType.IDENTIFIER) k++;
        while (hasType && k < e && t.get(k).is("[")) { k++; while (k < e && !t.get(k).is("]")) k++; if (k < e) k++; }
        if (!hasType) {
            Token a = t.get(s);
            err(ErrorCategory.INVALID_FUNCTION).title("Parameter has no type").at(a).unexpected(a).expected("a type before the parameter name")
                    .explain("Each parameter needs a type followed by a name, but '" + src.slice(a, t.get(e - 1)) + "' has no type.")
                    .suggest("Write the type first, for example 'int " + a.text() + "'.").state("global parsing: parameter list").commit();
        } else if (k < e) {
            Token u = t.get(k);
            err(ErrorCategory.UNEXPECTED_TOKEN).title("Unexpected '" + u.display() + "' in parameter").at(u).unexpected(u)
                    .expected("',' or ')'")
                    .explain("A parameter is written as <type> <name>, but '" + u.display() + "' does not fit that shape.")
                    .suggest("Separate parameters with commas, e.g. (int a, int b).").state("global parsing: parameter list").commit();
        }
    }
}
