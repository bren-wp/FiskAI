package hr.brendigo.fiskai.room;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities={FiscalDocument.class,FiscalQueueEntry.class},version=6,exportSchema=true)
public abstract class FiscalRoomDatabase extends RoomDatabase {
    public abstract FiscalQueueDao queue();
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
            .addCallback(new Callback() {
                @Override public void onCreate(@NonNull SupportSQLiteDatabase db) {
                    FiscalSchema.createTriggers(db::execSQL);
                }
            }).build();
    }
}
