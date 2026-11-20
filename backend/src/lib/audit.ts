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
    .prepare(
      `INSERT INTO audit_log
         (id, tenant_id, actor_type, actor_id, action, entity, entity_id, before_json, after_json, at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    )
    .bind(
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
    );
}
