package hr.brendigo.fiskai

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Stores authenticated ciphertext only. No destructive schema fallback. */
internal class RecordDatabase(context: Context, databaseName: String = "fiskai_records.db") :
    SQLiteOpenHelper(context.applicationContext, databaseName, null, 11) {

    init { setWriteAheadLoggingEnabled(true) }

    companion object {
        @Volatile private var instance: RecordDatabase? = null
        fun shared(context: Context): RecordDatabase = instance ?: synchronized(this) {
            instance ?: RecordDatabase(context.applicationContext).also { instance = it }
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE records (namespace TEXT NOT NULL, record_key TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(namespace,record_key))")
        db.execSQL("CREATE TABLE legacy_migration (namespace TEXT NOT NULL, record_key TEXT NOT NULL, PRIMARY KEY(namespace,record_key))")
        createCisJournal(db)
        createDemoLedger(db)
        createStock(db)
        protectImmutableInserts(db)
        hr.brendigo.fiskai.room.FiscalSchema.createTables { db.execSQL(it) }
        hr.brendigo.fiskai.room.IncomingSchema.create { db.execSQL(it) }
        hr.brendigo.fiskai.room.ReceiptSchema.create { db.execSQL(it) }
        hr.brendigo.fiskai.room.PaymentPreparationSchema.create { db.execSQL(it) }
        hr.brendigo.fiskai.room.PaymentPreparationSchema.allowMultiple { db.execSQL(it) }
        hr.brendigo.fiskai.room.LegalDecisionSchema.create { db.execSQL(it) }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        require(oldVersion in 1..10 && newVersion == 11)
        if(oldVersion == 1) createCisJournal(db)
        if(oldVersion < 3) createDemoLedger(db)
        if(oldVersion < 4) createStock(db)
        if(oldVersion < 5) protectImmutableInserts(db)
        if(oldVersion < 6) hr.brendigo.fiskai.room.FiscalSchema.createTables { db.execSQL(it) }
        if(oldVersion < 7) hr.brendigo.fiskai.room.IncomingSchema.create { db.execSQL(it) }
        if(oldVersion < 8) hr.brendigo.fiskai.room.ReceiptSchema.create { db.execSQL(it) }
        if(oldVersion < 9) hr.brendigo.fiskai.room.PaymentPreparationSchema.create { db.execSQL(it) }
        if(oldVersion < 10) hr.brendigo.fiskai.room.PaymentPreparationSchema.allowMultiple { db.execSQL(it) }
        hr.brendigo.fiskai.room.LegalDecisionSchema.create { db.execSQL(it) }
        // Force Room to validate the upgraded tables before recreating its identity hash.
        db.execSQL("DROP TABLE IF EXISTS room_master_table")
    }

    // SQLite REPLACE can delete conflicting rows without firing DELETE triggers.
    // Abort the insert before conflict resolution, including INSERT OR REPLACE.
    private fun protectImmutableInserts(db: SQLiteDatabase) {
        db.execSQL("CREATE TRIGGER demo_receipt_no_replace BEFORE INSERT ON demo_receipt WHEN EXISTS(SELECT 1 FROM demo_receipt WHERE id=NEW.id OR draft_id=NEW.draft_id OR (year=NEW.year AND premise=NEW.premise AND register_no=NEW.register_no AND number=NEW.number)) BEGIN SELECT RAISE(ABORT,'Immutable test snapshot'); END")
        db.execSQL("CREATE TRIGGER stock_event_no_replace BEFORE INSERT ON stock_event WHEN EXISTS(SELECT 1 FROM stock_event WHERE id=NEW.id) BEGIN SELECT RAISE(ABORT,'Immutable stock movement'); END")
    }

    private fun createCisJournal(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE cis_echo (id TEXT PRIMARY KEY NOT NULL, created INTEGER NOT NULL, state TEXT NOT NULL CHECK(state IN ('QUEUED','SENDING','VERIFIED','UNCERTAIN')), completed INTEGER, outcome TEXT)")
    }

    private fun createDemoLedger(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE demo_sequence (year INTEGER NOT NULL, premise TEXT NOT NULL, register_no TEXT NOT NULL, last_no INTEGER NOT NULL CHECK(last_no>=1), PRIMARY KEY(year,premise,register_no))")
        db.execSQL("CREATE TABLE demo_receipt (id TEXT PRIMARY KEY NOT NULL, draft_id TEXT NOT NULL UNIQUE, year INTEGER NOT NULL, premise TEXT NOT NULL, register_no TEXT NOT NULL, number INTEGER NOT NULL CHECK(number>=1), created INTEGER NOT NULL, payload TEXT NOT NULL, UNIQUE(year,premise,register_no,number))")
        db.execSQL("CREATE TRIGGER demo_receipt_no_update BEFORE UPDATE ON demo_receipt BEGIN SELECT RAISE(ABORT,'Immutable test snapshot'); END")
        db.execSQL("CREATE TRIGGER demo_receipt_no_delete BEFORE DELETE ON demo_receipt BEGIN SELECT RAISE(ABORT,'Immutable test snapshot'); END")
    }

    private fun createStock(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE stock_balance (product_id TEXT PRIMARY KEY NOT NULL, quantity INTEGER NOT NULL CHECK(quantity>=0))")
        db.execSQL("CREATE TABLE stock_event (id TEXT PRIMARY KEY NOT NULL, product_id TEXT NOT NULL, delta INTEGER NOT NULL CHECK(delta<>0), quantity_after INTEGER NOT NULL CHECK(quantity_after>=0), created INTEGER NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("CREATE INDEX stock_event_product ON stock_event(product_id,created)")
        db.execSQL("CREATE TRIGGER stock_event_no_update BEFORE UPDATE ON stock_event BEGIN SELECT RAISE(ABORT,'Immutable stock movement'); END")
        db.execSQL("CREATE TRIGGER stock_event_no_delete BEFORE DELETE ON stock_event BEGIN SELECT RAISE(ABORT,'Immutable stock movement'); END")
    }

    private fun values(namespace: String, key: String) = ContentValues().apply {
        put("namespace",namespace)
        put("record_key",key)
    }

    private fun exists(db: SQLiteDatabase, table: String, namespace: String, key: String): Boolean {
        return db.query(table,arrayOf("record_key"),"namespace=? AND record_key=?",arrayOf(namespace,key),null,null,null).use { it.moveToFirst() }
    }

    private fun put(db: SQLiteDatabase, namespace: String, key: String, payload: String) {
        require(payload.startsWith("gcm1:"))
        val row = values(namespace,key).apply { put("payload",payload) }
        check(db.insertWithOnConflict("records",null,row,SQLiteDatabase.CONFLICT_REPLACE) != -1L)
    }

    /** Marker and record commit atomically. Legacy cleanup is performed by caller afterwards.
     *  If process dies before cleanup, marker prevents stale data from replacing newer writes. */
    fun migrate(namespace: String, key: String, legacyCiphertext: () -> String?) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if(!exists(db,"legacy_migration",namespace,key)) {
                if(!exists(db,"records",namespace,key)) {
                    legacyCiphertext()?.let { put(db,namespace,key,it) }
                }
                check(db.insertOrThrow("legacy_migration",null,values(namespace,key)) != -1L)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun read(namespace: String, key: String): String? =
        readableDatabase.query("records",arrayOf("payload"),"namespace=? AND record_key=?",arrayOf(namespace,key),null,null,null).use {
            if(it.moveToFirst()) it.getString(0) else null
        }

    fun write(namespace: String, key: String, payload: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            check(exists(db,"legacy_migration",namespace,key)) { "Record has not been migrated" }
            put(db,namespace,key,payload)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
