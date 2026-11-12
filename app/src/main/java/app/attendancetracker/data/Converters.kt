package app.attendancetracker.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromCategory(value: SnapshotCategory): String = value.name

    @TypeConverter
    fun toCategory(value: String): SnapshotCategory = SnapshotCategory.valueOf(value)

    @TypeConverter
    fun fromStatus(value: SnapshotStatus): String = value.name

    @TypeConverter
    fun toStatus(value: String): SnapshotStatus = SnapshotStatus.valueOf(value)
}
