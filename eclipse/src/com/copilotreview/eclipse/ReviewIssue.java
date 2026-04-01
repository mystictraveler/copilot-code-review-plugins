package com.copilotreview.eclipse;

public class ReviewIssue {
    public final int line;
    public final String severity;
    public final String message;

    public ReviewIssue(int line, String severity, String message) {
        this.line = line;
        this.severity = severity;
        this.message = message;
    }
}
