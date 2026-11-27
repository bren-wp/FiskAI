package hr.brendigo.fiskai.room;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities={FiscalDocument.class,FiscalQueueEntry.class,IncomingInvoiceRow.class},version=7,exportSchema=true)
public abstract class FiscalRoomDatabase extends RoomDatabase {
    public abstract FiscalQueueDao queue();
    public abstract IncomingInvoiceDao incoming();
    private static volatile FiscalRoomDatabase instance;
    public static FiscalRoomDatabase shared(Context context) {
        FiscalRoomDatabase current=instance;
        if(current!=null)return current;
        synchronized(FiscalRoomDatabase.class) {
            if(instance==null)instance=open(context,"fiskai_records.db");
            return instance;
        }
    }
    public static FiscalRoomDatabase open(Context context,String name) {
        return Room.databaseBuilder(context.getApplicationContext(),FiscalRoomDatabase.class,name)
            .addMigrations(new androidx.room.migration.Migration(6,7) {
                @Override public void migrate(@NonNull SupportSQLiteDatabase db) { IncomingSchema.create(db::execSQL); }
            })
            .addCallback(new Callback() {
                @Override public void onCreate(@NonNull SupportSQLiteDatabase db) {
                    FiscalSchema.createTriggers(db::execSQL);
                    IncomingSchema.create(db::execSQL);
                }
            }).build();
    }
}
