package hr.brendigo.fiskai.room;
import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Immutable DEMO-only local payment preparation, not a fiscal acknowledgement. */
@Entity(tableName="demo_payment_preparation",foreignKeys={
    @ForeignKey(entity=ReceiptEventRow.class,parentColumns="id",childColumns="receiptId",onDelete=ForeignKey.RESTRICT,onUpdate=ForeignKey.RESTRICT),
    @ForeignKey(entity=FiscalDocument.class,parentColumns="id",childColumns="documentId",onDelete=ForeignKey.RESTRICT,onUpdate=ForeignKey.RESTRICT)
},indices={@Index(value={"documentId"},unique=true),@Index(value={"eventId"},unique=true)})
public class PaymentPreparationRow {
    @PrimaryKey @NonNull public String receiptId="";
    @NonNull public String documentId="";
    @NonNull public String eventId="";
    @NonNull public String payload="";
}
