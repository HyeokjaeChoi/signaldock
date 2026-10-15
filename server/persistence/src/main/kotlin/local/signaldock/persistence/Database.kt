package local.signaldock.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.postgresql.PGConnection
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

/** Local defaults, not the performance measurement profile. All times are milliseconds. */
data class DatabaseLimits(
    val concurrency: Int = 4,
    val poolSize: Int = 4,
    val minimumIdle: Int = 0,
    val requestMs: Long = 5_000,
    val admissionMs: Long = 250,
    val connectionMs: Long = 500,
    val lockMs: Long = 250,
    val statementMs: Long = 1_000,
    val cleanupMs: Long = 500,
    val responseMs: Long = 250,
) {
    init {
        require(concurrency in 1..64 && poolSize in 2..64 && minimumIdle in 0..poolSize)
        require(connectionMs >= 250 && admissionMs > 0 && lockMs > 0)
        require(lockMs < statementMs && cleanupMs > 0 && responseMs > 0)
        require(requestMs > admissionMs + connectionMs + statementMs + cleanupMs + responseMs)
        require(requestMs <= 120_000)
    }
}

class RequestBudget(private val limits: DatabaseLimits) {
    private val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limits.requestMs)
    fun remainingMs(): Long = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(0)
    fun databaseMs(): Long = (remainingMs() - limits.cleanupMs - limits.responseMs).coerceAtLeast(0)
}

class DatabaseUnavailable(val reason: String) : RuntimeException(reason)

/** Only bounded, non-sensitive stages are exposed to diagnostics. Never log SQL or exceptions. */
fun interface DatabaseObserver {
    fun record(stage: String, elapsedMs: Long)
}

class Database(
    jdbcUrl: String,
    username: String,
    password: String,
    val limits: DatabaseLimits = DatabaseLimits(),
    private val observer: DatabaseObserver = DatabaseObserver { _, _ -> },
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val slots = Semaphore(limits.concurrency)
    private val workers = Executors.newFixedThreadPool(limits.concurrency) { r ->
        Thread(r, "ingestion-jdbc").apply { isDaemon = true }
    }
    private val timer = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ingestion-deadline").apply { isDaemon = true }
    }
    private val cleanup = Executors.newFixedThreadPool(limits.concurrency) { r ->
        Thread(r, "ingestion-abort").apply { isDaemon = true }
    }
    internal val dataSource = HikariDataSource(HikariConfig().apply {
        this.jdbcUrl = jdbcUrl
        this.username = username
        this.password = password
        maximumPoolSize = limits.poolSize
        minimumIdle = limits.minimumIdle
        connectionTimeout = limits.connectionMs
        validationTimeout = 250
        initializationFailTimeout = -1
        addDataSourceProperty("connectTimeout", "1")
        addDataSourceProperty("socketTimeout", "2")
        addDataSourceProperty("cancelSignalTimeout", "1")
        poolName = "ingestion"
    })

    /** Pool counters for diagnostics (issue 23 measurement); not part of any contract. */
    fun poolStats(): String = dataSource.hikariPoolMXBean.let {
        "active=${it.activeConnections} idle=${it.idleConnections} pending=${it.threadsAwaitingConnection} total=${it.totalConnections}"
    }

    fun migrate() {
        Flyway.configure().dataSource(dataSource).load().migrate()
    }

    fun budget() = RequestBudget(limits)

    /** Readiness probe (ADR-0026): one `SELECT 1` through the normal path. Throws [DatabaseUnavailable]. */
    suspend fun ping() { transaction { query { fetchValue("SELECT 1") } } }

    suspend fun <T> transaction(budget: RequestBudget = budget(), work: Transaction.() -> T): T {
        val started = System.nanoTime()
        val ownsPermit = AtomicBoolean(false)
        val admitted = withTimeoutOrNull(min(limits.admissionMs, budget.databaseMs())) {
            slots.acquire()
            ownsPermit.set(true)
            true
        } ?: false
        observe("admission_wait", started)
        if (!admitted) {
            if (ownsPermit.get()) slots.release()
            throw DatabaseUnavailable("ADMISSION_TIMEOUT")
        }

        // The worker owns the permit until JDBC cleanup ends, even after HTTP cancellation.
        return suspendCancellableCoroutine { continuation ->
            val cancelled = AtomicBoolean(false)
            val connection = AtomicReference<Connection?>()
            fun abort() {
                cancelled.set(true)
                connection.get()?.let { c ->
                    // Cancel the active PostgreSQL operation. The worker still owns rollback and close.
                    try { c.unwrap(PGConnection::class.java).cancelQuery() }
                    catch (_: SQLException) { dataSource.evictConnection(c) }
                }
            }
            continuation.invokeOnCancellation { abort() }
            val deadlineTask = timer.schedule({ abort() }, budget.databaseMs(), TimeUnit.MILLISECONDS)
            try {
                workers.execute {
                    var result: T? = null
                    var failure: Throwable? = null
                    var committed = false
                    try {
                        if (cancelled.get() || budget.databaseMs() < limits.connectionMs) {
                            throw DatabaseUnavailable("REQUEST_TIMEOUT")
                        }
                        val waitStarted = System.nanoTime()
                        val c = try { dataSource.connection } finally { observe("connection_wait", waitStarted) }
                        connection.set(c)
                        if (cancelled.get()) throw DatabaseUnavailable("REQUEST_TIMEOUT")
                        c.autoCommit = false
                        c.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
                        val tx = Transaction(c, budget, limits, cancelled)
                        result = tx.work()
                        tx.checkBudget()
                        // Network deadline also bounds commit, which statement_timeout alone cannot bound.
                        c.setNetworkTimeout(cleanup, budget.databaseMs().coerceAtLeast(1).toInt())
                        c.commit()
                        committed = true
                        observe("commit", started)
                        if (cancelled.get()) throw DatabaseUnavailable("COMMIT_OUTCOME_UNCERTAIN")
                    } catch (error: Throwable) {
                        failure = error
                        val sql = generateSequence(error) { it.cause }.filterIsInstance<SQLException>().firstOrNull()
                        when (sql?.sqlState) {
                            "55P03" -> observe("lock_timeout", started)
                            "57014" -> observe("statement_timeout", started)
                        }
                    } finally {
                        deadlineTask.cancel(false)
                        connection.getAndSet(null)?.let { c ->
                            try {
                                if (failure != null && !committed && !c.isClosed) {
                                    c.setNetworkTimeout(cleanup, limits.cleanupMs.toInt())
                                    c.rollback()
                                    observe("rollback", started)
                                }
                                if (!c.isClosed && c.isValid(1)) {
                                    c.setNetworkTimeout(cleanup, 0)
                                    c.autoCommit = true
                                } else dataSource.evictConnection(c)
                            } catch (_: SQLException) {
                                dataSource.evictConnection(c)
                            } finally {
                                try { c.close() } catch (_: SQLException) { dataSource.evictConnection(c) }
                            }
                        }
                        slots.release()
                    }
                    if (continuation.isActive) {
                        if (failure != null) {
                            val error = failure
                            continuation.resumeWithException(
                                if (error is Error) error
                                else if (error is DatabaseUnavailable) error
                                else DatabaseUnavailable(if (committed) "COMMIT_OUTCOME_UNCERTAIN" else "DATABASE_UNAVAILABLE")
                            )
                        } else {
                            @Suppress("UNCHECKED_CAST")
                            continuation.resume(result as T)
                        }
                    }
                }
            } catch (error: java.util.concurrent.RejectedExecutionException) {
                deadlineTask.cancel(false)
                slots.release()
                continuation.resumeWithException(DatabaseUnavailable("SERVER_STOPPING"))
            }
        }
    }

    private fun observe(stage: String, started: Long) {
        try { observer.record(stage, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)) }
        catch (_: RuntimeException) { /* Diagnostics must not change transaction or permit ownership. */ }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        workers.shutdown()
        if (!workers.awaitTermination(limits.requestMs, TimeUnit.MILLISECONDS)) workers.shutdownNow()
        dataSource.close()
        timer.shutdownNow()
        cleanup.shutdown()
    }
}

class Transaction internal constructor(
    internal val connection: Connection,
    private val budget: RequestBudget,
    private val limits: DatabaseLimits,
    private val cancelled: AtomicBoolean,
) {
    internal val sql: DSLContext = DSL.using(connection, SQLDialect.POSTGRES)

    internal fun checkBudget() {
        if (cancelled.get() || budget.databaseMs() <= 1) throw DatabaseUnavailable("REQUEST_TIMEOUT")
    }

    /** Recompute against one deadline before each operation; SET LOCAL cannot leak into pool reuse. */
    fun <T> query(block: DSLContext.() -> T): T {
        checkBudget()
        val statement = min(limits.statementMs, budget.databaseMs())
        val lock = min(limits.lockMs, statement - 1).coerceAtLeast(1)
        connection.createStatement().use {
            it.execute("SET LOCAL statement_timeout = '$statement'")
            it.execute("SET LOCAL lock_timeout = '$lock'")
        }
        return sql.block()
    }
}
