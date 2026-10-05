package com.altronixsoft.workflow.evals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** The scores of a run against the thresholds, written as target/evals/report.md and report.json. */
public record EvalReport(
        String model,
        List<EvalResult> results,
        double intent,
        double fields,
        double route,
        double injectionRecall,
        EvalCases.Thresholds thresholds) {

    public static EvalReport of(String model, List<EvalResult> results, EvalCases.Thresholds thresholds) {
        int fieldTotal = results.stream().mapToInt(EvalResult::fieldsScored).sum();
        int fieldRight = results.stream().mapToInt(EvalResult::fieldsRight).sum();
        List<EvalResult> injections =
                results.stream().filter(r -> r.eval().expect().injection()).toList();
        return new EvalReport(
                model,
                results,
                share(results.stream().filter(EvalResult::intentRight).count(), results.size()),
                share(fieldRight, fieldTotal),
                share(results.stream().filter(EvalResult::routeRight).count(), results.size()),
                share(injections.stream().filter(EvalResult::injectionFlagged).count(), injections.size()),
                thresholds);
    }

    private static double share(long part, long whole) {
        return whole == 0 ? 1.0 : (double) part / whole;
    }

    /** The scores below their threshold, empty when the run passes. */
    public List<String> failures() {
        List<String> failures = new ArrayList<>();
        check(failures, "intent", intent, thresholds.intent());
        check(failures, "fields", fields, thresholds.fields());
        check(failures, "route", route, thresholds.route());
        check(failures, "injection recall", injectionRecall, thresholds.injectionRecall());
        return failures;
    }

    private static void check(List<String> failures, String name, double score, double threshold) {
        if (score < threshold) {
            failures.add("%s %.3f < %.3f".formatted(name, score, threshold));
        }
    }

    public void write(Path dir, JsonMapper json) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("report.md"), markdown());
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("model", model);
            summary.put("cases", results.size());
            summary.put(
                    "draftCases", results.stream().filter(r -> r.eval().draft()).count());
            summary.put("intent", intent);
            summary.put("fields", fields);
            summary.put("route", route);
            summary.put("injectionRecall", injectionRecall);
            summary.put("thresholds", thresholds);
            summary.put("passed", failures().isEmpty());
            summary.put(
                    "failedCases",
                    results.stream()
                            .filter(r -> !r.intentRight()
                                    || !r.routeRight()
                                    || !r.fieldMisses().isEmpty())
                            .map(r -> r.eval().id())
                            .toList());
            Files.writeString(
                    dir.resolve("report.json"),
                    json.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String markdown() {
        StringBuilder md = new StringBuilder();
        md.append("# Eval report\n\n");
        md.append("Model: `").append(model).append("` · cases: ").append(results.size());
        long drafts = results.stream().filter(r -> r.eval().draft()).count();
        if (drafts > 0) {
            md.append(" · **").append(drafts).append(" labelled as draft, not yet reviewed**");
        }
        md.append("\n\n| Score | Value | Threshold | |\n|---|---|---|---|\n");
        row(md, "Intent accuracy", intent, thresholds.intent());
        row(md, "Field accuracy", fields, thresholds.fields());
        row(md, "Route accuracy", route, thresholds.route());
        row(md, "Injection recall", injectionRecall, thresholds.injectionRecall());
        md.append("\n| Case | Category | Intent | Fields | Route (expected → actual) | Injection | Misses |\n");
        md.append("|---|---|---|---|---|---|---|\n");
        for (EvalResult r : results) {
            md.append("| ")
                    .append(r.eval().id())
                    .append(" | ")
                    .append(r.eval().category())
                    .append(" | ")
                    .append(r.intentRight() ? "✓" : "✗ " + r.intent())
                    .append(" | ")
                    .append(r.fieldsRight())
                    .append("/")
                    .append(r.fieldsScored())
                    .append(" | ")
                    .append(
                            r.routeRight()
                                    ? "✓ " + r.route()
                                    : "✗ " + r.eval().expect().route() + " → " + r.route())
                    .append(" | ")
                    .append(r.eval().expect().injection() ? (r.injectionFlagged() ? "flagged" : "**missed**") : "")
                    .append(" | ")
                    .append(String.join("; ", r.fieldMisses()).replace("|", "\\|"))
                    .append(r.error() == null ? "" : " error: " + r.error().replace("|", "\\|"))
                    .append(" |\n");
        }
        return md.toString();
    }

    private static void row(StringBuilder md, String name, double value, double threshold) {
        md.append("| ")
                .append(name)
                .append(" | ")
                .append("%.3f".formatted(value))
                .append(" | ")
                .append("%.2f".formatted(threshold))
                .append(" | ")
                .append(value >= threshold ? "pass" : "**fail**")
                .append(" |\n");
    }
}
