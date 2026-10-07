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
import dev.suyash.dot.core.domain.repeat.Frequency
import dev.suyash.dot.core.domain.repeat.RepeatEnd
import dev.suyash.dot.core.domain.repeat.RepeatRule
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
    fun delete_erasesContent_andRestoreBringsItBack() = runTest {
        val list = commands.ensureDefaultList()
        val id = commands.createTask(TaskDraft(list, "Secret plan", notes = "details", starred = true, repeat = RepeatRule(Frequency.DAILY)))
        val deleted = commands.delete(id)
        assertThat(repository.observeTasks(list).first()).isEmpty()
        val row = db.taskDao().get(id.value)!!
        assertThat(row.deleted).isTrue()
        assertThat(row.title).isEmpty()
        assertThat(row.notes).isEmpty()
        assertThat(row.recurrence).isNull()
        assertThat(row.starred).isFalse()
        commands.restore(deleted)
        val back = repository.getTask(id)!!
        assertThat(back.title).isEqualTo("Secret plan")
        assertThat(back.notes).isEqualTo("details")
        assertThat(back.isStarred).isTrue()
        assertThat(back.repeat).isEqualTo(RepeatRule(Frequency.DAILY))
    }

    @Test
    fun completingARepeatingTask_schedulesTheNextOccurrence() = runTest {
        val list = commands.ensureDefaultList()
        val due = LocalDate.parse("2026-10-02")
        val at = ZonedDateTime.parse("2026-10-02T18:00+05:30[Asia/Kolkata]").toInstant()
        val id = commands.createTask(TaskDraft(list, "Gym", dueDate = due, reminder = Reminder(at, zone), repeat = RepeatRule(Frequency.DAILY)))
        commands.setDone(id, true)

        val next = repository.getTask(id.successor())!!
        assertThat(next.title).isEqualTo("Gym")
        assertThat(next.isDone).isFalse()
        assertThat(next.dueDate).isEqualTo(due.plusDays(1))
        assertThat(next.reminder!!.at).isEqualTo(at.plusSeconds(24 * 3600))
        assertThat(next.repeat).isEqualTo(RepeatRule(Frequency.DAILY))
        val done = repository.getTask(id)!!
        assertThat(done.isDone).isTrue()
        assertThat(done.repeat).isNull() // the series moved on
        assertThat(repository.nextPendingReminder()?.id).isEqualTo(next.id)
    }

    @Test
    fun undoingACompletion_takesBackTheNextOccurrence() = runTest {
        val list = commands.ensureDefaultList()
        val rule = RepeatRule(Frequency.WEEKLY, end = RepeatEnd.AfterCount(3))
        val id = commands.createTask(TaskDraft(list, "Report", dueDate = LocalDate.parse("2026-10-02"), repeat = rule))
        commands.setDone(id, true)
        assertThat(repository.getTask(id.successor())!!.repeat?.end).isEqualTo(RepeatEnd.AfterCount(2))
        commands.setDone(id, false)
        assertThat(repository.getTask(id.successor())).isNull()
        val task = repository.getTask(id)!!
        assertThat(task.isDone).isFalse()
        assertThat(task.repeat).isEqualTo(rule.anchoredTo(LocalDate.parse("2026-10-02")))
    }

    @Test
    fun completingLate_skipsOccurrencesThatAreAlreadyOver() = runTest {
        val list = commands.ensureDefaultList() // today is 2026-10-02
        val id = commands.createTask(TaskDraft(list, "Water plants", dueDate = LocalDate.parse("2026-09-28"), repeat = RepeatRule(Frequency.DAILY)))
        commands.setDone(id, true)
        assertThat(repository.getTask(id.successor())!!.dueDate).isEqualTo(LocalDate.parse("2026-10-02"))
    }

    @Test
    fun theLastCountedOccurrence_endsTheSeries() = runTest {
        val list = commands.ensureDefaultList()
        val id = commands.createTask(
            TaskDraft(list, "Course", dueDate = LocalDate.parse("2026-10-02"), repeat = RepeatRule(Frequency.DAILY, end = RepeatEnd.AfterCount(1))),
        )
        commands.setDone(id, true)
        assertThat(repository.getTask(id.successor())).isNull()
    }

    @Test
    fun subtasks_completeWithTheirParent_andComeBackOnUndo() = runTest {
        val list = commands.ensureDefaultList()
        val parent = commands.createTask(TaskDraft(list, "Trip"))
        val passport = commands.addSubtask(parent, "Passport")!!
        val tickets = commands.addSubtask(parent, "Tickets")!!
        now = now.plusSeconds(1)
        commands.setDone(tickets, true) // done on its own, before the parent
        now = now.plusSeconds(1)
        commands.setDone(parent, true)
        assertThat(repository.getTask(passport)!!.isDone).isTrue()
        commands.setDone(parent, false)
        assertThat(repository.getTask(passport)!!.isDone).isFalse()
        assertThat(repository.getTask(tickets)!!.isDone).isTrue() // wasn't completed by the parent
        // Subtasks are listed after their parent, in order.
        assertThat(repository.observeTasks(list).first().filter { it.parentId == parent }.sortedBy { it.position }.map { it.title })
            .containsExactly("Passport", "Tickets").inOrder()
    }

    @Test
    fun repeatingParent_getsAFreshChecklist() = runTest {
        val list = commands.ensureDefaultList()
        val parent = commands.createTask(TaskDraft(list, "Weekly review", dueDate = LocalDate.parse("2026-10-02"), repeat = RepeatRule(Frequency.WEEKLY)))
        val sub = commands.addSubtask(parent, "Inbox zero")!!
        commands.setDone(parent, true)
        val freshSub = repository.getTask(sub.successor())!!
        assertThat(freshSub.parentId).isEqualTo(parent.successor())
        assertThat(freshSub.isDone).isFalse()
        assertThat(freshSub.title).isEqualTo("Inbox zero")
    }

    @Test
    fun deletingAParent_deletesItsSubtasks_andUndoRestoresAll() = runTest {
        val list = commands.ensureDefaultList()
        val parent = commands.createTask(TaskDraft(list, "Party"))
        val sub = commands.addSubtask(parent, "Cake")!!
        val deleted = commands.delete(parent)
        assertThat(deleted.map { it.id }).containsExactly(parent, sub)
        assertThat(repository.getTask(sub)).isNull()
        commands.restore(deleted)
        assertThat(repository.getTask(sub)!!.title).isEqualTo("Cake")
    }

    @Test
    fun deleteCompleted_removesOnlyCompletedTasks() = runTest {
        val list = commands.ensureDefaultList()
        val open = commands.createTask(TaskDraft(list, "Open"))
        val done = commands.createTask(TaskDraft(list, "Done"))
        commands.setDone(done, true)
        assertThat(commands.deleteCompleted(list)).isEqualTo(1)
        assertThat(repository.observeTasks(list).first().map { it.id }).containsExactly(open)
    }

    @Test
    fun moveToList_takesSubtasksAlong() = runTest {
        val home = commands.ensureDefaultList()
        val work = commands.createList("Work")
        val parent = commands.createTask(TaskDraft(home, "Deck"))
        val sub = commands.addSubtask(parent, "Charts")!!
        commands.moveToList(parent, work)
        assertThat(repository.observeTasks(work).first().map { it.id }).containsExactly(parent, sub)
        assertThat(repository.observeTasks(home).first()).isEmpty()
    }

    @Test
    fun duplicate_copiesBelowTheOriginal_withSubtasks() = runTest {
        val list = commands.ensureDefaultList()
        val below = commands.createTask(TaskDraft(list, "Below"))
        val original = commands.createTask(TaskDraft(list, "Original"))
        commands.addSubtask(original, "Step")
        val copy = commands.duplicate(original)!!
        val topLevel = repository.observeTasks(list).first().filter { it.parentId == null }.sortedBy { it.position }
        assertThat(topLevel.map { it.id }).containsExactly(original, copy, below).inOrder()
        assertThat(repository.observeTasks(list).first().filter { it.parentId == copy }.map { it.title }).containsExactly("Step")
    }

    @Test
    fun oldDeletionMarkers_arePurged_onceUploaded() = runTest {
        val list = commands.ensureDefaultList()
        val id = commands.createTask(TaskDraft(list, "Gone"))
        commands.delete(id)
        val cutoff = now.plusSeconds(1).toEpochMilli()
        // Still waiting to be uploaded: kept, unless nothing will ever upload it (signed out).
        assertThat(store.purgeTombstones(cutoff, includeUnpushed = false)).isEqualTo(0)
        assertThat(store.purgeTombstones(cutoff, includeUnpushed = true)).isEqualTo(1)
        assertThat(db.taskDao().get(id.value)).isNull()
        assertThat(store.pendingOutbox(10).map { it.recordId }).doesNotContain(id.value)
    }

    @Test
    fun recordsGoneFromTheCloud_areDroppedLocally() = runTest {
        val list = commands.ensureDefaultList()
        val kept = commands.createTask(TaskDraft(list, "Kept"))
        val gone = commands.createTask(TaskDraft(list, "Deleted elsewhere"))
        db.taskDao().setServerVersion(kept.value, 3)
        db.taskDao().setServerVersion(gone.value, 2)
        val unsynced = commands.createTask(TaskDraft(list, "Not uploaded yet"))
        val dropped = store.dropAllGone { _, id -> id == kept.value || id == list.value }
        assertThat(dropped).isEqualTo(1)
        assertThat(repository.getTask(gone)).isNull()
        assertThat(repository.getTask(kept)).isNotNull()
        assertThat(repository.getTask(unsynced)).isNotNull() // never synced, so never "gone"
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
        var keyReads = 0
        val encrypted = DotDatabase.encrypted(context) {
            keyReads++
            "x'${"ab".repeat(32)}'".toByteArray()
        }
        // Building the database must not read the key; the first query does.
        assertThat(keyReads).isEqualTo(0)
        runTest {
            TaskCommands(encrypted, clock, HlcClock("n"), ChangeNotifier(emptySet())).ensureDefaultList()
        }
        assertThat(keyReads).isEqualTo(1)
        encrypted.close()
        val header = File(context.getDatabasePath(DotDatabase.FILE_NAME).path).readBytes().copyOf(16)
        assertThat(String(header, Charsets.US_ASCII)).doesNotContain("SQLite format 3")
        DotDatabase.deleteFiles(context)
    }
}
