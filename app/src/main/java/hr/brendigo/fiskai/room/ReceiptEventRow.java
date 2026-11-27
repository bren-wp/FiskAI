package hr.brendigo.fiskai.room;
import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;
@Entity(tableName="receipt_event",foreignKeys={
    @ForeignKey(entity=BankTransactionRow.class,parentColumns="id",childColumns="bankId",onDelete=ForeignKey.RESTRICT,onUpdate=ForeignKey.RESTRICT)
},indices=@Index(value={"bankId"},unique=true))
public class ReceiptEventRow {
    @PrimaryKey @NonNull public String id="";
    public String bankId;
    @NonNull public String payload="";
}
