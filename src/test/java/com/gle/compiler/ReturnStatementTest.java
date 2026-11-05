package com.gle.compiler;

import com.gle.error.SyntaxError;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the matching-return check: every return statement must fit the function's declared return type. */
class ReturnStatementTest {
    private final GleCompiler compiler = new GleCompiler();

    private List<SyntaxError> errors(String code) { return compiler.compile(code).errors(); }

    private SyntaxError only(String code) {
        List<SyntaxError> e = errors(code);
        assertEquals(1, e.size(), "expected exactly one error but got " + e);
        return e.get(0);
    }

    // ------------------------------------------------------------------ valid code must stay clean
    @Test
    void validReturnPatternsAreAccepted() {
        String code = "#include <string>\n#include <cstdlib>\nusing namespace std;\n"
                + "int add(int a, int b) { return a + b; }\n"
                + "void hello() { int x = 1; return; }\n"
                + "void bye() { int y = 2; }\n"
                + "int sign(int x) {\n  if (x > 0) { return 1; } else if (x < 0) { return -1; } else { return 0; }\n}\n"
                + "int find(int n) {\n  int i = 0;\n  while (i < 10) {\n    if (i == n) { return i; }\n    i++;\n  }\n  return -1;\n}\n"
                + "int forever() { while (true) { int a = 1; } }\n"
                + "int loop2() { for (;;) { } }\n"
                + "int quit() { exit(1); }\n"
                + "int pick(int x) {\n  switch (x) {\n    case 1: return 10;\n    default: return 0;\n  }\n}\n"
                + "double half(int x) { return x / 2.0; }\n"
                + "float one() { return 1; }\n"
                + "char grade() { return 'A'; }\n"
                + "bool yes() { return true; }\n"
                + "string name() { return \"bob\"; }\n"
                + "const char* msg() { return \"hello\"; }\n"
                + "int neg() { return -5; }\n"
                + "int f(int);\n"
                + "int main() { return add(1, 2); }\n";
        assertTrue(errors(code).isEmpty(), "unexpected errors: " + errors(code));
    }

    @Test
    void mainMayOmitItsReturn() {
        assertTrue(errors("#include <iostream>\nusing namespace std;\nint main() {\n cout << \"hi\" << endl;\n}\n").isEmpty());
    }

    // ------------------------------------------------------------------ individual return statements
    @Test
    void voidFunctionCannotReturnAValue() {
        SyntaxError e = only("void show(int x) {\n    return x;\n}\nint main() { return 0; }\n");
        assertEquals("INVALID_RETURN", e.category());
        assertEquals(3, e.phase());
        assertEquals(2, e.line());
        assertTrue(e.title().contains("Void function 'show' returns a value"));
        assertEquals(Integer.valueOf(1), e.relatedLine());
    }

    @Test
    void nonVoidFunctionCannotUseBareReturn() {
        SyntaxError e = only("int f(int x) {\n    if (x > 0) {\n        return;\n    }\n    return 1;\n}\nint main() { return 0; }\n");
        assertEquals("INVALID_RETURN", e.category());
        assertEquals(3, e.line());
        assertTrue(e.title().contains("'return' without a value in function 'f'"));
        assertTrue(e.suggestion().contains("return 0;"));
    }

    @Test
    void mainCannotUseBareReturnEither() {
        SyntaxError e = only("int main() {\n    return;\n}\n");
        assertTrue(e.title().contains("function 'main'"));
    }

    @Test
    void wrongLiteralTypeIsReported() {
        assertEquals(2, only("int f() {\n    return \"hello\";\n}\nint main() { return 0; }\n").line());
        assertEquals(2, only("double f() {\n    return \"1.5\";\n}\nint main() { return 0; }\n").line());
        assertEquals(4, only("#include <string>\nusing namespace std;\nstring f() {\n    return 5;\n}\nint main() { return 0; }\n").line());
        assertEquals(4, only("#include <string>\nusing namespace std;\nstring f() {\n    return 'a';\n}\nint main() { return 0; }\n").line());
    }

    // ------------------------------------------------------------------ every path returns
    @Test
    void functionWithoutAnyReturnIsReportedAtItsClosingBrace() {
        SyntaxError e = only("int add(int a, int b) {\n    int s = a + b;\n}\nint main() { return add(1, 2); }\n");
        assertEquals("INVALID_RETURN", e.category());
        assertEquals(3, e.line());
        assertEquals("}", e.unexpectedToken());
        assertTrue(e.title().contains("never returns a value"));
        assertEquals(Integer.valueOf(1), e.relatedLine());
    }

    @Test
    void emptyNonVoidFunctionIsReported() {
        assertTrue(only("int stub() {\n}\nint main() { return 0; }\n").title().contains("never returns a value"));
    }

    @Test
    void returnOnlyOnSomePathsIsReported() {
        SyntaxError e = only("int f(int x) {\n    if (x > 0) {\n        return 1;\n    }\n}\nint main() { return f(1); }\n");
        assertTrue(e.title().contains("Not every path in function 'f' returns a value"));
        assertEquals(5, e.line());
        assertEquals(Integer.valueOf(3), e.relatedLine());
        assertTrue(only("int g(int s) {\n if (s > 90) {\n  return 1;\n } else if (s > 50) {\n  return 2;\n }\n}\nint main() { return 0; }\n")
                .title().contains("Not every path"));
        assertTrue(only("int h(int n) {\n for (int i = 0; i < 10; i++) {\n  if (i == n) {\n   return i;\n  }\n }\n}\nint main() { return 0; }\n")
                .title().contains("Not every path"));
    }

    // ------------------------------------------------------------------ one slip, one message
    @Test
    void onlyTheBadFunctionIsFlagged() {
        SyntaxError e = only("int good(int a) { return a; }\nvoid bad() { return 1; }\nint alsoGood() { return 2; }\nint main() { return 0; }\n");
        assertEquals(2, e.line());
    }

    @Test
    void missingSemicolonInReturnDoesNotCascade() {
        SyntaxError e = only("int f(int x) {\n    return x\n}\nint main() { return f(1); }\n");
        assertEquals("MISSING_SEMICOLON", e.category());
    }

    @Test
    void otherSyntaxErrorInsideFunctionSuppressesThePathCheck() {
        SyntaxError e = only("int f(int x) {\n    int y = ;\n    if (x > 0) { return 1; }\n}\nint main() { return 0; }\n");
        assertEquals("INVALID_EXPRESSION", e.category());
    }

    @Test
    void unclosedFunctionOnlyGetsTheBraceError() {
        SyntaxError e = only("int f(int x) {\n    if (x > 1) {\n        return x;\n    }\n");
        assertEquals("MISSING_BRACE", e.category());
        assertEquals(1, e.phase());
    }

    // ------------------------------------------------------------------ report quality and AST
    @Test
    void everyReturnErrorHasAllReportFields() {
        for (String code : List.of(
                "void v() {\n return 1;\n}\nint main() { return 0; }\n",
                "int f() {\n return;\n}\nint main() { return 0; }\n",
                "int f() {\n}\nint main() { return 0; }\n",
                "int f(int x) {\n if (x) { return 1; }\n}\nint main() { return 0; }\n",
                "int f() {\n return \"a\";\n}\nint main() { return 0; }\n")) {
            SyntaxError e = only(code);
            assertFalse(e.explanation().isBlank());
            assertFalse(e.suggestion().isBlank());
            assertFalse(e.expected().isBlank());
            assertFalse(e.parserState().isBlank());
            assertNotNull(e.relatedLine());
        }
    }

    @Test
    void phaseThreeReportsTheReturnCheckPerFunction() {
        CompilationResult r = compiler.compile("int add(int a, int b) { return a + b; }\nvoid f() { return; }\nint main() { return 0; }\n");
        assertTrue(r.success());
        List<String> summary = r.phases().get(2).summary();
        assertEquals(3, summary.stream().filter(s -> s.startsWith("Return check")).count());
        assertTrue(r.phases().get(2).ast().getChildren().stream().anyMatch(n -> n.getKind().equals("RETURN_CHECK")));
    }
}
