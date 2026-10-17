import type { ClientReportCounts } from "../../generated/typescript/models";
import { droppedTotal } from "./dropped";
import { useTranslation } from "./i18n";
import { formatNumber } from "../../web-shared/i18n";

/** Row limit of the endpoint (openapi/contract-v1.yaml). */
const LIMIT = 100;

/** Events that the SDKs dropped and counted (Q164). They are not in the event list. The period is the one of the other queries; no event filter applies. */
export function DroppedCard({ counts }: { counts?: ClientReportCounts }) {
  const { t, i18n } = useTranslation();
  const n = (v: number) => formatNumber(v, i18n.language);
  const total = droppedTotal(counts);
  return (
    <section className="card dropped-card" aria-label={t("dropped.title")}>
      <h2>{t("dropped.title")}</h2>
      <p className="note">{t("dropped.note")}</p>
      {counts && counts.items.length === 0 && <p>{t("dropped.empty")}</p>}
      {counts && counts.items.length > 0 && (
        <div className="table-wrap">
          <table>
            <caption className="sr-only">{t("dropped.title")}</caption>
            <thead>
              <tr><th scope="col">{t("dropped.sdkVersion")}</th><th scope="col">{t("dropped.reason")}</th><th scope="col" className="num">{t("dropped.count")}</th></tr>
            </thead>
            <tbody>
              {counts.items.map((r) => (
                <tr key={`${r.sdkVersion}|${r.reason}`}>
                  <td>{r.sdkVersion}</td>
                  <td>{t(`dropped.reasons.${r.reason}`)} <code>{r.reason}</code></td>
                  <td className="num">{n(r.count)}</td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr><th scope="row" colSpan={2}>{t("dropped.total")}</th><td className="num">{n(total ?? 0)}</td></tr>
            </tfoot>
          </table>
        </div>
      )}
      {counts?.truncated && <p className="note">{t("dropped.truncated", { limit: n(LIMIT) })}</p>}
    </section>
  );
}
