package hr.brendigo.fiskai.room;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities={FiscalDocument.class,FiscalQueueEntry.class},version=1,exportSchema=true)
public abstract class FiscalRoomDatabase extends RoomDatabase {
    public abstract FiscalQueueDao queue();
    private static volatile FiscalRoomDatabase instance;
    public static FiscalRoomDatabase shared(Context context) {
        FiscalRoomDatabase current=instance;
        if(current!=null)return current;
        synchronized(FiscalRoomDatabase.class) {
            if(instance==null)instance=open(context,"fiskai_fiscal_room.db");
            return instance;
        }
    }
    public static FiscalRoomDatabase open(Context context,String name) {
        return Room.databaseBuilder(context.getApplicationContext(),FiscalRoomDatabase.class,name)
            .addCallback(new Callback() {
                @Override public void onCreate(@NonNull SupportSQLiteDatabase db) {
                    db.execSQL("CREATE TRIGGER fiscal_document_no_update BEFORE UPDATE ON fiscal_document BEGIN SELECT RAISE(ABORT,'Immutable document'); END");
                    db.execSQL("CREATE TRIGGER fiscal_document_no_delete BEFORE DELETE ON fiscal_document BEGIN SELECT RAISE(ABORT,'Immutable document'); END");
                    db.execSQL("CREATE TRIGGER fiscal_document_no_replace BEFORE INSERT ON fiscal_document WHEN EXISTS(SELECT 1 FROM fiscal_document WHERE id=NEW.id OR draftId=NEW.draftId OR (year=NEW.year AND premise=NEW.premise AND registerNo=NEW.registerNo AND number=NEW.number)) BEGIN SELECT RAISE(ABORT,'Immutable document'); END");
                    db.execSQL("CREATE TRIGGER fiscal_outbox_no_delete BEFORE DELETE ON fiscal_outbox BEGIN SELECT RAISE(ABORT,'Durable queue'); END");
                    db.execSQL("CREATE TRIGGER fiscal_outbox_insert BEFORE INSERT ON fiscal_outbox WHEN NEW.state!='QUEUED' OR NEW.claimToken IS NOT NULL OR NEW.reason IS NOT NULL OR EXISTS(SELECT 1 FROM fiscal_outbox WHERE documentId=NEW.documentId) BEGIN SELECT RAISE(ABORT,'Invalid queue insert'); END");
                    db.execSQL("CREATE TRIGGER fiscal_outbox_transition BEFORE UPDATE ON fiscal_outbox WHEN NEW.documentId!=OLD.documentId OR NEW.created!=OLD.created OR NOT ((OLD.state='QUEUED' AND NEW.state='SENDING' AND NEW.claimToken IS NOT NULL AND NEW.reason IS NULL) OR (OLD.state='SENDING' AND NEW.state='UNCERTAIN' AND NEW.claimToken IS NOT NULL AND NEW.claimToken IS OLD.claimToken AND NEW.reason IS NOT NULL AND NEW.reason IN ('INTERRUPTED','TRANSPORT_ERROR','UNVERIFIED_RESPONSE'))) BEGIN SELECT RAISE(ABORT,'Invalid queue transition'); END");
                }
            }).build();
    }
}
