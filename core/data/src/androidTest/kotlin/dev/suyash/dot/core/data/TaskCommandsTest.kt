package dev.suyash.dot.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.suyash.dot.core.data.db.DotDatabase
import dev.suyash.dot.core.data.sync.SyncStore
import dev.suyash.dot.core.domain.events.ChangeOrigin
import dev.suyash.dot.core.domain.events.TaskChange
import dev.suyash.dot.core.domain.events.TaskChangeObserver
import dev.suyash.dot.core.domain.model.Reminder
import dev.suyash.dot.core.domain.model.RingMode
import dev.suyash.dot.core.domain.sync.Hlc
import dev.suyash.dot.core.domain.sync.HlcClock
import dev.suyash.dot.core.domain.sync.TaskField
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
class TaskCommandsTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private var now = Instant.parse("2026-10-02T04:30:00Z") // 10:00 IST
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = this@TaskCommandsTest.zone
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private lateinit var db: DotDatabase
    private lateinit var commands: TaskCommands
    private lateinit var store: SyncStore
    private lateinit var repository: TaskRepository
    private val changes = mutableListOf<TaskChange>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, DotDatabase::class.java).build()
        val notifier = ChangeNotifier(setOf(TaskChangeObserver { changes += it }))
        val hlc = HlcClock("test-node", wallClock = { now.toEpochMilli() })
        commands = TaskCommands(db, clock, hlc, notifier)
        store = SyncStore(db, hlc, notifier)
        repository = TaskRepository(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun createTask_isVisible_andQueuedForSync() = runTest {
        val list = commands.ensureDefaultList()
        val id = commands.createTask(TaskDraft(listId = list, title = "  Buy milk  "))
        val tasks = repository.observeTasks(list).first()
        assertThat(tasks.map { it.title }).containsExactly("Buy milk")
        assertThat(store.pendingOutbox(10).map { it.recordId }).containsAtLeast(id.value, list.value)
        assertThat(changes.last().origin).isEqualTo(ChangeOrigin.LOCAL)
    }

    @Test
    fun newTasksGoOnTop() = runTest {
        val list = commands.ensureDefaultList()
        commands.createTask(TaskDraft(list, "first"))
        commands.createTask(TaskDraft(list, "second"))
        assertThat(repository.observeTasks(list).first().map { it.title }).containsExactly("second", "first").inOrder()
    }

    @Test
    fun snooze_setsReminder_movesEarlierDueDate_andCounts() = runTest {
        val list = commands.ensureDefaultList()
        val id = commands.createTask(TaskDraft(list, "Pay rent", dueDate = LocalDate.parse("2026-10-02")))
        val until = ZonedDateTime.parse("2026-10-03T09:00+05:30[Asia/Kolkata]")
        commands.snooze(id, until)
        val task = repository.getTask(id)!!
        assertThat(task.reminder!!.at).isEqualTo(until.toInstant())
        assertThat(task.reminder!!.snoozeCount).isEqualTo(1)
        assertThat(task.dueDate).isEqualTo(LocalDate.parse("2026-10-03"))
    }

    @Test
    fun firedReminders_areNotPendingUntilSnoozed() = runTest {
        val list = commands.ensureDefaultList()
        val at = Instant.parse("2026-10-02T05:00:00Z")
        val id = commands.createTask(TaskDraft(list, "Call mom", reminder = Reminder(at, zone, RingMode.RING)))
        assertThat(repository.nextPendingReminder()?.id).isEqualTo(id)
        commands.markFired(listOf(id), at)
        assertThat(repository.nextPendingReminder()).isNull()
        commands.snooze(id, ZonedDateTime.ofInstant(at.plusSeconds(600), zone))
        assertThat(repository.nextPendingReminder()?.id).isEqualTo(id)
    }

    @Test
    fun completedTasks_neverRing() = runTest {
        val list = commands.ensureDefaultList()
        val id = commands.createTask(TaskDraft(list, "x", reminder = Reminder(now.plusSeconds(60), zone)))
        commands.setDone(id, true)
        assertThat(repository.dueReminders(now.plusSeconds(3600))).isEmpty()
    }

    @Test
    fun deleteAndRestore() = runTest {
        val list = commands.ensureDefaultList()
        val id = commands.createTask(TaskDraft(list, "temp"))
        commands.delete(id)
        assertThat(repository.observeTasks(list).first()).isEmpty()
        commands.restore(id)
        assertThat(repository.observeTasks(list).first().map { it.id }).containsExactly(id)
    }

    @Test
    fun remoteChanges_mergeFieldByField() = runTest {
        val list = commands.ensureDefaultList()
        val id = commands.createTask(TaskDraft(list, "Buy milk"))
        // Local: completed now.
        now = now.plusSeconds(10)
        commands.setDone(id, true)
        // Remote (another device): renamed *later* than our completion, but didn't touch status.
        val local = store.localTask(id.value)!!.record
        val remote = local.copy(
            task = local.task.copy(title = "Buy oat milk", isDone = false, completedAt = null),
            clocks = local.clocks + (TaskField.TITLE to Hlc(now.toEpochMilli() + 5_000, 0, "other")) +
                (TaskField.STATUS to Hlc(0, 0, "other")),
        )
        store.applyRemote(listOf(remote to 2L), emptyList())
        val merged = repository.getTask(id)!!
        assertThat(merged.title).isEqualTo("Buy oat milk")
        assertThat(merged.isDone).isTrue()
        assertThat(changes.last().origin).isEqualTo(ChangeOrigin.REMOTE)
    }

    @Test
    fun confirmPush_keepsNewerLocalEdit() = runTest {
        val list = commands.ensureDefaultList()
        val id = commands.createTask(TaskDraft(list, "v1"))
        val entry = store.pendingOutbox(10).first { it.recordId == id.value }
        val pushed = store.localTask(id.value)!!.record
        // The user edits again while the push is in flight.
        now = now.plusSeconds(5)
        commands.rename(id, "v2")
        store.confirmTaskPush(entry, pushed, version = 1)
        assertThat(repository.getTask(id)!!.title).isEqualTo("v2")
        // The newer edit is still queued.
        assertThat(store.pendingOutbox(10).map { it.recordId }).contains(id.value)
    }

    @Test
    fun timeZoneChange_keepsWallClockTime() = runTest {
        val list = commands.ensureDefaultList()
        val eightAmIst = ZonedDateTime.parse("2026-10-03T08:00+05:30[Asia/Kolkata]")
        val id = commands.createTask(TaskDraft(list, "Gym", reminder = Reminder(eightAmIst.toInstant(), zone)))
        val london = ZoneId.of("Europe/London")
        commands.rebaseFloatingReminders(london)
        val reminder = repository.getTask(id)!!.reminder!!
        assertThat(reminder.zone).isEqualTo(london)
        assertThat(reminder.at.atZone(london).toLocalTime().toString()).isEqualTo("08:00")
    }

    @Test
    fun encryptedDatabaseFile_isNotPlaintextSqlite() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        DotDatabase.deleteFiles(context)
        val passphrase = "x'${"ab".repeat(32)}'".toByteArray()
        val encrypted = DotDatabase.encrypted(context, passphrase)
        runTest {
            TaskCommands(encrypted, clock, HlcClock("n"), ChangeNotifier(emptySet())).ensureDefaultList()
        }
        encrypted.close()
        val header = File(context.getDatabasePath(DotDatabase.FILE_NAME).path).readBytes().copyOf(16)
        assertThat(String(header, Charsets.US_ASCII)).doesNotContain("SQLite format 3")
        DotDatabase.deleteFiles(context)
    }
}
