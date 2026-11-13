package app.attendancetracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TermEntity::class,
        CourseEntity::class,
        SnapshotEntity::class,
        ExamHistoryEntity::class,
        AttendanceRecordEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AttendanceDatabase : RoomDatabase() {
    abstract fun portalDao(): PortalDao

    companion object {
        @Volatile
        private var instance: AttendanceDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `attendance_records` (
                        `courseCode` TEXT NOT NULL,
                        `term` TEXT NOT NULL,
                        `sessionKey` TEXT NOT NULL,
                        `date` TEXT NOT NULL,
                        `slot` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `dateMillis` INTEGER NOT NULL,
                        `rawDateText` TEXT NOT NULL,
                        `fetchedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`courseCode`, `term`, `sessionKey`),
                        FOREIGN KEY(`courseCode`, `term`) REFERENCES `courses`(`code`, `term`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_attendance_records_courseCode_term` ON `attendance_records` (`courseCode`, `term`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_attendance_records_dateMillis` ON `attendance_records` (`dateMillis`)")
            }
        }

        fun getInstance(context: Context): AttendanceDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AttendanceDatabase::class.java,
                    "attendance_tracker.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build().also { instance = it }
            }
    }
}
