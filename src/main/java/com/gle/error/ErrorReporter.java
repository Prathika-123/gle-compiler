package com.gle.error;

import com.gle.lexer.Token;

import java.util.*;

/** Collects errors from all phases, removes duplicates and caps the total. */
public final class ErrorReporter {
    public static final int MAX_ERRORS = 50;
    private final List<SyntaxError> errors = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();

    public Builder report(int phase, ErrorCategory category) { return new Builder(phase, category); }

    public List<SyntaxError> sorted() {
        List<SyntaxError> copy = new ArrayList<>(errors);
        copy.sort(Comparator.comparingInt(SyntaxError::line)
                .thenComparingInt(SyntaxError::column)
                .thenComparingInt(SyntaxError::phase));
        return copy;
    }

    public int countPhase(int phase) {
        int n = 0;
        for (SyntaxError e : errors) if (e.phase() == phase) n++;
        return n;
    }

    public int count() { return errors.size(); }

    public final class Builder {
        private final int phase;
        private final ErrorCategory category;
        private String title;
        private int line = 1, column = 1;
        private String unexpected = "", expected = "", explanation = "", suggestion = "", state = "";
        private Integer related;

        private Builder(int phase, ErrorCategory category) { this.phase = phase; this.category = category; }

        public Builder title(String t) { this.title = t; return this; }
        public Builder at(Token t) { this.line = t.line(); this.column = t.column(); return this; }
        public Builder unexpected(Token t) { this.unexpected = t.display(); return this; }
        public Builder unexpected(String s) { this.unexpected = s; return this; }
        public Builder expected(String s) { this.expected = s; return this; }
        public Builder explain(String s) { this.explanation = s; return this; }
        public Builder suggest(String s) { this.suggestion = s; return this; }
        public Builder related(int line) { this.related = line; return this; }
        public Builder state(String s) { this.state = s; return this; }

        public void commit() {
            String t = title == null ? category.title() : title;
            String key = line + ":" + column + ":" + category + ":" + t;
            if (errors.size() >= MAX_ERRORS || !seen.add(key)) return;
            Integer rel = (related != null && related == line) ? null : related;
            errors.add(new SyntaxError(category.name(), t, phase, line, column, unexpected, expected,
                    explanation, suggestion, rel, state));
        }
    }
}
