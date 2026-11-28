package hr.brendigo.fiskai.room;

import java.util.function.Consumer;

public final class LegalDecisionSchema {
    private LegalDecisionSchema(){}
    public static void create(Consumer<String> exec) {
        exec.accept("CREATE TABLE IF NOT EXISTS legal_decision_ledger (receiptId TEXT NOT NULL PRIMARY KEY,payload TEXT NOT NULL,FOREIGN KEY(receiptId) REFERENCES demo_payment_preparation(receiptId) ON UPDATE RESTRICT ON DELETE RESTRICT)");
        exec.accept("CREATE TRIGGER IF NOT EXISTS legal_decision_no_update BEFORE UPDATE ON legal_decision_ledger BEGIN SELECT RAISE(ABORT,'Immutable legal decision'); END");
        exec.accept("CREATE TRIGGER IF NOT EXISTS legal_decision_no_delete BEFORE DELETE ON legal_decision_ledger BEGIN SELECT RAISE(ABORT,'Immutable legal decision'); END");
        exec.accept("CREATE TRIGGER IF NOT EXISTS legal_decision_no_replace BEFORE INSERT ON legal_decision_ledger WHEN EXISTS(SELECT 1 FROM legal_decision_ledger WHERE receiptId=NEW.receiptId) BEGIN SELECT RAISE(ABORT,'Immutable legal decision'); END");
    }
}
