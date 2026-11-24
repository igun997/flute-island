package dev.igun997.fluteisland.data

import android.content.Context

/**
 * Remembers the last crash. Android marks a crashed accessibility service as "crashed" and does not restart it, so the
 * island would just vanish. Writing the reason down lets the control room say what happened and offer a restart.
 */
object CrashGuard {
    private const val PREFS = "crash"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong("at", System.currentTimeMillis())
                    .putString("what", "${error.javaClass.simpleName}: ${error.message.orEmpty().take(140)}")
                    .commit() // synchronous: the process is about to die
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The last recorded crash as (time, description), or null. */
    fun last(context: Context): Pair<Long, String>? {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = p.getLong("at", 0L)
        return if (at == 0L) null else at to p.getString("what", "").orEmpty()
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
