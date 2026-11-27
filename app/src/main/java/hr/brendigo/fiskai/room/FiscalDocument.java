package hr.brendigo.fiskai.room;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Immutable local DEMO snapshot reference. Contains only ciphertext and identifiers. */
@Entity(tableName="fiscal_document", indices={
    @Index(value={"draftId"},unique=true),
    @Index(value={"year","premise","registerNo","number"},unique=true)
})
public class FiscalDocument {
    @PrimaryKey @NonNull public String id="";
    @NonNull public String draftId="";
    public int year;
    @NonNull public String premise="";
    @NonNull public String registerNo="";
    public long number;
    public long created;
    @NonNull public String payload="";
    @NonNull public String digest="";
}
