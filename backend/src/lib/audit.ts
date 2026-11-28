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

/**
 * Optimistic-concurrency write. The audit row goes first, guarded by the same
 * `where` as the UPDATE; D1 runs the batch as one serialized transaction, so
 * nothing can run between them: the audit row exists iff the UPDATE applies.
 * The outcome is read from the UPDATE's own RETURNING rows (never changes()).
 * Callers put the revision/version check in `where`. Returns true if applied.
 */
export async function guardedUpdate(
  db: D1Database,
  table: "jobs" | "tasks" | "documents",
  set: string,
  setParams: unknown[],
  where: string,
  whereParams: unknown[],
  audit: { actor: Actor; action: string; entity: string; entityId: string; before: unknown; after: unknown },
): Promise<boolean> {
  const [, update] = await db.batch([
    auditStmtIfExists(
      db,
      `SELECT 1 FROM ${table} WHERE ${where}`,
      whereParams,
      audit.actor,
      audit.action,
      audit.entity,
      audit.entityId,
      audit.before,
      audit.after,
    ),
    db.prepare(`UPDATE ${table} SET ${set} WHERE ${where} RETURNING id`).bind(...setParams, ...whereParams),
  ]);
  return update.results.length > 0;
}
