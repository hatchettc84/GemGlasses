import { createRemoteJWKSet, jwtVerify, type JWTVerifyGetKey } from "jose";

const keysByTeam = new Map<string, JWTVerifyGetKey>();

/** Cloudflare Access signing keys for a team domain, cached per isolate. */
export function accessKeys(teamDomain: string): JWTVerifyGetKey {
  let keys = keysByTeam.get(teamDomain);
  if (!keys) {
    keys = createRemoteJWKSet(new URL(`${teamDomain}/cdn-cgi/access/certs`));
    keysByTeam.set(teamDomain, keys);
  }
  return keys;
}

/** Verifies an Access JWT and returns its email claim, lowercased. Throws if invalid. */
export async function verifyAccessJwt(
  token: string,
  keys: JWTVerifyGetKey,
  issuer: string,
  audience: string,
): Promise<string> {
  const { payload } = await jwtVerify(token, keys, { issuer, audience });
  const email = payload.email;
  if (typeof email !== "string" || email.length === 0) throw new Error("no email claim");
  return email.toLowerCase();
}
