# SignalDock DB timeout research

Research date: 2026-10-09. Stack: Kotlin Ktor + jOOQ JDBC + HikariCP + PostgreSQL.
Only five official documents were checked. No code/spec inspection or tests were performed. The applications below are document-based proposals, not verification of the current implementation.

## 1. What each setting limits

| Setting | Scope | Expiry behavior and cautions |
|---|---|---|
| PostgreSQL `lock_timeout` | Wait time for each lock acquisition attempt | Aborts the statement. It is not a combined budget for multiple lock waits. Includes implicit locks. [PG settings](https://www.postgresql.org/docs/17/runtime-config-client.html#GUC-LOCK-TIMEOUT) |
| PostgreSQL `statement_timeout` | Time from server receipt of a command to completion | Includes lock waits. It is not a whole-transaction budget. When both values are enabled and `lock_timeout >= statement_timeout`, `statement_timeout` takes effect first. [PG settings](https://www.postgresql.org/docs/17/runtime-config-client.html#GUC-STATEMENT-TIMEOUT) |
| PostgreSQL `transaction_timeout` | Total elapsed time of an explicit or implicit transaction | Terminates the session. Optional for PG17+. Do not require it in SignalDock's baseline while the version is undecided. Does not apply to prepared transactions. [PG settings](https://www.postgresql.org/docs/17/runtime-config-client.html#GUC-TRANSACTION-TIMEOUT) |
| PostgreSQL `idle_in_transaction_session_timeout` | Idle time waiting for the next client query in an open transaction | Terminates the session. Does not limit a running query or total transaction time. [PG settings](https://www.postgresql.org/docs/17/runtime-config-client.html#GUC-IDLE-IN-TRANSACTION-SESSION-TIMEOUT) |
| HikariCP `connectionTimeout` | Wait time to borrow a connection from the pool, in ms | Throws `SQLException` when exceeded. Does not limit SQL or transactions already running. The minimum allowed value is 250 ms. [HikariCP README](https://github.com/brettwooldridge/HikariCP#frequently-used) |

If PG17's `transaction_timeout` is shorter than or equal to `statement_timeout` or `idle_in_transaction_session_timeout`, the corresponding longer timeout is ignored. [PG settings](https://www.postgresql.org/docs/17/runtime-config-client.html#GUC-TRANSACTION-TIMEOUT)

## 2. pgJDBC settings are a separate layer

| Setting | Unit | Meaning |
|---|---|---|
| `queryTimeout` | Seconds | Driver execution wait limit for queries without a separate `Statement.setQueryTimeout(int)` value. Separate from server `statement_timeout`. |
| `cancelSignalTimeout` | Seconds | Connect/read timeout for the cancel command sent over a separate connection. It is neither a query execution budget nor a guarantee of successful cancellation. |
| `socketTimeout` | Seconds | Limit on waiting for socket reads from the server. Closes the connection when exceeded. Distinguish this from normal query cancellation. |
| `connectTimeout` | Seconds | Connect limit for a new socket. Separate from the HikariCP pool wait limit. |

Definitions of these four settings: [pgJDBC: Initializing the Driver](https://jdbc.postgresql.org/documentation/use/).

Cancellation is a separate operation. PostgreSQL sends `CancelRequest` over a new connection. It may have no effect if it arrives late. Sending a cancellation request does not prove success; check the original query response. Do not assume that a Ktor request timeout or a client stopping its wait guarantees DB cancellation. This application principle follows from the protocol document; Ktor integration behavior was not verified. [PG Message Flow §53.2.8](https://www.postgresql.org/docs/17/protocol-flow.html#PROTOCOL-FLOW-CANCELING-REQUESTS)

## 3. Using SET LOCAL with a pool

Design proposal: Start an explicit transaction on the same borrowed JDBC connection, then run `SET LOCAL` and all business SQL there. jOOQ must also use the context bound to that transaction. A separate pool connection does not share these settings because `SET` applies only to the current session. [PG SET](https://www.postgresql.org/docs/17/sql-set.html)

Order: acquire connection → disable autoCommit/start transaction → `SET LOCAL lock_timeout = '200ms'` → `SET LOCAL statement_timeout = '800ms'` → business SQL → commit on success or rollback on error → return to pool.

After commit or rollback, `SET LOCAL` returns to the previous session value. It has no effect outside a transaction. Ordinary `SET` can remain in the session after commit. Respect this difference to avoid leaking settings when pooled connections are reused. [PG SET](https://www.postgresql.org/docs/17/sql-set.html)

Statement cancellation/error inside an explicit transaction requires rollback to clear the failed transaction state. Do not catch the error and continue business SQL without recovery. [PG Message Flow §53.2.2.1](https://www.postgresql.org/docs/17/protocol-flow.html#PROTOCOL-FLOW-MULTI-STATEMENT)

Recovery by rolling back to a savepoint before the error is an exception. pgJDBC `autosave=always` also uses this method. Do not expect automatic recovery with the default `autosave=never`. Rolling back to a savepoint before `SET LOCAL` can also cancel that setting. [pgJDBC autosave](https://jdbc.postgresql.org/documentation/use/), [PG SET](https://www.postgresql.org/docs/17/sql-set.html)

## 4. Distinguish timeouts from commit outcomes

- Server-side cancellation/error of a business query was confirmed before COMMIT, with no savepoint recovery: The explicit transaction is failed. End it with rollback. [PG Message Flow](https://www.postgresql.org/docs/17/protocol-flow.html)
- Response loss or connection failure after sending COMMIT: A client timeout alone does not prove that no commit occurred. The commit may have completed and only its response was lost. This follows from command completion/response and connection termination rules. [PG Message Flow](https://www.postgresql.org/docs/17/protocol-flow.html)
- Connection close: The server rolls back a transaction that is still open when it handles termination. However, writes outside a transaction block can commit before it detects the disconnect. A socket timeout therefore does not prove that no commit occurred. [PG Message Flow §53.2.9](https://www.postgresql.org/docs/17/protocol-flow.html#PROTOCOL-FLOW-TERMINATION)

Design proposal: For writes with unknown outcomes, look up the result using an event ID or equivalent and prevent duplicates. Do not retry unconditionally with a new ID because of a timeout. The current deduplication implementation was not checked.

## 5. Example layered limits

These values are illustrative, not universal industry defaults. Choose actual values after measuring query count, load, network behavior, and response targets.

| Layer | Example value | Purpose |
|---|---|---|
| HikariCP pool acquisition | `connectionTimeout=500` ms | Limit pool wait first. [HikariCP](https://github.com/brettwooldridge/HikariCP#frequently-used) |
| DB lock / statement | `lock_timeout=200ms`, `statement_timeout=800ms` | Keep lock waits shorter. [PG settings](https://www.postgresql.org/docs/17/runtime-config-client.html) |
| Driver | `queryTimeout=2`, `cancelSignalTimeout=1`, `socketTimeout=5`, `connectTimeout=2` seconds | Separate execution wait, cancel communication, reads, and connection establishment. [pgJDBC](https://jdbc.postgresql.org/documentation/use/) |
| Application | Transaction work budget: 2 seconds; request budget: 4 seconds | Proposed values. Design cancellation and rollback paths separately. Request expiry alone does not imply DB termination. [Protocol evidence](https://www.postgresql.org/docs/17/protocol-flow.html) |

These limits do not share one clock. Multiple statements can follow pool wait. Cleanup and waiting for the COMMIT response also take time. An application transaction budget is not the same feature as PostgreSQL `transaction_timeout`. Review that setting separately if PG17+ is selected.
