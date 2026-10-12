import { useEffect, useState } from "react";
import { EventReceiptDetailToJSON } from "../../generated/typescript/models";
import type { EventReceiptDetail } from "../../generated/typescript/models";
import type { Api } from "./api";
import { formatInstant } from "./period";
import { QueryError } from "./polling";

type Load =
  | { status: "loading" }
  | { status: "ok"; detail: EventReceiptDetail }
  | { status: "notFound" }
  | { status: "error"; message: string };

export function CopyButton({ text, label }: { text: string; label: string }) {
  const [result, setResult] = useState("");
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text);
      setResult("Copied");
    } catch {
      setResult("Copy failed");
    }
  };
  return (
    <button type="button" className="copy" onClick={copy} aria-label={label}>
      {result || "Copy"}
    </button>
  );
}

/** Details are fetched once and stay in place while the list polls (spec section 6). Retry is manual. */
export function Details({ api, eventId, timeZone, onClose }: { api: Api; eventId: string; timeZone: string; onClose: () => void }) {
  const [load, setLoad] = useState<Load>({ status: "loading" });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setLoad({ status: "loading" });
    api.getEvent(eventId, controller.signal).then(
      (detail) => setLoad({ status: "ok", detail }),
      (e) => {
        if (controller.signal.aborted) return;
        if (e instanceof QueryError && e.status === 404) setLoad({ status: "notFound" });
        else setLoad({ status: "error", message: e instanceof Error ? e.message : "Request failed" });
      },
    );
    return () => controller.abort();
  }, [api, eventId, attempt]);

  return (
    <section className="panel" aria-label="Event details">
      <h2>
        Event details <button type="button" onClick={onClose}>Close</button>
      </h2>
      {load.status === "loading" && <p>Loading...</p>}
      {load.status === "notFound" && <p>Event not found</p>}
      {load.status === "error" && (
        <p className="error" role="alert">
          {load.message} <button type="button" onClick={() => setAttempt((n) => n + 1)}>Retry</button>
        </p>
      )}
      {load.status === "ok" && <DetailBody detail={load.detail} timeZone={timeZone} />}
    </section>
  );
}

function DetailBody({ detail, timeZone }: { detail: EventReceiptDetail; timeZone: string }) {
  // Values passed through JSONB: this is the stored event, not the original HTTP payload or its key order.
  const json = JSON.stringify(EventReceiptDetailToJSON(detail), null, 2);
  return (
    <>
      <dl>
        <dt>Name</dt><dd>{detail.name}</dd>
        <dt>Event ID</dt><dd><code>{detail.eventId}</code> <CopyButton text={detail.eventId} label="Copy event ID" /></dd>
        <dt>Installation ID</dt><dd><code>{detail.installationId}</code> <CopyButton text={detail.installationId} label="Copy installation ID" /></dd>
        <dt>occurredAt (device clock)</dt><dd>{formatInstant(detail.occurredAt, timeZone)}</dd>
        <dt>receivedAt (server)</dt><dd>{formatInstant(detail.receivedAt, timeZone)}</dd>
        <dt>SDK version at collection</dt><dd>{detail.sdkVersion}</dd>
      </dl>
      <h3>Properties</h3>
      {Object.keys(detail.properties).length === 0 ? (
        <p className="note">No properties.</p>
      ) : (
        <table>
          <tbody>
            {Object.entries(detail.properties).map(([k, v]) => (
              <tr key={k}>
                <th scope="row">{k}</th>
                <td>{typeof v} </td>
                <td>{String(v)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <h3>
        Stored event JSON <CopyButton text={json} label="Copy stored event JSON" />
      </h3>
      <p className="note">As stored after JSONB. It is not the original HTTP payload, and key order is not preserved.</p>
      <pre>{json}</pre>
    </>
  );
}
