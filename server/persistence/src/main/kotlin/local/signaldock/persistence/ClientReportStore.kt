package local.signaldock.persistence

import local.signaldock.ingestion.ClientReport
import local.signaldock.server.jooq.tables.ClientReports.CLIENT_REPORTS

/**
 * Stores SDK client reports (ADR-0036): one row per entry, all rows of one report in one transaction.
 * No installation ID or other identity is stored. A report is not idempotent: a resend adds its rows again.
 */
public class ClientReportStore(private val database: Database) {
    public suspend fun store(report: ClientReport, budget: RequestBudget = database.budget()) {
        database.transaction(budget) {
            report.entries.forEach { entry ->
                query {
                    insertInto(CLIENT_REPORTS)
                        .set(CLIENT_REPORTS.SDK_PLATFORM, report.sdkPlatform.wire)
                        .set(CLIENT_REPORTS.SDK_VERSION, report.sdkVersion)
                        .set(CLIENT_REPORTS.REASON, entry.reason.wire)
                        .set(CLIENT_REPORTS.COUNT, entry.count)
                        .execute()
                }
            }
        }
    }
}
