package com.example.keyboardlauncher

import android.content.Context
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.GestureDetectorCompat
import androidx.fragment.app.Fragment
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

data class TodoItem(
    var name: String,
    var targetMinutes: Int,
    var spentMs: Long = 0L,
    var done: Boolean = false
)

class FocusFragment : Fragment() {

    // UI
    private lateinit var timerTitle: TextView
    private lateinit var timeText: TextView
    private lateinit var todayText: TextView
    private lateinit var totalText: TextView
    private lateinit var scheduleTitle: TextView
    private lateinit var scheduleText: TextView
    private lateinit var deadlinesTitle: TextView
    private lateinit var deadlinesText: TextView

    // TODO UI container
    private lateinit var todoTitle: TextView
    private lateinit var todoContainer: LinearLayout

    // Timing
    private val uiHandler = Handler(Looper.getMainLooper())
    private var uiTickIntervalMs = 50L // 50ms update

    // State
    private var isRunning = false
    private var isPaused = false

    // Times (millis)
    private var totalTime = 25 * 60 * 1000L
    private var remainingMs = totalTime

    // For precise tracking
    private var endTime = 0L
    private var runningStartTime = 0L
    private var sessionElapsedMs = 0L // accumulated during this session (ms)

    private lateinit var gestureDetector: GestureDetectorCompat

    // Storage keys
    private val PREFS = "focus_prefs"
    private val DURATION_KEY = "duration"
    private val TODAY_DATE_KEY = "today_date"
    private val TODAY_MIN_KEY = "today_minutes"
    private val TOTAL_MIN_KEY = "total_minutes"
    private val DEADLINES_RAW_KEY = "deadlines_raw"
    private val SCHEDULE_RAW_KEY = "schedule_raw"
    private val TODOS_RAW_KEY = "todos_raw"

    // TODOs
    private val todos = mutableListOf<TodoItem>()
    private var activeTodoIndex: Int = -1

    private val tickRunnable = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val rem = (endTime - now).coerceAtLeast(0L)
            updateTimeText(rem)
            updateTodoSpentsLive()
            if (rem > 0L && isRunning) {
                uiHandler.postDelayed(this, uiTickIntervalMs)
            } else if (rem <= 0L && isRunning) {
                accumulateRunningTimeIfAny()
                finishSession()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_focus, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        timerTitle = view.findViewById(R.id.timerTitle)
        timeText = view.findViewById(R.id.timeText)
        todayText = view.findViewById(R.id.todayText)
        totalText = view.findViewById(R.id.totalText)
        scheduleTitle = view.findViewById(R.id.scheduleTitle)
        scheduleText = view.findViewById(R.id.scheduleText)
        deadlinesTitle = view.findViewById(R.id.deadlinesTitle)
        deadlinesText = view.findViewById(R.id.deadlinesText)

        todoTitle = view.findViewById(R.id.todoTitle)
        todoContainer = view.findViewById(R.id.todoContainer)

        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // titles
        timerTitle.text = "Timer"
        scheduleTitle.text = "Schedule"
        deadlinesTitle.text = "Deadlines"
        todoTitle.text = "TODO"

        totalTime = prefs.getLong(DURATION_KEY, totalTime)
        remainingMs = totalTime
        updateTimeText(remainingMs)

        handleDateReset(prefs)
        updateStats(prefs)
        updateSchedule()
        updateDeadlines()
        loadTodos(prefs)
        renderTodos()

        setupSwipeGestures(prefs)

        // Tap the time text:
        timeText.setOnClickListener {
            when {
                isRunning -> pauseTimer()
                isPaused -> resumeTimer()
                else -> startTimer(totalTime, resetSession = true)
            }
        }

        // Long-press time -> ALWAYS stop and save session
        timeText.setOnLongClickListener {
            stopTimer(prefs)
            true
        }

        // Long press schedule title or content → edit
        scheduleTitle.setOnLongClickListener {
            showScheduleEditor()
            true
        }
        scheduleText.setOnLongClickListener {
            showScheduleEditor()
            true
        }

        // Long press deadlines title or content → edit
        deadlinesTitle.setOnLongClickListener {
            showDeadlinesEditor()
            true
        }
        deadlinesText.setOnLongClickListener {
            showDeadlinesEditor()
            true
        }

        // Long press TODO title or container → edit TODOs
        todoTitle.setOnLongClickListener {
            showTodoEditor()
            true
        }
        todoContainer.setOnLongClickListener {
            showTodoEditor()
            true
        }
    }

    // ───────────────── TODO Persistence & UI ─────────────────

    private fun loadTodos(prefs: android.content.SharedPreferences) {
        todos.clear()
        val raw = prefs.getString(TODOS_RAW_KEY, null) ?: return
        try {
            val arr = JSONArray(raw)
            var i = 0
            while (i < arr.length()) {
                val o = arr.optJSONObject(i)
                if (o != null) {
                    val name = o.optString("name", "")
                    if (name.isNotEmpty()) {
                        val target = o.optInt("target", 30)
                        val spent = o.optLong("spent", 0L)
                        val done = o.optBoolean("done", false)
                        todos.add(TodoItem(name, target, spent, done))
                    }
                }
                i++
            }
        } catch (_: Exception) {
        }
    }

    private fun saveTodos(prefs: android.content.SharedPreferences) {
        try {
            val arr = JSONArray()
            for (t in todos) {
                val o = JSONObject()
                o.put("name", t.name)
                o.put("target", t.targetMinutes)
                o.put("spent", t.spentMs)
                o.put("done", t.done)
                arr.put(o)
            }
            prefs.edit().putString(TODOS_RAW_KEY, arr.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private fun renderTodos() {
        todoContainer.removeAllViews()
        val ctx = requireContext()
        for ((index, todo) in todos.withIndex()) {
            val row = LinearLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.HORIZONTAL
                setPadding(24, 8, 24, 8)
                gravity = Gravity.CENTER_VERTICAL
            }

            val cb = CheckBox(ctx).apply {
                isChecked = todo.done
                setOnCheckedChangeListener { _, isChecked ->
                    todo.done = isChecked
                    val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    saveTodos(prefs)
                }
            }

            val nameTv = TextView(ctx).apply {
                text = todo.name
                setTextColor(0xFFAAAAAA.toInt())
                textSize = 15f
                setPadding(12, 0, 24, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val targetTv = TextView(ctx).apply {
                text = "${todo.targetMinutes} min"
                setTextColor(0xFFAAAAAA.toInt())
                textSize = 14f
                setPadding(12, 0, 12, 0)
            }

            val spentTv = TextView(ctx).apply {
                tag = "spent_$index"
                text = spentTextForTodo(todo, false)
                setTextColor(0xFFAAAAAA.toInt())
                textSize = 14f
                setPadding(12, 0, 0, 0)
            }

            row.addView(cb)
            row.addView(nameTv)
            row.addView(targetTv)
            row.addView(spentTv)

            // clicking the row selects/activates the todo and starts timer if idle
            row.setOnClickListener {
                if (activeTodoIndex != index) {
                    activeTodoIndex = index
                    highlightActiveTodo()
                }
                if (!isRunning && !isPaused) {
                    startTimer(totalTime, resetSession = true)
                }
            }

            todoContainer.addView(row)
        }
        highlightActiveTodo()
    }

    private fun highlightActiveTodo() {
        for (i in 0 until todoContainer.childCount) {
            val row = todoContainer.getChildAt(i)
            row.alpha = if (i == activeTodoIndex) 1.0f else 0.85f
        }
    }

    private fun spentTextForTodo(todo: TodoItem, includeRunningNow: Boolean): String {
        val now = SystemClock.elapsedRealtime()
        val runningAdd = if (includeRunningNow && isRunning && activeTodoIndex >= 0) {
            val active = todos.getOrNull(activeTodoIndex)
            if (active === todo) {
                now - runningStartTime
            } else 0L
        } else 0L
        val spentMs = todo.spentMs + runningAdd
        val minutes = (spentMs / 60000).toInt()
        return "$minutes min"
    }

    private fun updateTodoSpentsLive() {
        val now = SystemClock.elapsedRealtime()
        var i = 0
        while (i < todos.size) {
            val tv = todoContainer.findViewWithTag<TextView>("spent_$i")
            if (tv != null) {
                val t = todos[i]
                val runningAdd = if (isRunning && activeTodoIndex == i) (now - runningStartTime) else 0L
                val valueMs = t.spentMs + runningAdd
                val minutes = (valueMs / 60000).toInt()
                tv.text = "$minutes min"
                if (!t.done && minutes >= t.targetMinutes) {
                    t.done = true
                    val row = todoContainer.getChildAt(i) as? ViewGroup
                    val cb = row?.getChildAt(0) as? CheckBox
                    cb?.isChecked = true
                    val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    saveTodos(prefs)
                }
            }
            i++
        }
    }

    // accumulate running time into sessionElapsedMs and active todo when pausing/stopping
    private fun accumulateRunningTimeIfAny() {
        if (isRunning) {
            val now = SystemClock.elapsedRealtime()
            val ran = (now - runningStartTime).coerceAtLeast(0L)
            sessionElapsedMs += ran
            if (activeTodoIndex >= 0 && activeTodoIndex < todos.size) {
                val t = todos[activeTodoIndex]
                t.spentMs += ran
                val minutes = (t.spentMs / 60000).toInt()
                if (!t.done && minutes >= t.targetMinutes) t.done = true
                val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                saveTodos(prefs)
            }
            runningStartTime = now
        }
    }

    // ───────────────── TIMER (Handler-based for ms precision) ─────────────────

    private fun startTimer(duration: Long, resetSession: Boolean = true) {
        if (resetSession) sessionElapsedMs = 0L
        isRunning = true
        isPaused = false
        val now = SystemClock.elapsedRealtime()
        runningStartTime = now
        endTime = now + duration
        remainingMs = duration
        uiHandler.removeCallbacks(tickRunnable)
        uiHandler.post(tickRunnable)
    }

    private fun pauseTimer() {
        if (!isRunning) return
        accumulateRunningTimeIfAny()
        val now = SystemClock.elapsedRealtime()
        remainingMs = (endTime - now).coerceAtLeast(0L)
        uiHandler.removeCallbacks(tickRunnable)
        isRunning = false
        isPaused = true
        renderTodos()
    }

    private fun resumeTimer() {
        if (!isPaused) return
        val now = SystemClock.elapsedRealtime()
        runningStartTime = now
        endTime = now + remainingMs
        isRunning = true
        isPaused = false
        uiHandler.removeCallbacks(tickRunnable)
        uiHandler.post(tickRunnable)
    }

    /**
     * Stops the timer and saves elapsed session minutes.
     */
    private fun stopTimer(prefs: android.content.SharedPreferences) {
        accumulateRunningTimeIfAny()
        uiHandler.removeCallbacks(tickRunnable)
        isRunning = false
        isPaused = false
        saveElapsed(prefs)
        saveTodos(prefs)
        remainingMs = totalTime
        updateTimeText(totalTime)
        updateStats(prefs)
        sessionElapsedMs = 0L
        activeTodoIndex = -1
        renderTodos()
    }

    private fun stopAndReset(prefs: android.content.SharedPreferences) {
        uiHandler.removeCallbacks(tickRunnable)
        isRunning = false
        isPaused = false
        sessionElapsedMs = 0L
        remainingMs = totalTime
        updateTimeText(totalTime)
        updateStats(prefs)
    }

    private fun finishSession() {
        accumulateRunningTimeIfAny()
        isRunning = false
        isPaused = false
        uiHandler.removeCallbacks(tickRunnable)
        notifyCompletion()
        timeText.text = "DONE"
        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        saveElapsed(prefs)
        saveTodos(prefs)
        updateStats(prefs)
        sessionElapsedMs = 0L
        remainingMs = totalTime
        activeTodoIndex = -1
        renderTodos()
    }

    // vibrate and play default notification sound
    private fun notifyCompletion() {
        val ctx = context ?: return
        try {
            val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vib != null && vib.hasVibrator()) {
                val durationMs = 400L
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(durationMs)
                }
            }
        } catch (_: Exception) {
        }
        try {
            val notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val r = RingtoneManager.getRingtone(ctx, notification)
            r?.play()
        } catch (_: Exception) {
        }
    }

    // ───────────────── DURATION ─────────────────

    private fun toggleDuration(prefs: android.content.SharedPreferences) {
        totalTime = if (totalTime == 25 * 60 * 1000L) 50 * 60 * 1000L else 25 * 60 * 1000L
        remainingMs = totalTime
        prefs.edit().putLong(DURATION_KEY, totalTime).apply()
        updateTimeText(totalTime)
    }

    private fun setupSwipeGestures(prefs: android.content.SharedPreferences) {
        gestureDetector = GestureDetectorCompat(
            requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                // Use non-null MotionEvent parameters to match the Java API signature exactly.
                override fun onFling(e1: MotionEvent, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                    if (isRunning || isPaused) return false
                    val dy = e2.y - e1.y
                    if (kotlin.math.abs(dy) > 80) {
                        changeDuration(if (dy < 0) +5 else -5)
                        val prefsLocal = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        prefsLocal.edit().putLong(DURATION_KEY, totalTime).apply()
                        remainingMs = totalTime
                        updateTimeText(totalTime)
                        return true
                    }
                    return false
                }
            }
        )

        timeText.setOnTouchListener { _, e ->
            gestureDetector.onTouchEvent(e)
            false
        }
    }

    private fun changeDuration(delta: Int) {
        val min = (totalTime / 60000).toInt() + delta
        totalTime = min.coerceIn(5, 95) * 60 * 1000L
    }

    // ───────────────── STATS ─────────────────

    private fun saveElapsed(prefs: android.content.SharedPreferences) {
        val min = ((sessionElapsedMs + 30_000L) / 60_000L).toInt()
        if (min <= 0) return
        handleDateReset(prefs)
        prefs.edit()
            .putInt(TODAY_MIN_KEY, prefs.getInt(TODAY_MIN_KEY, 0) + min)
            .putInt(TOTAL_MIN_KEY, prefs.getInt(TOTAL_MIN_KEY, 0) + min)
            .apply()
        sessionElapsedMs = 0L
    }

    private fun handleDateReset(prefs: android.content.SharedPreferences) {
        val today = currentDate()
        if (prefs.getString(TODAY_DATE_KEY, null) != today) {
            prefs.edit()
                .putString(TODAY_DATE_KEY, today)
                .putInt(TODAY_MIN_KEY, 0)
                .apply()
            for (t in todos) {
                t.spentMs = 0L
                t.done = false
            }
            saveTodos(prefs)
            renderTodos()
        }
    }

    private fun updateStats(prefs: android.content.SharedPreferences) {
        todayText.text = "Today: ${prefs.getInt(TODAY_MIN_KEY, 0)} min"
        val t = prefs.getInt(TOTAL_MIN_KEY, 0)
        totalText.text = "Total: ${t / 60} hr ${t % 60} min"
    }

    // ───────────────── SCHEDULE / DEADLINES / TODO Editor ─────────────────

    private fun defaultSchedule(): String =
        """
Mon
09:00–10:00 UMT 203
11:00–12:00 QT 204

Tue
09:00–10:00 QT 202
11:00–12:00 UMT 204
""".trimIndent()

    private fun updateSchedule() {
        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(SCHEDULE_RAW_KEY, null) ?: defaultSchedule().also {
            prefs.edit().putString(SCHEDULE_RAW_KEY, it).apply()
        }

        val day = SimpleDateFormat("EEE", Locale.US).format(Date())
        val blocks = raw.split("\n\n")
        var found: String? = null
        for (b in blocks) {
            if (b.startsWith(day)) {
                found = b
                break
            }
        }
        val block = found?.substringAfter("\n") ?: "No classes today"
        scheduleText.text = block
    }

    private fun showScheduleEditor() {
        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val edit = EditText(requireContext()).apply {
            setText(prefs.getString(SCHEDULE_RAW_KEY, defaultSchedule()))
            minLines = 10
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Edit Schedule")
            .setView(edit)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString(SCHEDULE_RAW_KEY, edit.text.toString()).apply()
                updateSchedule()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun defaultDeadlines(): String =
        """
ASSIGN | QT 204 HW3 | 2026-01-16
EXAM | QT 204 – Midsem | 2026-01-21
QUIZ | UMT 203 Quiz | 2026-01-22
""".trimIndent()

    private fun updateDeadlines() {
        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(DEADLINES_RAW_KEY, null) ?: defaultDeadlines().also {
            prefs.edit().putString(DEADLINES_RAW_KEY, it).apply()
        }

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.time

        val sb = StringBuilder()
        val lines = raw.lines()
        for (ln in lines) {
            val parts = ln.split("|").map { s -> s.trim() }
            if (parts.size != 3) continue
            val date = try { sdf.parse(parts[2]) } catch (_: Exception) { null } ?: continue
            val days = ((date.time - today.time) / 86400000).toInt()
            if (days < 0) continue
            val d = SimpleDateFormat("EEE, dd MMM", Locale.US).format(date)
            sb.append("${parts[0]} ${parts[1]} in $days days ($d)\n\n")
        }

        deadlinesText.text = sb.toString().trimEnd()
    }

    private fun showDeadlinesEditor() {
        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val edit = EditText(requireContext()).apply {
            setText(prefs.getString(DEADLINES_RAW_KEY, defaultDeadlines()))
            minLines = 8
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Edit Deadlines")
            .setView(edit)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString(DEADLINES_RAW_KEY, edit.text.toString()).apply()
                updateDeadlines()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * TODO Editor:
     * - simple text editor, one item per line:
     *   NAME | TARGET_MIN
     * - existing spent/done retained for items with the same name; otherwise new items added with spent=0.
     */
    private fun showTodoEditor() {
        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val edit = EditText(requireContext()).apply {
            minLines = 8
            val sb = StringBuilder()
            for (t in todos) {
                sb.append("${t.name} | ${t.targetMinutes}\n")
            }
            setText(sb.toString().trimEnd())
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Edit TODOs (one per line: NAME | TARGET_MIN)")
            .setView(edit)
            .setPositiveButton("Save") { _, _ ->
                val mapOld = todos.associateBy { it.name }
                val lines = edit.text.toString().lines()
                val newList = mutableListOf<TodoItem>()
                for (ln in lines) {
                    val parts = ln.split("|").map { s -> s.trim() }
                    if (parts.isEmpty() || parts[0].isEmpty()) continue
                    val name = parts[0]
                    val target = parts.getOrNull(1)?.toIntOrNull() ?: 30
                    val old = mapOld[name]
                    if (old != null) {
                        newList.add(TodoItem(name, target, old.spentMs, old.done))
                    } else {
                        newList.add(TodoItem(name, target, 0L, false))
                    }
                }
                todos.clear()
                todos.addAll(newList)
                saveTodos(prefs)
                renderTodos()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Format mm:ss.SSS
    private fun updateTimeText(ms: Long) {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val millis = ms % 1000
        timeText.text = String.format("%02d:%02d.%03d", minutes, seconds, millis)
    }

    private fun currentDate(): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    override fun onDestroyView() {
        super.onDestroyView()
        uiHandler.removeCallbacks(tickRunnable)
    }
}