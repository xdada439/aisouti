package com.lk.studyassistant.quantum.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class LocalDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        createQuestionBankTables(db)
        createMaterialTables(db)
        createImportRecordTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createQuestionBankTables(db)
            createMaterialTables(db)
        }
        if (oldVersion < 3) {
            runCatching { db.execSQL("ALTER TABLE local_question_bank ADD COLUMN option_e TEXT") }
            createImportRecordTable(db)
        }
        if (oldVersion < 4) {
            runCatching { db.execSQL("ALTER TABLE local_question_bank ADD COLUMN normalized_full_text TEXT NOT NULL DEFAULT ''") }
            runCatching { db.execSQL("UPDATE local_question_bank SET normalized_full_text = TRIM(COALESCE(normalized_stem,'') || ' ' || COALESCE(normalized_options,'')) WHERE normalized_full_text = ''") }
        }
        if (oldVersion < 5) {
            // 无老用户兼容包袱，直接重建题库表：
            // - 删 question_id / analysis / chapter 三个未用列
            // - 选项扩到 A~H（共 8 列）
            runCatching { db.execSQL("DROP TABLE IF EXISTS local_question_bank_fts") }
            runCatching { db.execSQL("DROP TABLE IF EXISTS local_question_bank") }
            createQuestionBankTables(db)
        }
    }

    private fun createQuestionBankTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS local_question_bank (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                question_type TEXT NOT NULL,
                stem TEXT NOT NULL,
                option_a TEXT,
                option_b TEXT,
                option_c TEXT,
                option_d TEXT,
                option_e TEXT,
                option_f TEXT,
                option_g TEXT,
                option_h TEXT,
                answer TEXT NOT NULL,
                source_file TEXT,
                normalized_stem TEXT NOT NULL,
                normalized_options TEXT NOT NULL,
                normalized_full_text TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS local_question_bank_fts USING fts4(
                stem,
                options,
                content='local_question_bank'
            )
            """.trimIndent()
        )
    }

    private fun createMaterialTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS local_material (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                source_file TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS local_material_chunk (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                material_id INTEGER NOT NULL,
                material_name TEXT NOT NULL,
                chapter_path TEXT,
                page_no INTEGER,
                paragraph_index INTEGER NOT NULL,
                raw_text TEXT NOT NULL,
                clean_text TEXT NOT NULL,
                keywords TEXT,
                source_file TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS local_material_chunk_fts USING fts4(
                clean_text,
                keywords,
                content='local_material_chunk'
            )
            """.trimIndent()
        )
    }

    private fun createImportRecordTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS local_import_record (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                type TEXT NOT NULL,
                file_name TEXT NOT NULL,
                display_name TEXT,
                file_size INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL,
                error_code TEXT,
                detail TEXT,
                total_rows INTEGER NOT NULL DEFAULT 0,
                imported_count INTEGER NOT NULL DEFAULT 0,
                failed_count INTEGER NOT NULL DEFAULT 0,
                single_count INTEGER NOT NULL DEFAULT 0,
                multiple_count INTEGER NOT NULL DEFAULT 0,
                judge_count INTEGER NOT NULL DEFAULT 0,
                blank_count INTEGER NOT NULL DEFAULT 0,
                unsupported_count INTEGER NOT NULL DEFAULT 0,
                chunk_count INTEGER NOT NULL DEFAULT 0,
                enabled INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    companion object {
        private const val DB_NAME = "local_ai_search.db"
        private const val DB_VERSION = 5
    }
}
