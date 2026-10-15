import { useEffect, useRef, useState } from "react";
import { EventReceiptDetailToJSON } from "../../generated/typescript/models";
import type { EventReceiptDetail } from "../../generated/typescript/models";
import type { Api } from "./api";
import { errorText } from "./errorText";
import { useTranslation } from "./i18n";
import { PlanBadge } from "./PlanBadge";
import { QueryError } from "./polling";
import { formatDateTime } from "../../web-shared/i18n";

type Load =
  | { status: "loading" }
  | { status: "ok"; detail: EventReceiptDetail }
  | { status: "notFound" }
  | { status: "error"; error: Error };

export function CopyButton({ text, label }: { text: string; label: string }) {
  const { t } = useTranslation();
  const [result, setResult] = useState<"copied" | "failed" | "">("");
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text);
      setResult("copied");
    } catch {
      setResult("failed");
    }
  };
  return (
    <>
      <button type="button" className="copy" onClick={copy} aria-label={label}>
        {result ? t(`copy.${result}`) : t("copy.copy")}
      </button>
      <span className="sr-only" role="status">{result ? t(`copy.${result}`) : ""}</span>
    </>
  );
}

/** Details are fetched once and stay in place while the list polls (spec section 6). Retry is manual. */
export function Details({ api, eventId, timeZone, onClose }: { api: Api; eventId: string; timeZone: string; onClose: () => void }) {
  const { t } = useTranslation();
  const [load, setLoad] = useState<Load>({ status: "loading" });
  const [attempt, setAttempt] = useState(0);
  // Opening an event moves focus to the heading of the details, so a keyboard or screen reader user lands on them (issue 29).
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => { heading.current?.focus(); }, [eventId]);

  useEffect(() => {
    const controller = new AbortController();
    setLoad({ status: "loading" });
    api.getEvent(eventId, controller.signal).then(
      (detail) => setLoad({ status: "ok", detail }),
      (e) => {
        if (controller.signal.aborted) return;
        if (e instanceof QueryError && e.status === 404) setLoad({ status: "notFound" });
        else setLoad({ status: "error", error: e instanceof Error ? e : new QueryError("Request failed", undefined, undefined, undefined, "errors.request") });
      },
    );
    return () => controller.abort();
  }, [api, eventId, attempt]);

  return (
    <section className="card detail" aria-label={t("details.label")}>
      <div className="detail-head">
        <h2 ref={heading} tabIndex={-1}>{t("details.title")}</h2>
        <button type="button" onClick={onClose}>{t("details.close")}</button>
      </div>
      {load.status === "loading" && <p role="status">{t("details.loading")}</p>}
      {load.status === "notFound" && <p role="status">{t("details.notFound")}</p>}
      {load.status === "error" && (
        <p className="error" role="alert">
          {errorText(t, load.error)} <button type="button" onClick={() => setAttempt((n) => n + 1)}>{t("status.retry")}</button>
        </p>
      )}
      {load.status === "ok" && <DetailBody detail={load.detail} timeZone={timeZone} />}
    </section>
  );
}

function DetailBody({ detail, timeZone }: { detail: EventReceiptDetail; timeZone: string }) {
  const { t, i18n } = useTranslation();
  // Values passed through JSONB: this is the stored event, not the original HTTP payload or its key order.
  const json = JSON.stringify(EventReceiptDetailToJSON(detail), null, 2);
  return (
    <>
      <dl>
        <dt>{t("details.name")}</dt><dd>{detail.name}</dd>
        <dt>{t("details.eventId")}</dt><dd><code>{detail.eventId}</code> <CopyButton text={detail.eventId} label={t("copy.eventId")} /></dd>
        <dt>{t("details.installationId")}</dt><dd><code>{detail.installationId}</code> <CopyButton text={detail.installationId} label={t("copy.installationId")} /></dd>
        <dt>{t("details.occurredAt")}</dt><dd>{formatDateTime(detail.occurredAt, timeZone, i18n.language)}</dd>
        <dt>{t("details.receivedAt")}</dt><dd>{formatDateTime(detail.receivedAt, timeZone, i18n.language)}</dd>
        <dt>{t("details.sdkVersion")}</dt><dd>{detail.sdkVersion}</dd>
        <dt>{t("details.planStatus")}</dt><dd><PlanBadge status={detail.planStatus} /></dd>
      </dl>
      <h3>{t("details.properties")}</h3>
      {Object.keys(detail.properties).length === 0 ? (
        <p className="note">{t("details.noProperties")}</p>
      ) : (
        <div className="table-wrap">
          <table>
            <caption className="sr-only">{t("details.properties")}</caption>
            <tbody>
              {Object.entries(detail.properties).map(([k, v]) => (
                <tr key={k}>
                  <th scope="row">{k}</th>
                  <td>{typeof v === "string" ? t("details.typeString") : typeof v === "number" ? t("details.typeNumber") : t("details.typeBoolean")}</td>
                  <td>{String(v)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <h3>
        {t("details.json")} <CopyButton text={json} label={t("copy.json")} />
      </h3>
      <p className="note">{t("details.jsonNote")}</p>
      <pre>{json}</pre>
    </>
  );
}
