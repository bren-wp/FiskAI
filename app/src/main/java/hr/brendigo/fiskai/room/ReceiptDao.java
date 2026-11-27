package hr.brendigo.fiskai.room;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;
@Dao
public interface ReceiptDao {
    @Query("SELECT * FROM bank_transaction ORDER BY id") List<BankTransactionRow> bank();
    @Query("SELECT * FROM receipt_event ORDER BY id") List<ReceiptEventRow> receipts();
    @Query("SELECT * FROM bank_transaction WHERE id=:id") BankTransactionRow bankById(String id);
    @Query("SELECT * FROM receipt_event WHERE id=:id") ReceiptEventRow receiptById(String id);
    @Insert void insertBank(List<BankTransactionRow> rows);
    @Insert void insertReceipt(ReceiptEventRow row);
}
