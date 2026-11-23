package com.timevora.app.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.LocalDate

@Entity(tableName = "usage_sessions", indices = [Index(value = ["packageName", "day"])])
data class UsageSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appLabel: String,
    val day: String,
    val startMillis: Long,
    val endMillis: Long,
    val durationMillis: Long
)

@Entity(tableName = "app_limits")
data class AppLimit(
    @PrimaryKey val packageName: String,
    val appLabel: String,
    val limitMinutes: Int,
    val enabled: Boolean = true,
    val alert50: Boolean = true,
    val alert80: Boolean = true,
    val alert100: Boolean = true
)

/** A diary entry: task, meeting or routine. Can repeat and can remind you with a notification. */
@Entity(tableName = "planner_items")
data class PlannerItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val notes: String,
    val kind: String,          // task | meeting | routine
    val startDay: String,      // yyyy-MM-dd (first or only date)
    val minuteOfDay: Int,      // -1 = no time
    val repeatMode: String,    // none | daily | weekdays | weekly
    val remindBefore: Int,     // -1 = no reminder, 0 = at time, else minutes before
    val createdAt: Long
)

/** Marks a planner item as done on one specific day (so repeating items can be ticked every day). */
@Entity(tableName = "planner_done", primaryKeys = ["itemId", "day"])
data class PlannerDone(val itemId: Long, val day: String)

/** Manual category choice for one app (overrides the automatic guess). */
@Entity(tableName = "app_categories")
data class AppCategoryOverride(@PrimaryKey val packageName: String, val category: String)

/** Daily time limit for a whole category, e.g. Social total 60 min. [category] is an AppCategory name. */
@Entity(tableName = "category_limits")
data class CategoryLimit(
    @PrimaryKey val category: String,
    val limitMinutes: Int,
    val enabled: Boolean = true,
    val alert50: Boolean = true,
    val alert80: Boolean = true,
    val alert100: Boolean = true
)

@Entity(tableName = "focus_sessions")
data class FocusSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: String,
    val startMillis: Long,
    val plannedSeconds: Int,
    val actualSeconds: Int,
    val label: String,
    /** Seconds spent in other apps while the session ran (timevora itself is not counted). */
    @ColumnInfo(defaultValue = "0") val leakedSeconds: Int = 0,
    /** Focus Forest: true when the tree withered (too much phone use, or the session was stopped early). */
    @ColumnInfo(defaultValue = "0") val withered: Boolean = false
)

/** User-defined recurring habit. All records remain on-device. */
@Entity(tableName = "habits", indices = [Index(value = ["active", "createdAt"])])
data class Habit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val category: String = "Personal",
    val schedule: String = "daily", // daily | weekdays | weekly
    val active: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

/** One completion per habit per local calendar day. */
@Entity(tableName = "habit_logs", primaryKeys = ["habitId", "day"], indices = [Index(value = ["day"])])
data class HabitLog(val habitId: Long, val day: String, val completedAt: Long)

/** Immutable reward ledger prevents UI refreshes from awarding XP again. */
@Entity(tableName = "xp_transactions", indices = [Index(value = ["day"])])
data class XpTransaction(
    @PrimaryKey val eventKey: String,
    val day: String,
    val points: Int,
    val reason: String,
    val createdAt: Long
)

@Entity(tableName = "daily_scores")
data class DailyScore(
    @PrimaryKey val day: String,
    val score: Int,
    val focusScore: Int,
    val planScore: Int,
    val habitScore: Int,
    val balanceScore: Int,
    val calculatedAt: Long
)

/** Permanently unlocked achievements. The primary key makes unlocks idempotent. */
@Entity(tableName = "achievement_unlocks")
data class AchievementUnlock(
    @PrimaryKey val achievementId: String,
    val unlockedAt: Long,
    val progressAtUnlock: Int
)


/** A study subject created by the user for exam preparation. */
@Entity(tableName = "exam_subjects")
data class ExamSubject(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorHex: String = "#4263EB",
    val targetMinutes: Int = 600,
    val createdAt: Long = System.currentTimeMillis()
)

/** An upcoming exam associated with a subject. */
@Entity(tableName = "exam_events", indices = [Index(value = ["examDate"])])
data class ExamEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val subjectId: Long,
    val examDate: String, // yyyy-MM-dd
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/** Manually logged study time, stored locally and grouped by subject and day. */
@Entity(tableName = "study_logs", indices = [Index(value = ["day"]), Index(value = ["subjectId", "day"])])
data class StudyLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subjectId: Long,
    val day: String,
    val minutes: Int,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface UsageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(session: UsageSession)
    @Query("SELECT packageName, appLabel, SUM(durationMillis) AS durationMillis, COUNT(*) AS sessions FROM usage_sessions WHERE day = :day GROUP BY packageName ORDER BY durationMillis DESC")
    suspend fun daily(day: String): List<AppUsageTotal>
    @Query("SELECT COALESCE(SUM(durationMillis),0) FROM usage_sessions WHERE day = :day") suspend fun total(day: String): Long
    @Query("SELECT * FROM usage_sessions WHERE day = :day ORDER BY startMillis DESC") suspend fun sessions(day: String): List<UsageSession>
    @Query("SELECT day, SUM(durationMillis) AS durationMillis FROM usage_sessions WHERE day BETWEEN :start AND :end GROUP BY day ORDER BY day") suspend fun dailyTotals(start: String, end: String): List<DailyUsageTotal>
    @Query("SELECT packageName, appLabel, SUM(durationMillis) AS durationMillis, COUNT(*) AS sessions FROM usage_sessions WHERE day BETWEEN :start AND :end GROUP BY packageName ORDER BY durationMillis DESC") suspend fun rangeApps(start: String, end: String): List<AppUsageTotal>
    @Query("SELECT COALESCE(SUM(durationMillis),0) FROM usage_sessions WHERE day = :day AND packageName = :pkg") suspend fun appTotal(day: String, pkg: String): Long
    @Query("SELECT COALESCE(SUM(durationMillis),0) FROM usage_sessions WHERE startMillis >= :since") suspend fun usageSince(since: Long): Long
    @Query("SELECT * FROM app_limits ORDER BY appLabel COLLATE NOCASE") suspend fun limits(): List<AppLimit>
    @Query("SELECT packageName, appLabel FROM usage_sessions GROUP BY packageName ORDER BY appLabel COLLATE NOCASE") suspend fun knownApps(): List<KnownApp>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveLimit(limit: AppLimit)
    @Query("DELETE FROM app_limits WHERE packageName = :pkg") suspend fun deleteLimit(pkg: String)
    @Query("DELETE FROM usage_sessions") suspend fun clearAll()

    // ---- Diary / planner ----
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun savePlanner(item: PlannerItem): Long
    @Query("SELECT * FROM planner_items ORDER BY id") suspend fun plannerAll(): List<PlannerItem>
    @Query("SELECT * FROM planner_items WHERE id = :id") suspend fun plannerById(id: Long): PlannerItem?
    @Query("DELETE FROM planner_items WHERE id = :id") suspend fun deletePlanner(id: Long)
    @Query("DELETE FROM planner_done WHERE itemId = :id") suspend fun deleteDoneFor(id: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun markDone(done: PlannerDone)
    @Query("DELETE FROM planner_done WHERE itemId = :id AND day = :day") suspend fun unmarkDone(id: Long, day: String)
    @Query("SELECT itemId FROM planner_done WHERE day = :day") suspend fun doneIds(day: String): List<Long>
    @Query("SELECT COUNT(*) FROM planner_done WHERE itemId = :id AND day = :day") suspend fun doneCount(id: Long, day: String): Int

    // ---- Focus timer ----
    @Insert suspend fun insertFocus(session: FocusSession)
    @Query("SELECT COALESCE(SUM(actualSeconds),0) FROM focus_sessions WHERE day = :day") suspend fun focusSeconds(day: String): Long
    @Query("SELECT * FROM focus_sessions ORDER BY startMillis DESC LIMIT :n") suspend fun recentFocus(n: Int): List<FocusSession>
    @Query("SELECT COALESCE(SUM(actualSeconds),0) FROM focus_sessions") suspend fun focusSecondsTotal(): Long
    @Query("SELECT COALESCE(SUM(actualSeconds),0) FROM focus_sessions WHERE day BETWEEN :start AND :end") suspend fun focusSecondsBetween(start: String, end: String): Long

    // ---- Smart categories and category limits ----
    @Query("SELECT * FROM app_categories") suspend fun appCategoryOverrides(): List<AppCategoryOverride>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveAppCategory(o: AppCategoryOverride)
    @Query("DELETE FROM app_categories WHERE packageName = :pkg") suspend fun deleteAppCategory(pkg: String)
    @Query("SELECT * FROM category_limits ORDER BY category") suspend fun categoryLimits(): List<CategoryLimit>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveCategoryLimit(limit: CategoryLimit)
    @Query("DELETE FROM category_limits WHERE category = :category") suspend fun deleteCategoryLimit(category: String)

    // ---- Focus Forest ----
    @Query("SELECT COALESCE(SUM(durationMillis),0) FROM usage_sessions WHERE startMillis >= :since AND startMillis < :until AND packageName != :pkg") suspend fun usageBetweenExcluding(since: Long, until: Long, pkg: String): Long
    @Query("SELECT * FROM focus_sessions WHERE plannedSeconds >= 300 ORDER BY startMillis DESC LIMIT :n") suspend fun forestSessions(n: Int): List<FocusSession>
    @Query("SELECT DISTINCT day FROM focus_sessions WHERE plannedSeconds >= 300 AND withered = 0 AND actualSeconds >= plannedSeconds ORDER BY day") suspend fun treeDays(): List<String>
    @Query("SELECT COUNT(*) FROM focus_sessions WHERE plannedSeconds >= 300 AND withered = 0 AND actualSeconds >= plannedSeconds") suspend fun healthyTreeCount(): Int
    @Query("SELECT COUNT(*) FROM focus_sessions WHERE plannedSeconds >= 300 AND (withered = 1 OR actualSeconds < plannedSeconds)") suspend fun witheredTreeCount(): Int

    // ---- Habits, Life Score and XP ----
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveHabit(habit: Habit): Long
    @Query("SELECT * FROM habits WHERE active = 1 ORDER BY createdAt DESC") suspend fun activeHabits(): List<Habit>
    @Query("SELECT * FROM habits ORDER BY createdAt DESC") suspend fun allHabits(): List<Habit>
    @Query("SELECT day FROM habit_logs WHERE habitId = :habitId AND day BETWEEN :start AND :end ORDER BY day DESC") suspend fun habitLogDays(habitId: Long, start: String, end: String): List<String>
    @Query("SELECT COUNT(*) FROM habit_logs WHERE habitId = :habitId AND day BETWEEN :start AND :end") suspend fun habitCompletionsBetween(habitId: Long, start: String, end: String): Int
    @Query("UPDATE habits SET schedule = :schedule WHERE id = :id") suspend fun updateHabitSchedule(id: Long, schedule: String)
    @Query("UPDATE habits SET active = 0 WHERE id = :id") suspend fun archiveHabit(id: Long)
    @Query("DELETE FROM habits WHERE id = :id") suspend fun deleteHabit(id: Long)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun logHabit(log: HabitLog): Long
    @Query("DELETE FROM habit_logs WHERE habitId = :habitId AND day = :day") suspend fun unlogHabit(habitId: Long, day: String)
    @Query("SELECT COUNT(*) FROM habit_logs WHERE habitId = :habitId AND day = :day") suspend fun habitDone(habitId: Long, day: String): Int
    @Query("SELECT COUNT(*) FROM habit_logs WHERE day = :day") suspend fun completedHabits(day: String): Int
    @Query("SELECT COUNT(*) FROM habits WHERE active = 1") suspend fun activeHabitCount(): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addXp(transaction: XpTransaction): Long
    @Query("SELECT COALESCE(SUM(points),0) FROM xp_transactions") suspend fun totalXp(): Int
    @Query("SELECT COALESCE(SUM(points),0) FROM xp_transactions WHERE day = :day") suspend fun xpToday(day: String): Int
    @Query("SELECT * FROM daily_scores ORDER BY day DESC LIMIT :limit") suspend fun recentScores(limit: Int): List<DailyScore>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveDailyScore(score: DailyScore)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun unlockAchievement(unlock: AchievementUnlock): Long
    @Query("SELECT * FROM achievement_unlocks ORDER BY unlockedAt DESC") suspend fun unlockedAchievements(): List<AchievementUnlock>
    @Query("SELECT COUNT(*) FROM focus_sessions") suspend fun focusSessionCount(): Int
    @Query("SELECT COUNT(*) FROM focus_sessions WHERE actualSeconds >= :minimumSeconds") suspend fun qualifyingFocusCount(minimumSeconds: Int): Int
    @Query("SELECT COUNT(*) FROM planner_done") suspend fun completedPlannerCount(): Int
    @Query("SELECT COUNT(*) FROM planner_done WHERE day BETWEEN :start AND :end") suspend fun completedPlannerBetween(start: String, end: String): Int
    @Query("SELECT COUNT(*) FROM habit_logs") suspend fun completedHabitLogCount(): Int
    @Query("SELECT COUNT(*) FROM habit_logs WHERE day BETWEEN :start AND :end") suspend fun completedHabitsBetween(start: String, end: String): Int
    @Query("SELECT COALESCE(SUM(minutes),0) FROM study_logs WHERE day BETWEEN :start AND :end") suspend fun studyMinutesBetween(start: String, end: String): Int
    @Query("SELECT day FROM focus_sessions WHERE actualSeconds >= :minimumSeconds UNION SELECT day FROM habit_logs UNION SELECT day FROM planner_done UNION SELECT day FROM study_logs WHERE minutes >= 15 ORDER BY day") suspend fun activityDays(minimumSeconds: Int): List<String>

    // ---- Student exam mode ----
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveExamSubject(subject: ExamSubject): Long
    @Query("SELECT * FROM exam_subjects ORDER BY name COLLATE NOCASE") suspend fun examSubjects(): List<ExamSubject>
    @Query("DELETE FROM exam_subjects WHERE id = :id") suspend fun deleteExamSubject(id: Long)
    @Query("UPDATE exam_subjects SET name = :name, targetMinutes = :targetMinutes WHERE id = :id") suspend fun updateExamSubject(id: Long, name: String, targetMinutes: Int)
    @Query("DELETE FROM exam_events WHERE subjectId = :subjectId") suspend fun deleteExamsForSubject(subjectId: Long)
    @Query("DELETE FROM study_logs WHERE subjectId = :subjectId") suspend fun deleteStudyLogsForSubject(subjectId: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveExamEvent(event: ExamEvent): Long
    @Query("SELECT * FROM exam_events ORDER BY examDate ASC") suspend fun examEvents(): List<ExamEvent>
    @Query("DELETE FROM exam_events WHERE id = :id") suspend fun deleteExamEvent(id: Long)
    @Insert suspend fun addStudyLog(log: StudyLog): Long
    @Query("DELETE FROM study_logs WHERE id = :id") suspend fun deleteStudyLog(id: Long)
    @Query("SELECT COALESCE(SUM(minutes),0) FROM study_logs WHERE subjectId = :subjectId") suspend fun subjectStudyMinutes(subjectId: Long): Int
    @Query("SELECT COALESCE(SUM(minutes),0) FROM study_logs WHERE subjectId = :subjectId AND day = :day") suspend fun subjectStudyMinutesForDay(subjectId: Long, day: String): Int
    @Query("SELECT * FROM study_logs ORDER BY createdAt DESC LIMIT :limit") suspend fun recentStudyLogs(limit: Int): List<StudyLog>
}

data class AppUsageTotal(val packageName: String, val appLabel: String, val durationMillis: Long, val sessions: Int)
data class DailyUsageTotal(val day: String, val durationMillis: Long)
data class KnownApp(val packageName: String, val appLabel: String)

@Database(
    entities = [UsageSession::class, AppLimit::class, PlannerItem::class, PlannerDone::class, FocusSession::class, Habit::class, HabitLog::class, XpTransaction::class, DailyScore::class, AchievementUnlock::class, ExamSubject::class, ExamEvent::class, StudyLog::class, AppCategoryOverride::class, CategoryLimit::class],
    version = 8,
    exportSchema = false
)
abstract class timevoraDatabase : RoomDatabase() {
    abstract fun usageDao(): UsageDao
    companion object {
        @Volatile private var INSTANCE: timevoraDatabase? = null
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS app_limits (packageName TEXT NOT NULL, appLabel TEXT NOT NULL, limitMinutes INTEGER NOT NULL, enabled INTEGER NOT NULL, alert50 INTEGER NOT NULL, alert80 INTEGER NOT NULL, alert100 INTEGER NOT NULL, PRIMARY KEY(packageName))")
            }
        }
        // Adds the diary/planner and focus tables. Existing usage history is untouched.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS planner_items (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, notes TEXT NOT NULL, kind TEXT NOT NULL, startDay TEXT NOT NULL, minuteOfDay INTEGER NOT NULL, repeatMode TEXT NOT NULL, remindBefore INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS planner_done (itemId INTEGER NOT NULL, day TEXT NOT NULL, PRIMARY KEY(itemId, day))")
                db.execSQL("CREATE TABLE IF NOT EXISTS focus_sessions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, day TEXT NOT NULL, startMillis INTEGER NOT NULL, plannedSeconds INTEGER NOT NULL, actualSeconds INTEGER NOT NULL, label TEXT NOT NULL)")
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS habits (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, category TEXT NOT NULL, schedule TEXT NOT NULL, active INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_habits_active_createdAt ON habits(active, createdAt)")
                db.execSQL("CREATE TABLE IF NOT EXISTS habit_logs (habitId INTEGER NOT NULL, day TEXT NOT NULL, completedAt INTEGER NOT NULL, PRIMARY KEY(habitId, day))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_habit_logs_day ON habit_logs(day)")
                db.execSQL("CREATE TABLE IF NOT EXISTS xp_transactions (eventKey TEXT NOT NULL, day TEXT NOT NULL, points INTEGER NOT NULL, reason TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(eventKey))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_xp_transactions_day ON xp_transactions(day)")
                db.execSQL("CREATE TABLE IF NOT EXISTS daily_scores (day TEXT NOT NULL, score INTEGER NOT NULL, focusScore INTEGER NOT NULL, planScore INTEGER NOT NULL, habitScore INTEGER NOT NULL, balanceScore INTEGER NOT NULL, calculatedAt INTEGER NOT NULL, PRIMARY KEY(day))")
            }
        }
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS achievement_unlocks (achievementId TEXT NOT NULL, unlockedAt INTEGER NOT NULL, progressAtUnlock INTEGER NOT NULL, PRIMARY KEY(achievementId))")
            }
        }
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS exam_subjects (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, colorHex TEXT NOT NULL, targetMinutes INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS exam_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, subjectId INTEGER NOT NULL, examDate TEXT NOT NULL, notes TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_exam_events_examDate ON exam_events(examDate)")
                db.execSQL("CREATE TABLE IF NOT EXISTS study_logs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, subjectId INTEGER NOT NULL, day TEXT NOT NULL, minutes INTEGER NOT NULL, note TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_study_logs_day ON study_logs(day)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_study_logs_subjectId_day ON study_logs(subjectId, day)")
            }
        }
        // Focus Forest: remember how much phone use leaked into a session and whether its tree withered.
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE focus_sessions ADD COLUMN leakedSeconds INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE focus_sessions ADD COLUMN withered INTEGER NOT NULL DEFAULT 0")
            }
        }
        // Smart categories: manual app categories and per-category daily limits.
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS app_categories (packageName TEXT NOT NULL, category TEXT NOT NULL, PRIMARY KEY(packageName))")
                db.execSQL("CREATE TABLE IF NOT EXISTS category_limits (category TEXT NOT NULL, limitMinutes INTEGER NOT NULL, enabled INTEGER NOT NULL, alert50 INTEGER NOT NULL, alert80 INTEGER NOT NULL, alert100 INTEGER NOT NULL, PRIMARY KEY(category))")
            }
        }
        fun get(context: Context): timevoraDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, timevoraDatabase::class.java, "timevora.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).build().also { INSTANCE = it }
        }
    }
}

fun todayKey(): String = LocalDate.now().toString()
