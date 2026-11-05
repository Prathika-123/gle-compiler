# GLE Educational Compiler

An educational C++-like compiler front end that demonstrates the **GLE (global, local, expression-level) parsing technique** from
*"A Parsing Technique for Enhancing Compiler Syntax Error Messages for Student Programmers"* (Algaraibeh, Jeffery, Soule, Dousay – IEEE FIE 2024).
Everything (lexer, parsers, ASTs, error reporting) is written from scratch in Java. No GCC/Clang is invoked.

## Run

Requirements: JDK 17+ and Maven.

```
mvn spring-boot:run          # then open http://localhost:8080
mvn test                     # engine unit tests
```

## API

`POST /api/compiler/compile` with `{ "sourceCode": "..." }` returns:
`success`, `status`, `message`, `errorCount`, `lineCount`, `lexical` (token count, stats, tokens),
`phases[3]` (status, summary lines, AST-A/B/C), and `errors[]`
(`category, title, phase, line, column, unexpectedToken, expected, explanation, suggestion, relatedLine, parserState`).

## Architecture

```
com.gle
├─ controller   CompilerController          REST endpoint
├─ service      CompilerService             input validation, delegates to engine
├─ dto          CompileRequest/Response     JSON contract
├─ compiler     GleCompiler (+ GleCli)      coordinates lexer + 3 phases; NO Spring dependency
├─ lexer        Lexer, Token, TokenType
├─ parser       GlobalParser     (Phase 1 -> AST-A)
│               LocalParser      (Phase 2 -> AST-B)
│               ExpressionLevelParser (Phase 3 -> AST-C)
│               LibraryChecker  (missing #include / missing `using namespace std;`, reported in Phase 1)
│               ReturnChecker   (does every `return` match the function's return type? reported in Phase 3)
├─ ast          AstNode
└─ error        SyntaxError, ErrorReporter, ErrorCategory
```

Quick engine check without Spring: `java com.gle.compiler.GleCli file.cpp --ast`.

### The three phases

| Phase | Looks at | Deliberately ignores | Output |
|---|---|---|---|
| 1 Global | directives, global declarations, function return type/name/parameters, `{ }` boundaries | everything inside bodies | AST-A: one node per function with header + body as unparsed text |
| 2 Local | `if`, `if-else`, `for`, `while`, `do-while`, `switch` inside bodies: parentheses, braces, header parts, bodies | contents of headers and plain statements | AST-B + a list of statement/header chunks |
| 3 Expression | each chunk: declarations, assignments, arithmetic/relational/logical expressions, calls, `return`, `cin`/`cout`/`printf`/`scanf` | – | AST-C |

Error recovery: Phase 1 recovers at function boundaries, Phase 2 at control-structure boundaries, Phase 3 at statement
boundaries. A missing `;` is reported and parsing continues as if it were present; other errors skip the rest of the chunk.
Errors are de-duplicated and an error in a header never re-appears as errors in its contents.

### Matching return statements

`ReturnChecker` runs with Phase 3 and compares each function definition's declared return type (recorded by Phase 1) with its
`return` statements. Problems are reported as category `INVALID_RETURN` (phase 3), with the usual explanation, suggestion and
related line (the function header):

| Mistake | Example | Reported at |
|---|---|---|
| A `void` function returns a value | `void f() { return x; }` | the returned value |
| A non-void function uses a bare `return;` | `int f() { return; }` | the `;` |
| Obviously wrong literal | `int f() { return "hi"; }`, `string f() { return 5; }` | the literal |
| Body never returns a value | `int add(int a, int b) { int s = a + b; }` | the closing `}` |
| Only some paths return | `int f(int x) { if (x > 0) { return 1; } }` | the closing `}` |

Deliberately conservative, so valid code is not flagged: `main` may omit its `return` (C++ returns 0); `while (true)`, `for (;;)`,
`do ... while (1)`, `switch`, `try`, `throw` and `exit()/abort()` count as "does not fall off the end"; only a *single literal*
return value is type-judged (`return a + b;` is never questioned); `auto`, pointer-returning and unknown user types are not
type-judged; lambda bodies are ignored; and the *every path returns* analysis is skipped for a function that already has another
reported error (one slip, one message). Each checked function also gets a `RETURN_CHECK` node in AST-C and a summary line.

### Missing header / missing namespace checks

`LibraryChecker` reports two common beginner mistakes as Phase 1 errors (`MISSING_HEADER`, `MISSING_NAMESPACE`):

* **Missing header** – a known library name (`cout`, `string`, `vector`, `sqrt`, `printf`, `sort`, `rand`, `strlen`, …) is used
  before any matching `#include`. One error per missing header, at the first use, e.g. `Missing header <iostream>`.
* **Missing namespace** – a `std` name (`cout`, `cin`, `endl`, `string`, containers, `sort`, …) is used without `std::` and
  without a preceding `using namespace std;` / `using std::name;`. One error per program, at the first use.

Notes: only code inside functions and global declarations is inspected; names the program declares itself (e.g. a variable called
`max`) are ignored; `#include "file.h"` disables the missing-header check (a local header may provide anything);
`<iostream>`/`<sstream>`/`<fstream>` are accepted for `string`; `<bits/stdc++.h>` satisfies every header.

## Deviations from the paper (mention these in your report)

* The paper generates each phase's parser with Yacc/Bison and maps LR parse states to messages with **Merr**. This project
  uses **hand-written recursive-descent parsers** instead. The same idea is kept: messages come from *where the parser is*
  (e.g. "in a for header, expecting the first ';'") plus the unexpected token, not from a list of known bad programs.
  Each error carries a `parserState` string showing that context.
* The paper's three phases re-lex the source with phase-specific token sets; here one token stream is shared, and each phase
  reads only its own token ranges (same effect, simpler).
* Phase 1 in the paper treats the function body as an opaque string. Here it is a token range, and an unclosed function
  body is reported by Phase 1 (as a function-boundary error).

## Supported language subset

Types (`int float double char bool void long short unsigned signed const static auto`, and `Name`/`std::name` style user types),
global variables, functions with parameters, `if/else if/else`, `for`, `while`, `do-while`, `switch/case/default`,
declarations with arrays/initializers, assignments (incl. compound), arithmetic/relational/logical/bitwise operators, `?:`, casts,
calls, `return/break/continue`, `cout <<`, `cin >>`, `printf/scanf`. Not supported: templates (`vector<int>`), classes/structs
(skipped with a note), range-for contents, lambdas, preprocessor macro expansion. Semantic checks (undeclared variables, general type
errors) are out of scope: this project is about **syntax** error messages. The one semantic-flavoured exception is the
matching-return check above, because a missing or mismatched `return` is a very common beginner mistake.

## Frontend

Plain HTML/CSS/JS in `src/main/resources/static` (no framework, no database). Ctrl+Enter compiles; clicking an error, an AST
node or a token highlights its source line.
