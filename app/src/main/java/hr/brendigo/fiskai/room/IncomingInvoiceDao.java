package hr.brendigo.fiskai.room;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

@Dao
public interface IncomingInvoiceDao {
    @Query("SELECT * FROM incoming_invoice ORDER BY id")
    List<IncomingInvoiceRow> all();
    @Query("SELECT * FROM incoming_invoice WHERE id=:id")
    IncomingInvoiceRow find(String id);
    @Insert void insert(IncomingInvoiceRow row);
    @Query("UPDATE incoming_invoice SET payload=:next WHERE id=:id AND payload=:previous")
    int update(String id,String previous,String next);
}
