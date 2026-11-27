package hr.brendigo.fiskai.room;
import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName="incoming_invoice")
public class IncomingInvoiceRow {
    @PrimaryKey @NonNull public String id="";
    @NonNull public String payload="";
}
