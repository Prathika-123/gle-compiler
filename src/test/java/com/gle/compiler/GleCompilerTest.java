package com.gle.compiler;

import com.gle.error.SyntaxError;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GleCompilerTest {
    private final GleCompiler compiler = new GleCompiler();

    private List<SyntaxError> errors(String code) { return compiler.compile(code).errors(); }

    @Test
    void validProgramHasNoErrors() {
        String code = "#include <iostream>\nusing namespace std;\nint main() {\n int x = 1;\n if (x > 0) { cout << x << endl; }\n for (int i = 0; i < 3; i++) x += i;\n return 0;\n}\n";
        assertTrue(errors(code).isEmpty());
    }

    @Test
    void missingSemicolonIsReportedAtNextToken() {
        List<SyntaxError> e = errors("int main() {\nint a = 10\nreturn 0;\n}\n");
        assertEquals(1, e.size());
        assertEquals("MISSING_SEMICOLON", e.get(0).category());
        assertEquals(3, e.get(0).line());
        assertEquals("return", e.get(0).unexpectedToken());
        assertEquals(Integer.valueOf(2), e.get(0).relatedLine());
        assertTrue(e.get(0).suggestion().contains("int a = 10"));
    }

    @Test
    void commasInForHeaderAreReportedOnce() {
        List<SyntaxError> e = errors("int main() {\nint x, y;\nfor (x = 1, y < 10, x++)\n  y = 1;\n}\n");
        assertEquals(1, e.size());
        assertEquals("INVALID_FOR_HEADER", e.get(0).category());
        assertEquals(2, e.get(0).phase());
    }

    @Test
    void conditionClosedTooEarly() {
        List<SyntaxError> e = errors("int main() {\nint a, b;\nif (a * b <= 200) || (b < 200)\n  a = 1;\n}\n");
        assertEquals(1, e.size());
        assertEquals("INVALID_CONTROL", e.get(0).category());
        assertEquals("||", e.get(0).unexpectedToken());
    }

    @Test
    void unclosedFunctionReportedByPhaseOne() {
        List<SyntaxError> e = errors("int f(int x) {\n if (x > 1) {\n  return x;\n }\n");
        assertEquals(1, e.size());
        assertEquals(1, e.get(0).phase());
        assertEquals("MISSING_BRACE", e.get(0).category());
    }

    @Test
    void codeAfterEarlyClosingBraceIsOneError() {
        List<SyntaxError> e = errors("int main() {\n int x = 1;\n for (x = 1; x < 5; x++)\n}\n cout << x << endl;\n}\n}\n");
        long outside = e.stream().filter(x -> x.category().equals("MISPLACED_CODE")).count();
        assertEquals(1, outside);
        assertEquals(2, e.size());
    }

    @Test
    void missingParenInIfHeaderDoesNotCascade() {
        List<SyntaxError> e = errors("int main() {\n int x = 3;\n if (x > 2 {\n  x = 1;\n }\n}\n");
        assertEquals(1, e.size());
        assertEquals("MISSING_PAREN", e.get(0).category());
        assertEquals(3, e.get(0).line());
    }

    @Test
    void everyErrorHasAllReportFields() {
        for (SyntaxError e : errors("int main() {\n int x = ;\n x = (1 + 2;\n}\n")) {
            assertFalse(e.title().isBlank());
            assertTrue(e.line() > 0 && e.column() > 0);
            assertFalse(e.unexpectedToken().isBlank());
            assertFalse(e.expected().isBlank());
            assertFalse(e.explanation().isBlank());
            assertFalse(e.suggestion().isBlank());
        }
    }

    @Test
    void missingHeaderAndNamespaceAreBothReported() {
        List<SyntaxError> e = errors("int main() {\n int x = 1;\n cout << x << endl;\n return 0;\n}\n");
        assertEquals(2, e.size());
        assertTrue(e.stream().anyMatch(x -> x.category().equals("MISSING_HEADER") && x.expected().contains("<iostream>")));
        assertTrue(e.stream().anyMatch(x -> x.category().equals("MISSING_NAMESPACE")));
        assertTrue(e.stream().allMatch(x -> x.phase() == 1 && x.line() == 3));
    }

    @Test
    void missingNamespaceOnlyWhenHeaderIsPresent() {
        List<SyntaxError> e = errors("#include <iostream>\nint main() {\n cout << 1;\n return 0;\n}\n");
        assertEquals(1, e.size());
        assertEquals("MISSING_NAMESPACE", e.get(0).category());
        assertEquals(Integer.valueOf(1), e.get(0).relatedLine());
    }

    @Test
    void stdPrefixOrUsingDeclarationNeedsNoNamespace() {
        assertTrue(errors("#include <iostream>\nint main() {\n std::cout << 1 << std::endl;\n return 0;\n}\n").isEmpty());
        assertTrue(errors("#include <iostream>\nusing std::cout;\nint main() {\n cout << 1;\n return 0;\n}\n").isEmpty());
    }

    @Test
    void missingHeaderForCLibraryFunctionNeedsNoNamespace() {
        List<SyntaxError> e = errors("int main() {\n double r = sqrt(4.0);\n return 0;\n}\n");
        assertEquals(1, e.size());
        assertEquals("MISSING_HEADER", e.get(0).category());
        assertTrue(e.get(0).expected().contains("<cmath>"));
    }

    @Test
    void namesDeclaredByTheProgramAreNotLibraryUses() {
        assertTrue(errors("int main() {\n int max = 3;\n int time = max + 1;\n return time;\n}\n").isEmpty());
    }

    @Test
    void includeAfterFirstUseIsStillMissing() {
        List<SyntaxError> e = errors("using namespace std;\nint main() {\n cout << 1;\n return 0;\n}\n#include <iostream>\n");
        assertEquals(1, e.size());
        assertEquals("MISSING_HEADER", e.get(0).category());
        assertTrue(e.get(0).explanation().contains("line 6"));
    }
}
