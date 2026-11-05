package com.gle.service;

import com.gle.compiler.GleCompiler;
import com.gle.dto.CompileResponse;
import org.springframework.stereotype.Service;

/** Thin service layer: validates input and delegates to the Spring-independent compiler engine. */
@Service
public class CompilerService {
    static final int MAX_SOURCE_LENGTH = 200_000;

    private final GleCompiler compiler = new GleCompiler();

    public CompileResponse compile(String sourceCode) {
        if (sourceCode == null || sourceCode.isBlank()) return CompileResponse.rejected("Source code is empty.");
        if (sourceCode.length() > MAX_SOURCE_LENGTH) return CompileResponse.rejected("Source code is too large (limit " + MAX_SOURCE_LENGTH + " characters).");
        return CompileResponse.from(compiler.compile(sourceCode));
    }
}
