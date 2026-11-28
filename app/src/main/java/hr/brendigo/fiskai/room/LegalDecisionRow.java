package hr.brendigo.fiskai.room;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.PrimaryKey;

/** Separate immutable, encrypted decision snapshot for a DEMO payment. */
@Entity(tableName="legal_decision_ledger",foreignKeys={
    @ForeignKey(entity=PaymentPreparationRow.class,parentColumns="receiptId",childColumns="receiptId",onDelete=ForeignKey.RESTRICT,onUpdate=ForeignKey.RESTRICT)
})
public class LegalDecisionRow {
    @PrimaryKey @NonNull public String receiptId="";
    @NonNull public String payload="";
}
