package hr.brendigo.fiskai.room;
import java.util.function.Consumer;

public final class IncomingSchema {
    private IncomingSchema(){}
    public static void create(Consumer<String> exec) {
        exec.accept("CREATE TABLE IF NOT EXISTS incoming_invoice (id TEXT NOT NULL PRIMARY KEY, payload TEXT NOT NULL)");
        exec.accept("CREATE TABLE IF NOT EXISTS incoming_cutover (id INTEGER NOT NULL PRIMARY KEY CHECK(id=1))");
    }
}
