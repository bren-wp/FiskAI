package hr.brendigo.fiskai.room;

import java.util.function.Consumer;

/** Shared DDL for the existing SQLite file and Room's fresh schema. */
public final class FiscalSchema {
    private FiscalSchema() {}
    public static void createTables(Consumer<String> execute) {
        execute.accept("CREATE TABLE fiscal_document (id TEXT NOT NULL PRIMARY KEY, draftId TEXT NOT NULL, year INTEGER NOT NULL, premise TEXT NOT NULL, registerNo TEXT NOT NULL, number INTEGER NOT NULL, created INTEGER NOT NULL, payload TEXT NOT NULL, digest TEXT NOT NULL)");
        execute.accept("CREATE UNIQUE INDEX index_fiscal_document_draftId ON fiscal_document(draftId)");
        execute.accept("CREATE UNIQUE INDEX index_fiscal_document_year_premise_registerNo_number ON fiscal_document(year,premise,registerNo,number)");
        execute.accept("CREATE TABLE fiscal_outbox (documentId TEXT NOT NULL PRIMARY KEY, state TEXT NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL, claimToken TEXT, reason TEXT, FOREIGN KEY(documentId) REFERENCES fiscal_document(id) ON UPDATE RESTRICT ON DELETE RESTRICT)");
        execute.accept("CREATE INDEX index_fiscal_outbox_state_created ON fiscal_outbox(state,created)");
        execute.accept("CREATE TABLE fiscal_cutover (id INTEGER NOT NULL PRIMARY KEY CHECK(id=1))");
        createTriggers(execute);
    }
    public static void createTriggers(Consumer<String> execute) {
        execute.accept("CREATE TRIGGER fiscal_document_no_update BEFORE UPDATE ON fiscal_document BEGIN SELECT RAISE(ABORT,'Immutable document'); END");
        execute.accept("CREATE TRIGGER fiscal_document_no_delete BEFORE DELETE ON fiscal_document BEGIN SELECT RAISE(ABORT,'Immutable document'); END");
        execute.accept("CREATE TRIGGER fiscal_document_no_replace BEFORE INSERT ON fiscal_document WHEN EXISTS(SELECT 1 FROM fiscal_document WHERE id=NEW.id OR draftId=NEW.draftId OR (year=NEW.year AND premise=NEW.premise AND registerNo=NEW.registerNo AND number=NEW.number)) BEGIN SELECT RAISE(ABORT,'Immutable document'); END");
        execute.accept("CREATE TRIGGER fiscal_outbox_no_delete BEFORE DELETE ON fiscal_outbox BEGIN SELECT RAISE(ABORT,'Durable queue'); END");
        execute.accept("CREATE TRIGGER fiscal_outbox_insert BEFORE INSERT ON fiscal_outbox WHEN NOT ((NEW.state IN ('QUEUED','ARCHIVED') AND NEW.claimToken IS NULL AND NEW.reason IS NULL) OR (NEW.state='UNCERTAIN' AND NEW.claimToken IS NOT NULL AND NEW.reason IS NOT NULL AND NEW.reason IN ('INTERRUPTED','TRANSPORT_ERROR','UNVERIFIED_RESPONSE'))) OR EXISTS(SELECT 1 FROM fiscal_outbox WHERE documentId=NEW.documentId) BEGIN SELECT RAISE(ABORT,'Invalid queue insert'); END");
        execute.accept("CREATE TRIGGER fiscal_outbox_transition BEFORE UPDATE ON fiscal_outbox WHEN NEW.documentId!=OLD.documentId OR NEW.created!=OLD.created OR NOT ((OLD.state='QUEUED' AND NEW.state='SENDING' AND NEW.claimToken IS NOT NULL AND NEW.reason IS NULL) OR (OLD.state='SENDING' AND NEW.state='UNCERTAIN' AND NEW.claimToken IS NOT NULL AND NEW.claimToken IS OLD.claimToken AND NEW.reason IS NOT NULL AND NEW.reason IN ('INTERRUPTED','TRANSPORT_ERROR','UNVERIFIED_RESPONSE'))) BEGIN SELECT RAISE(ABORT,'Invalid queue transition'); END");
    }
}
