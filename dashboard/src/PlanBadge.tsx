import type { PlanStatus } from "../../generated/typescript/models";
import { useTranslation } from "./i18n";

// Dot and word, like the other badges (ADR-0016): a violation is never shown by color alone.
const BADGE = { planned: "ok", unplanned_event: "warn", invalid_properties: "bad" } as const;

/** The plan status of an event (ADR-0022). No status means the server did not check it: no plan, or an older row. */
export function PlanBadge({ status }: { status?: PlanStatus }) {
  const { t } = useTranslation();
  return status
    ? <span className={`badge ${BADGE[status]}`}>{t(`plan.${status}`)}</span>
    : <span className="badge idle">{t("plan.notChecked")}</span>;
}
