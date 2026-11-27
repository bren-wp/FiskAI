package hr.brendigo.fiskai.room;
import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
@Entity(tableName="bank_transaction")
public class BankTransactionRow {
    @PrimaryKey @NonNull public String id="";
    @NonNull public String payload="";
}
