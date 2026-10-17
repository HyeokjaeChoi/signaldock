package local.signaldock.server.codegen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.Configuration;
import org.jooq.meta.jaxb.Database;
import org.jooq.meta.jaxb.Generate;
import org.jooq.meta.jaxb.Generator;
import org.jooq.meta.jaxb.Jdbc;
import org.jooq.meta.jaxb.Target;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

public final class JooqCodegen {
    private static final String IMAGE = "postgres:17.6-alpine@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94";

    private JooqCodegen() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) throw new IllegalArgumentException("output [expected]");
        Path output = Path.of(args[0]);
        recreate(output);
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(
                DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))) {
            postgres.start();
            Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("filesystem:src/main/resources/db/migration")
                .load().migrate();
            GenerationTool.generate(new Configuration()
                .withJdbc(new Jdbc().withDriver("org.postgresql.Driver").withUrl(postgres.getJdbcUrl())
                    .withUser(postgres.getUsername()).withPassword(postgres.getPassword()))
                .withGenerator(new Generator()
                    .withName("org.jooq.codegen.JavaGenerator")
                    .withDatabase(new Database().withName("org.jooq.meta.postgres.PostgresDatabase")
                        .withInputSchema("public").withIncludes("events|client_reports"))
                    .withGenerate(new Generate().withGeneratedAnnotation(false).withJavadoc(false).withComments(false))
                    .withTarget(new Target().withPackageName("local.signaldock.server.jooq")
                        .withDirectory(output.toString()).withClean(true))));
        }
        if (args.length == 2) compare(output, Path.of(args[1]));
    }

    private static void recreate(Path directory) throws IOException {
        if (Files.exists(directory)) try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.delete(path); } catch (IOException e) { throw new RuntimeException(e); }
            });
        }
        Files.createDirectories(directory);
    }

    private static void compare(Path actual, Path expected) throws IOException {
        List<String> actualFiles = files(actual);
        List<String> expectedFiles = files(expected);
        if (!actualFiles.equals(expectedFiles)) throw new IllegalStateException("jOOQ file set drift: " + actualFiles + " != " + expectedFiles);
        for (String name : actualFiles) {
            if (Files.mismatch(actual.resolve(name), expected.resolve(name)) != -1) {
                throw new IllegalStateException("jOOQ source drift: " + name);
            }
        }
    }

    private static List<String> files(Path root) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).map(root::relativize).map(Path::toString).sorted().toList();
        }
    }
}
