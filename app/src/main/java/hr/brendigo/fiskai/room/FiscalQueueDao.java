package hr.brendigo.fiskai.room;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Transaction;

/** No network transport and no fabricated acceptance state. */
@Dao
public abstract class FiscalQueueDao {
    @Insert abstract void insertDocument(FiscalDocument document);
    @Insert abstract void insertQueue(FiscalQueueEntry entry);
    @Query("SELECT * FROM fiscal_document WHERE id=:id")
    public abstract FiscalDocument document(String id);
    @Query("SELECT * FROM fiscal_outbox WHERE documentId=:id")
    public abstract FiscalQueueEntry entry(String id);

    @Transaction
    public FiscalQueueEntry enqueue(FiscalDocument document,long now) {
        FiscalDocument existing=document(document.id);
        if(existing!=null) {
            if(!existing.digest.equals(document.digest) || !existing.payload.equals(document.payload)
                || !existing.draftId.equals(document.draftId) || existing.number!=document.number
                || existing.year!=document.year || !existing.premise.equals(document.premise)
                || !existing.registerNo.equals(document.registerNo) || existing.created!=document.created)
                throw new IllegalStateException("Conflicting immutable document");
            FiscalQueueEntry row=entry(document.id);
            if(row==null)throw new IllegalStateException("Missing queue entry");
            return row;
        }
        FiscalQueueEntry row=new FiscalQueueEntry();
        row.documentId=document.id;
        row.created=now;
        row.updated=now;
        insertDocument(document);
        insertQueue(row);
        return row;
    }
    @Query("SELECT * FROM fiscal_document WHERE draftId=:draftId")
    public abstract FiscalDocument byDraft(String draftId);
    @Query("SELECT COALESCE(MAX(number),0) FROM fiscal_document WHERE year=:year AND premise=:premise AND registerNo=:register")
    abstract long highestNumber(int year,String premise,String register);

    /** Creates the authenticated snapshot only after allocation inside the Room transaction.
     * Migration floor is supplied by the legacy coordinator while holding its write lock.
     * Not enabled for live issuance before the one-authority cutover. */
    public interface SnapshotFactory {
        FiscalDocument create(long number);
    }

    @Transaction
    public FiscalDocument issueLocal(String draftId,int year,String premise,String register,
                                     long migrationFloor,long now,SnapshotFactory factory) {
        java.util.UUID.fromString(draftId);
        if(year<2000 || year>9999 || premise==null || premise.isEmpty() || register==null ||
            register.isEmpty() || migrationFloor<0 || migrationFloor>999999999L || now<=0)
            throw new IllegalArgumentException("Invalid numbering scope");
        FiscalDocument existing=byDraft(draftId);
        if(existing!=null) {
            if(existing.year!=year || !existing.premise.equals(premise) ||
                !existing.registerNo.equals(register) || entry(existing.id)==null)
                throw new IllegalStateException("Conflicting document scope");
            return existing;
        }
        long last=Math.max(migrationFloor,highestNumber(year,premise,register));
        if(last>=999999999L)throw new IllegalStateException("Numbering exhausted");
        long next=last+1;
        FiscalDocument created=factory.create(next);
        if(created==null || !draftId.equals(created.draftId) || created.year!=year ||
            !premise.equals(created.premise) || !register.equals(created.registerNo) ||
            created.number!=next || created.created!=now ||
            !created.payload.startsWith("gcm1:") || !created.digest.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid immutable snapshot");
        java.util.UUID.fromString(created.id);
        // Any failure rolls back both rows. A failed issue does not consume a number.
        insertDocument(created);
        FiscalQueueEntry row=new FiscalQueueEntry();
        row.documentId=created.id;
        row.created=now;
        row.updated=now;
        insertQueue(row);
        return created;
    }

    @Query("UPDATE fiscal_outbox SET state='SENDING', claimToken=:token, updated=:now WHERE documentId=:id AND state='QUEUED' AND claimToken IS NULL")
    abstract int claimInternal(String id,String token,long now);
    @Query("UPDATE fiscal_outbox SET state='UNCERTAIN', reason=:reason, updated=:now WHERE documentId=:id AND state='SENDING' AND claimToken=:token")
    abstract int uncertainInternal(String id,String token,String reason,long now);
    @Query("UPDATE fiscal_outbox SET state='UNCERTAIN', reason='INTERRUPTED', updated=:now WHERE state='SENDING'")
    public abstract int recoverInterrupted(long now);
    // Only future verified transport adapters may call these, never an automatic retry.
    public boolean claim(String id,String token,long now) {
        java.util.UUID.fromString(token);
        return claimInternal(id,token,now)==1;
    }
    public boolean markUncertain(String id,String token,String reason,long now) {
        if(!"TRANSPORT_ERROR".equals(reason)&&!"UNVERIFIED_RESPONSE".equals(reason))
            throw new IllegalArgumentException("Invalid outcome");
        return uncertainInternal(id,token,reason,now)==1;
    }
}
