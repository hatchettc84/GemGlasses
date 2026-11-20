import { TENANT_ID, type AdminUser } from "../env";
import { nowIso, ulid } from "./ids";

export interface Actor {
  type: "user" | "device" | "system";
  id: string | null;
  tenantId: string;
}

export const SYSTEM_ACTOR: Actor = { type: "system", id: null, tenantId: TENANT_ID };

export function actorFromUser(u: AdminUser): Actor {
  return { type: "user", id: u.id, tenantId: u.tenantId };
}

const AUDIT_COLUMNS = "id, tenant_id, actor_type, actor_id, action, entity, entity_id, before_json, after_json, at";

function auditValues(
  actor: Actor,
  action: string,
  entity: string,
  entityId: string | null,
  before: unknown,
  after: unknown,
): unknown[] {
  return [
    ulid(),
    actor.tenantId,
    actor.type,
    actor.id,
    action,
    entity,
    entityId,
    before == null ? null : JSON.stringify(before),
    after == null ? null : JSON.stringify(after),
    nowIso(),
  ];
}

/** Returns the insert so callers can batch it with the change it records. */
export function auditStmt(
  db: D1Database,
  actor: Actor,
  action: string,
  entity: string,
  entityId: string | null,
  before: unknown,
  after: unknown,
): D1PreparedStatement {
  return db
    .prepare(`INSERT INTO audit_log (${AUDIT_COLUMNS}) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`)
    .bind(...auditValues(actor, action, entity, entityId, before, after));
}

/**
 * Like auditStmt, but inserts nothing unless `whereSql` (a SELECT-able condition
 * with `?` placeholders bound to `whereParams`) is true when the batch reaches it.
 * Put it after the change it records so a change that did not apply leaves no audit row.
 */
export function auditStmtIfExists(
  db: D1Database,
  existsSql: string,
  existsParams: unknown[],
  actor: Actor,
  action: string,
  entity: string,
  entityId: string | null,
  before: unknown,
  after: unknown,
): D1PreparedStatement {
  return db
    .prepare(
      `INSERT INTO audit_log (${AUDIT_COLUMNS})
       SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ? WHERE EXISTS (${existsSql})`,
    )
    .bind(...auditValues(actor, action, entity, entityId, before, after), ...existsParams);
}
