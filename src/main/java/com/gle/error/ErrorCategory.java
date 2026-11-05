package com.gle.error;

public enum ErrorCategory {
    LEXICAL_ERROR("Lexical error"),
    MISSING_SEMICOLON("Missing semicolon"),
    MISSING_PAREN("Missing parenthesis"),
    MISSING_BRACE("Missing brace"),
    UNEXPECTED_TOKEN("Unexpected token"),
    INVALID_FUNCTION("Invalid function structure"),
    INVALID_CONTROL("Invalid control structure"),
    INVALID_FOR_HEADER("Invalid for-loop header"),
    INVALID_DECLARATION("Invalid declaration"),
    INVALID_EXPRESSION("Invalid expression"),
    MISPLACED_CODE("Code outside a function"),
    MISSING_HEADER("Missing header"),
    MISSING_NAMESPACE("Missing namespace"),
    INVALID_RETURN("Invalid return statement");

    private final String title;
    ErrorCategory(String title) { this.title = title; }
    public String title() { return title; }
}
