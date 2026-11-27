package hr.brendigo.fiskai.room;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName="fiscal_outbox", foreignKeys=@ForeignKey(
    entity=FiscalDocument.class,parentColumns="id",childColumns="documentId",
    onDelete=ForeignKey.RESTRICT,onUpdate=ForeignKey.RESTRICT),
    indices=@Index(value={"state","created"}))
public class FiscalQueueEntry {
    @PrimaryKey @NonNull public String documentId="";
    @NonNull public String state="QUEUED";
    public long created;
    public long updated;
    public String claimToken;
    public String reason;
}
