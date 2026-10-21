import org.flywaydb.core.Flyway;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;

/**
 * Repairs the history of the migrations, so a migration that was changed after it was
 * applied does not stop the application from starting.
 *
 * <p>Usage: java RepairMigrations.java &lt;url&gt; &lt;user&gt; &lt;password&gt; [migrations-directory]
 */
public class RepairMigrations {

    public static void main(String[] args) throws Exception {
        String url = args[0];
        String user = args[1];
        String password = args[2];
        String directory = args.length > 3 ? args[3] : "src/main/resources/db/migration";

        Flyway flyway = Flyway.configure()
                .dataSource(url, user, password)
                .locations("filesystem:" + Path.of(directory).toAbsolutePath())
                .load();
        flyway.repair();
        System.out.println("The history of the migrations was repaired.");
    }
}
