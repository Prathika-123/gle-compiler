package com.gle.dto;

/** Request body of POST /api/compiler/compile. */
public record CompileRequest(String sourceCode) {
}
