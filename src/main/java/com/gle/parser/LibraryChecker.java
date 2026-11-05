package com.gle.parser;

import com.gle.error.ErrorCategory;
import com.gle.error.ErrorReporter;
import com.gle.lexer.Token;
import com.gle.lexer.TokenType;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Library-usage check, reported as part of Phase 1 (it only needs directives and the names used in
 * function headers, function bodies and global declarations).
 *
 * <ul>
 *   <li><b>Missing header</b>: a standard-library name such as {@code cout}, {@code vector} or
 *       {@code sqrt} is used but no matching {@code #include} appears before its first use.</li>
 *   <li><b>Missing namespace</b>: a name that lives in {@code std} (for example {@code cout},
 *       {@code string}) is used without {@code std::} and without {@code using namespace std;}
 *       (or a {@code using std::name;} declaration) before it.</li>
 * </ul>
 *
 * Only code inside functions and global declarations is inspected, so stray code outside any
 * function (already reported as misplaced code) does not produce extra library errors.
 * Names the program declares itself (a variable called {@code max}, a function called {@code log})
 * are never reported.
 */
public final class LibraryChecker {
    public record Result(List<String> summary) {}

    /** One known library name. {@code headers} lists the includes that make it available. */
    private record Lib(String name, boolean inStd, boolean needsCall, boolean needsTemplateArgs,
                       String suggested, List<String> headers) {}

    private static final Pattern INCLUDE = Pattern.compile("^#\\s*include\\s*([<\"])\\s*([^>\"]+?)\\s*[>\"].*$");
    private static final String BITS = "bits/stdc++.h";

    private static final Map<String, Lib> LIBS = new LinkedHashMap<>();

    private static void add(String names, boolean inStd, boolean call, boolean tmpl, String suggested, String... headers) {
        List<String> h = new ArrayList<>(Arrays.asList(headers));
        h.add(BITS);
        for (String n : names.split("\\s+")) LIBS.put(n, new Lib(n, inStd, call, tmpl, suggested, h));
    }

    static {
        // C++ streams
        add("cout cin cerr clog endl", true, false, false, "iostream", "iostream");
        // strings: <iostream>/<sstream>/<fstream> pull in <string> on every common compiler, so they are accepted
        add("string", true, false, false, "string", "string", "iostream", "sstream", "fstream");
        add("to_string stoi stod stol stoll stof", true, true, false, "string", "string", "iostream", "sstream", "fstream");
        add("getline", true, true, false, "string", "string", "istream", "iostream", "sstream", "fstream");
        add("stringstream istringstream ostringstream", true, false, false, "sstream", "sstream");
        add("ifstream ofstream fstream", true, false, false, "fstream", "fstream");
        add("setw setprecision setfill", true, true, false, "iomanip", "iomanip");
        // containers
        add("vector", true, false, true, "vector", "vector");
        add("map multimap", true, false, true, "map", "map");
        add("set multiset", true, false, true, "set", "set");
        add("unordered_map", true, false, true, "unordered_map", "unordered_map");
        add("unordered_set", true, false, true, "unordered_set", "unordered_set");
        add("queue priority_queue", true, false, true, "queue", "queue");
        add("stack", true, false, true, "stack", "stack");
        add("deque", true, false, true, "deque", "deque");
        add("list", true, false, true, "list", "list");
        add("array", true, false, true, "array", "array");
        add("pair", true, false, true, "utility", "utility", "map", "unordered_map", "set", "algorithm", "tuple");
        add("make_pair", true, true, false, "utility", "utility", "map", "unordered_map", "set", "algorithm", "tuple");
        // algorithms
        add("sort reverse min max min_element max_element", true, true, false, "algorithm", "algorithm");
        // C library (not in std when used the C way, so no namespace check)
        add("printf scanf puts putchar getchar fprintf sprintf snprintf fopen fclose fgets fputs perror",
                false, true, false, "cstdio", "cstdio", "stdio.h");
        add("sqrt pow sin cos tan floor ceil fabs log log10 exp round fmod atan atan2",
                false, true, false, "cmath", "cmath", "math.h");
        add("abs", false, true, false, "cstdlib", "cstdlib", "stdlib.h", "cmath", "math.h");
        add("rand srand exit atoi atof malloc calloc realloc free system qsort",
                false, true, false, "cstdlib", "cstdlib", "stdlib.h");
        add("strlen strcpy strcmp strcat strncpy strncmp strstr strchr memset memcpy",
                false, true, false, "cstring", "cstring", "string.h");
        add("isdigit isalpha isalnum isspace isupper islower ispunct toupper tolower",
                false, true, false, "cctype", "cctype", "ctype.h");
        add("time clock", false, true, false, "ctime", "ctime", "time.h");
        add("INT_MAX INT_MIN UINT_MAX LONG_MAX LONG_MIN LLONG_MAX LLONG_MIN",
                false, false, false, "climits", "climits", "limits.h");
    }

    private final List<Token> t;
    private final ErrorReporter rep;
    private final List<FunctionInfo> functions;
    private final List<StatementChunk> globalChunks;

    public LibraryChecker(List<Token> tokens, ErrorReporter rep, List<FunctionInfo> functions, List<StatementChunk> globalChunks) {
        this.t = tokens;
        this.rep = rep;
        this.functions = functions;
        this.globalChunks = globalChunks;
    }

    /** One use of a library name. */
    private record Use(int index, Lib lib, boolean qualified) {}

    public Result check() {
        List<String> summary = new ArrayList<>();
        boolean[] inScope = scope();
        Set<String> userNames = userDeclaredNames();

        // directives, in source order
        record Include(int index, String header, boolean angle) {}
        List<Include> includes = new ArrayList<>();
        boolean localInclude = false;
        for (int i = 0; i < t.size(); i++) {
            Token x = t.get(i);
            if (x.type() != TokenType.PREPROCESSOR) continue;
            Matcher m = INCLUDE.matcher(x.text());
            if (!m.matches()) continue;
            boolean angle = m.group(1).equals("<");
            includes.add(new Include(i, m.group(2).trim(), angle));
            if (!angle) localInclude = true;
        }

        // using-directives / using-declarations for std, with the token index where they take effect
        int usingNamespaceStd = -1;
        Map<String, Integer> usingNames = new HashMap<>();
        for (int i = 0; i + 2 < t.size(); i++) {
            if (!t.get(i).is("using")) continue;
            if (t.get(i + 1).is("namespace") && t.get(i + 2).is("std")) {
                if (usingNamespaceStd < 0) usingNamespaceStd = i;
            } else if (t.get(i + 1).is("std") && i + 3 < t.size() && t.get(i + 2).is("::")
                    && t.get(i + 3).type() == TokenType.IDENTIFIER) {
                usingNames.putIfAbsent(t.get(i + 3).text(), i);
            }
        }

        // collect uses
        List<Use> uses = new ArrayList<>();
        for (int i = 0; i < t.size(); i++) {
            if (!inScope[i]) continue;
            Token x = t.get(i);
            if (x.type() != TokenType.IDENTIFIER) continue;
            Lib lib = LIBS.get(x.text());
            if (lib == null || userNames.contains(x.text())) continue;
            Token prev = i > 0 ? t.get(i - 1) : null;
            Token next = i + 1 < t.size() ? t.get(i + 1) : null;
            boolean qualified = false;
            if (prev != null && (prev.is(".") || prev.is("->"))) continue;          // member access
            if (prev != null && prev.is("::")) {
                boolean std = i >= 2 && t.get(i - 2).is("std");
                if (!std) continue;                                                // Foo::name, ::name
                qualified = true;
            }
            if (lib.needsCall() && (next == null || !next.is("("))) continue;
            if (lib.needsTemplateArgs() && (next == null || !next.is("<"))) continue;
            uses.add(new Use(i, lib, qualified));
        }

        checkHeaders(uses, includes.stream().map(Include::index).toList(),
                includes.stream().map(Include::header).toList(), localInclude, summary);
        checkNamespace(uses, includes.stream().map(Include::index).toList(), usingNamespaceStd, usingNames, summary);
        return new Result(summary);
    }

    // ------------------------------------------------------------------ headers

    private void checkHeaders(List<Use> uses, List<Integer> incIdx, List<String> incNames, boolean localInclude, List<String> summary) {
        // A quoted include (#include "mine.h") may provide anything, so nothing can be said about missing headers.
        if (localInclude) return;
        // group by the header that would be suggested, in order of first use
        Map<String, List<Use>> missing = new LinkedHashMap<>();
        Map<String, Integer> lateInclude = new HashMap<>();
        for (Use u : uses) {
            boolean ok = false;
            int late = -1;
            for (int k = 0; k < incNames.size(); k++) {
                if (u.lib().headers().contains(incNames.get(k))) {
                    if (incIdx.get(k) < u.index()) { ok = true; break; }
                    if (late < 0) late = t.get(incIdx.get(k)).line();
                }
            }
            if (ok) continue;
            missing.computeIfAbsent(u.lib().suggested(), k -> new ArrayList<>()).add(u);
            if (late >= 0) lateInclude.putIfAbsent(u.lib().suggested(), late);
        }
        int lastIncludeLine = incIdx.isEmpty() ? -1 : t.get(incIdx.get(incIdx.size() - 1)).line();
        for (Map.Entry<String, List<Use>> e : missing.entrySet()) {
            String header = e.getKey();
            List<Use> list = e.getValue();
            Token first = t.get(list.get(0).index());
            Set<String> names = new LinkedHashSet<>();
            for (Use u : list) names.add(u.lib().name());
            String shown = String.join(", ", names.stream().limit(4).toList()) + (names.size() > 4 ? ", …" : "");
            Lib firstLib = list.get(0).lib();
            boolean cStyle = !firstLib.inStd() && firstLib.headers().stream().anyMatch(h -> h.endsWith(".h"));
            String alt = cStyle ? " (or <" + firstLib.headers().stream().filter(h -> h.endsWith(".h")).findFirst().orElse(header) + "> in C style)" : "";
            ErrorReporter.Builder b = rep.report(1, ErrorCategory.MISSING_HEADER)
                    .title("Missing header <" + header + ">").at(first).unexpected(first)
                    .expected("#include <" + header + ">")
                    .explain("'" + first.text() + "' is used on line " + first.line() + ", but " + (lateInclude.containsKey(header)
                            ? "<" + header + "> is only included on line " + lateInclude.get(header) + ", after that first use."
                            : "no #include before it provides it. Names such as " + shown + " are declared in <" + header + ">."))
                    .suggest("Add #include <" + header + ">" + alt + " at the top of the file, before the code that uses " + "'" + first.text() + "'.")
                    .state("global parsing: checking #include directives against the names used in the program");
            if (lastIncludeLine > 0) b.related(lastIncludeLine);
            b.commit();
            summary.add("Missing header (line " + first.line() + "): <" + header + "> needed for " + shown);
        }
    }

    // ---------------------------------------------------------------- namespace

    private void checkNamespace(List<Use> uses, List<Integer> incIdx, int usingNamespaceStd, Map<String, Integer> usingNames, List<String> summary) {
        List<Use> bad = new ArrayList<>();
        for (Use u : uses) {
            if (!u.lib().inStd() || u.qualified()) continue;
            if (usingNamespaceStd >= 0 && usingNamespaceStd < u.index()) continue;
            Integer decl = usingNames.get(u.lib().name());
            if (decl != null && decl < u.index()) continue;
            bad.add(u);
        }
        if (bad.isEmpty()) return;
        Token first = t.get(bad.get(0).index());
        Set<String> names = new LinkedHashSet<>();
        for (Use u : bad) names.add(u.lib().name());
        String others = names.size() > 1
                ? " Also affected: " + String.join(", ", names.stream().skip(1).limit(5).toList()) + (names.size() > 6 ? ", …" : "") + "."
                : "";
        int lastIncludeLine = incIdx.isEmpty() ? -1 : t.get(incIdx.get(incIdx.size() - 1)).line();
        ErrorReporter.Builder b = rep.report(1, ErrorCategory.MISSING_NAMESPACE)
                .title("Missing 'using namespace std;'").at(first).unexpected(first)
                .expected("'using namespace std;' or the 'std::' prefix")
                .explain("'" + first.text() + "' belongs to the std namespace, but there is no 'using namespace std;' before line " + first.line()
                        + " and it is not written as 'std::" + first.text() + "'." + others)
                .suggest("Add 'using namespace std;' after the #include lines, or write std::" + first.text() + " instead.")
                .state("global parsing: checking namespace use against the names used in the program");
        if (lastIncludeLine > 0) b.related(lastIncludeLine);
        b.commit();
        summary.add("Missing namespace (line " + first.line() + "): " + String.join(", ", names.stream().limit(6).toList())
                + " used without 'using namespace std;' or 'std::'");
    }

    // ------------------------------------------------------------------ helpers

    /** Tokens in function headers/bodies and global declarations. */
    private boolean[] scope() {
        boolean[] s = new boolean[t.size()];
        for (FunctionInfo f : functions) {
            int to = f.definition ? Math.max(f.bodyEnd, f.headerEnd) : f.headerEnd;
            for (int i = Math.max(0, f.headerStart); i < Math.min(to, s.length); i++) s[i] = true;
        }
        for (StatementChunk c : globalChunks) {
            for (int i = Math.max(0, c.start()); i < Math.min(c.end(), s.length); i++) s[i] = true;
        }
        return s;
    }

    /** Names the program declares itself (variables, parameters, functions, types): these are never library uses. */
    private Set<String> userDeclaredNames() {
        Set<String> names = new HashSet<>();
        for (int i = 1; i < t.size(); i++) {
            Token x = t.get(i);
            if (x.type() != TokenType.IDENTIFIER || !LIBS.containsKey(x.text())) continue;
            Token p = t.get(i - 1);
            Token n = i + 1 < t.size() ? t.get(i + 1) : null;
            boolean declaredAfterType = Keywords.isType(p) || p.is("struct") || p.is("class") || p.is("enum") || p.is("typedef")
                    || p.type() == TokenType.IDENTIFIER && !p.is("std") && !p.is("namespace");
            boolean afterTemplate = p.is(">") && n != null && (n.is(";") || n.is("=") || n.is(",") || n.is(")") || n.is("[") || n.is("{"));
            boolean afterRef = (p.is("&") || p.is("*")) && i >= 2 && (Keywords.isType(t.get(i - 2)) || t.get(i - 2).type() == TokenType.IDENTIFIER)
                    && n != null && (n.is(";") || n.is("=") || n.is(",") || n.is(")") || n.is("[") || n.is("("));
            if (declaredAfterType || afterTemplate || afterRef) names.add(x.text());
        }
        return names;
    }
}
