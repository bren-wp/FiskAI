package hr.brendigo.fiskai

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Stores authenticated ciphertext only. No destructive schema fallback. */
internal class RecordDatabase(context: Context, databaseName: String = "fiskai_records.db") :
    SQLiteOpenHelper(context.applicationContext, databaseName, null, 1) {

    companion object {
        @Volatile private var instance: RecordDatabase? = null
        fun shared(context: Context): RecordDatabase = instance ?: synchronized(this) {
            instance ?: RecordDatabase(context.applicationContext).also { instance = it }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE records (namespace TEXT NOT NULL, record_key TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(namespace,record_key))")
        db.execSQL("CREATE TABLE legacy_migration (namespace TEXT NOT NULL, record_key TEXT NOT NULL, PRIMARY KEY(namespace,record_key))")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unsupported schema upgrade from " + oldVersion + " to " + newVersion)
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
