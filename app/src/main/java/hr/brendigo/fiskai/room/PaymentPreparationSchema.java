package hr.brendigo.fiskai.room;
import java.util.function.Consumer;
public final class PaymentPreparationSchema {
    private PaymentPreparationSchema(){}
    public static void create(Consumer<String> exec) {
        exec.accept("CREATE TABLE IF NOT EXISTS demo_payment_preparation (receiptId TEXT NOT NULL PRIMARY KEY,documentId TEXT NOT NULL,eventId TEXT NOT NULL,payload TEXT NOT NULL,FOREIGN KEY(receiptId) REFERENCES receipt_event(id) ON UPDATE RESTRICT ON DELETE RESTRICT,FOREIGN KEY(documentId) REFERENCES fiscal_document(id) ON UPDATE RESTRICT ON DELETE RESTRICT)");
        exec.accept("CREATE UNIQUE INDEX IF NOT EXISTS index_demo_payment_preparation_documentId ON demo_payment_preparation(documentId)");
        exec.accept("CREATE UNIQUE INDEX IF NOT EXISTS index_demo_payment_preparation_eventId ON demo_payment_preparation(eventId)");
        exec.accept("CREATE TRIGGER IF NOT EXISTS demo_payment_preparation_no_update BEFORE UPDATE ON demo_payment_preparation BEGIN SELECT RAISE(ABORT,'Immutable payment preparation'); END");
        exec.accept("CREATE TRIGGER IF NOT EXISTS demo_payment_preparation_no_delete BEFORE DELETE ON demo_payment_preparation BEGIN SELECT RAISE(ABORT,'Immutable payment preparation'); END");
        exec.accept("CREATE TRIGGER IF NOT EXISTS demo_payment_preparation_no_replace BEFORE INSERT ON demo_payment_preparation WHEN EXISTS(SELECT 1 FROM demo_payment_preparation WHERE receiptId=NEW.receiptId OR documentId=NEW.documentId OR eventId=NEW.eventId) BEGIN SELECT RAISE(ABORT,'Duplicate payment preparation'); END");
    }
}
