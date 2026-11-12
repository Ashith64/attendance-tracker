package app.attendancetracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        TermEntity::class,
        CourseEntity::class,
        SnapshotEntity::class,
        ExamHistoryEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AttendanceDatabase : RoomDatabase() {
    abstract fun portalDao(): PortalDao

    companion object {
        @Volatile
        private var instance: AttendanceDatabase? = null

        fun getInstance(context: Context): AttendanceDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AttendanceDatabase::class.java,
                    "attendance_tracker.db",
                ).build().also { instance = it }
            }
    }
}
