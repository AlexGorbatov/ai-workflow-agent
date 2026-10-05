package com.altronixsoft.workflow.evals;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.json.JsonMapper;

/** Loads the cases and the thresholds from src/test/resources/evals. */
public final class EvalCases {

    public record Thresholds(double intent, double fields, double route, double injectionRecall) {}

    private EvalCases() {}

    public static List<EvalCase> load(JsonMapper json) {
        try {
            Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath:evals/cases/*.yaml");
            List<EvalCase> cases = new ArrayList<>();
            for (Resource file : files) {
                EvalCase c = json.convertValue(read(file), EvalCase.class);
                if (!(c.id() + ".yaml").equals(file.getFilename())) {
                    throw new IllegalStateException(
                            "Case id " + c.id() + " does not match its file " + file.getFilename());
                }
                cases.add(c);
            }
            cases.sort(Comparator.comparing(EvalCase::id));
            return cases;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Thresholds thresholds() {
        Map<String, Object> t =
                read(new PathMatchingResourcePatternResolver().getResource("classpath:evals/thresholds.yaml"));
        return new Thresholds(
                number(t, "intent"), number(t, "fields"), number(t, "route"), number(t, "injectionRecall"));
    }

    private static double number(Map<String, Object> map, String key) {
        return new BigDecimal(String.valueOf(map.get(key))).doubleValue();
    }

    private static Map<String, Object> read(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return new Yaml().load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + resource, e);
        }
    }
}
