package hr.brendigo.fiskai.room;
import java.util.function.Consumer;
public final class ReceiptSchema {
    private ReceiptSchema(){}
    public static void create(Consumer<String> exec) {
        exec.accept("CREATE TABLE IF NOT EXISTS bank_transaction (id TEXT NOT NULL PRIMARY KEY,payload TEXT NOT NULL)");
        exec.accept("CREATE TABLE IF NOT EXISTS receipt_event (id TEXT NOT NULL PRIMARY KEY,bankId TEXT,payload TEXT NOT NULL,FOREIGN KEY(bankId) REFERENCES bank_transaction(id) ON UPDATE RESTRICT ON DELETE RESTRICT)");
        exec.accept("CREATE UNIQUE INDEX IF NOT EXISTS index_receipt_event_bankId ON receipt_event(bankId)");
        for(String table:new String[]{"bank_transaction","receipt_event"}) {
            exec.accept("CREATE TRIGGER IF NOT EXISTS "+table+"_no_update BEFORE UPDATE ON "+table+" BEGIN SELECT RAISE(ABORT,'Immutable evidence'); END");
            exec.accept("CREATE TRIGGER IF NOT EXISTS "+table+"_no_delete BEFORE DELETE ON "+table+" BEGIN SELECT RAISE(ABORT,'Immutable evidence'); END");
            String conflict=table.equals("receipt_event")?" OR (NEW.bankId IS NOT NULL AND bankId=NEW.bankId)":"";
            exec.accept("CREATE TRIGGER IF NOT EXISTS "+table+"_no_replace BEFORE INSERT ON "+table+" WHEN EXISTS(SELECT 1 FROM "+table+" WHERE id=NEW.id"+conflict+") BEGIN SELECT RAISE(ABORT,'Duplicate evidence'); END");
        }
    }
}
