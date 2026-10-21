package com.veriqra.app.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads a local {@code .env} file into the environment.
 *
 * <p>Spring Boot reads OS environment variables and configuration files, but not the
 * {@code .env} convention that most hosting platforms use. This post processor closes
 * that gap: it reads {@code .env} from the working directory and adds it as the lowest
 * priority property source, so a real environment variable always wins and the file can
 * never override what the deployment configured.
 *
 * <p>The file is optional. When it is absent nothing happens.
 */
public class DotEnvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** The name of the property source that is added to the environment. */
    public static final String PROPERTY_SOURCE_NAME = "veriqra-dotenv";

    /** Overrides the location of the file. */
    private static final String LOCATION_VARIABLE = "VERIQRA_ENV_FILE";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path file = resolveFile();
        if (file == null) {
            return;
        }
        Map<String, Object> values = read(file);
        if (values.isEmpty()) {
            return;
        }
        MapPropertySource source = new MapPropertySource(PROPERTY_SOURCE_NAME, values);
        environment.getPropertySources().addLast(source);
        log.info("Loaded {} setting(s) from {}", values.size(), file.toAbsolutePath());
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DotEnvEnvironmentPostProcessor.class);

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /**
     * Resolves the file to read.
     *
     * @return the file, or {@code null} when there is nothing to read
     */
    private Path resolveFile() {
        String configured = System.getenv(LOCATION_VARIABLE);
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured);
            return Files.isRegularFile(path) ? path : null;
        }
        Path path = Path.of(".env");
        return Files.isRegularFile(path) ? path : null;
    }

    /**
     * Parses the file.
     *
     * <p>The format is one {@code KEY=VALUE} pair per line. Blank lines and lines that
     * start with {@code #} are ignored. A value may be wrapped in single or double
     * quotes, which are removed. Everything after the first equals sign belongs to the
     * value, so a password may contain one.
     */
    private Map<String, Object> read(Path file) {
        Map<String, Object> values = new LinkedHashMap<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return values;
        }
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && trimmed.charAt(0) == '\uFEFF') {
                // A file written with a byte order mark starts with an invisible
                // character, which would otherwise become part of the first key.
                trimmed = trimmed.substring(1).trim();
            }
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (trimmed.startsWith("export ")) {
                trimmed = trimmed.substring("export ".length()).trim();
            }
            int separator = trimmed.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = trimmed.substring(0, separator).trim();
            String value = trimmed.substring(separator + 1).trim();
            if (key.isEmpty()) {
                continue;
            }
            values.put(key, unquote(value));
        }
        return values;
    }

    private String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
