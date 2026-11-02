import java.io.*;
import java.nio.file.*;
import java.util.*;

/** User-run input checks only: no visibility queries or sunshine integration. */
public final class ProjectedInputChecks {
    static void accepted(Path config, String crs, PrintWriter log) throws IOException {
        ProjectedBaselineExperiment.Inputs in = new ProjectedBaselineExperiment.Inputs(config);
        if (!crs.equals(in.p.getProperty("projected_crs")) || in.receivers.size() != 16384)
            throw new AssertionError("unexpected input CRS or receiver count");
        report(log, "ACCEPTED crs=" + crs + " pixels=" + in.receivers.size());
    }

    static void rejected(Path plainConfig, String crs, String expected, Path output, PrintWriter log)
            throws IOException {
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(plainConfig)) { p.load(reader); }
        // The test copy lives elsewhere; keep its data paths pointing to the original inputs.
        for (String key : List.of("dem", "receivers", "metadata"))
            p.setProperty(key, plainConfig.getParent().resolve(p.getProperty(key)).normalize().toString());
        p.setProperty("projected_crs", crs);
        Path copy = output.resolve("invalid_" + crs.substring(5) + ".properties");
        try (Writer writer = Files.newBufferedWriter(copy)) { p.store(writer, "Input rejection case only"); }
        try {
            new ProjectedBaselineExperiment.Inputs(copy);
        } catch (IllegalArgumentException e) {
            if (!expected.equals(e.getMessage())) throw new AssertionError("wrong rejection reason", e);
            report(log, "EXPECTED_REJECTION crs=" + crs + " reason=" + e.getMessage());
            return;
        }
        throw new AssertionError("invalid projection was accepted: " + crs);
    }

    static void report(PrintWriter log, String line) {
        System.out.println(line); log.println(line); log.flush();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3)
            throw new IllegalArgumentException("qinling-config plain-config new-output-directory");
        Path qinling = Path.of(args[0]).toAbsolutePath().normalize();
        Path plain = Path.of(args[1]).toAbsolutePath().normalize();
        Path output = Path.of(args[2]).toAbsolutePath().normalize();
        Path allowed = Path.of("user_runs").toAbsolutePath().normalize();
        if (!output.startsWith(allowed) || output.equals(allowed))
            throw new IllegalArgumentException("output must be a new user_runs subdirectory");
        Files.createDirectories(output.getParent());
        Files.createDirectory(output);
        Files.writeString(output.resolve("input_sources.txt"), "qinling=" + qinling + "\nplain=" + plain + "\n");
        try (PrintWriter log = new PrintWriter(Files.newBufferedWriter(output.resolve("checks.log")))) {
            accepted(qinling, "EPSG:32649", log);
            accepted(plain, "EPSG:32650", log);
            rejected(plain, "EPSG:4326", "expected prepared UTM49N/50N metre grid", output, log);
            rejected(plain, "EPSG:32649", "projected CRS differs from preparation metadata", output, log);
            report(log, "PROJECTED_INPUT_CHECKS_PASS checks=4 no_sunshine_computation");
        }
    }
}
