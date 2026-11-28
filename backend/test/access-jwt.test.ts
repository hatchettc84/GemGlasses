import { describe, expect, it } from "vitest";
import { createLocalJWKSet, exportJWK, generateKeyPair, SignJWT } from "jose";
import { normalizeTeamDomain, verifyAccessJwt } from "../src/auth/access-jwt";

const ISS = "https://team.cloudflareaccess.com";
const AUD = "aud-123";

async function setup() {
  const { publicKey, privateKey } = await generateKeyPair("RS256");
  const jwk = { ...(await exportJWK(publicKey)), kid: "k1", alg: "RS256" };
  const keys = createLocalJWKSet({ keys: [jwk] });
  const sign = (claims: Record<string, unknown>, aud = AUD) =>
    new SignJWT(claims).setProtectedHeader({ alg: "RS256", kid: "k1" })
      .setIssuer(ISS).setAudience(aud).setIssuedAt().setExpirationTime("5m").sign(privateKey);
  return { keys, sign };
}

describe("verifyAccessJwt", () => {
  it("returns the lowercased email from a valid token", async () => {
    const { keys, sign } = await setup();
    expect(await verifyAccessJwt(await sign({ email: "Tech@Example.com" }), keys, ISS, AUD)).toBe("tech@example.com");
  });
  it("rejects a token for another application", async () => {
    const { keys, sign } = await setup();
    await expect(verifyAccessJwt(await sign({ email: "a@b.co" }, "other"), keys, ISS, AUD)).rejects.toThrow();
  });
  it("rejects a token without an email", async () => {
    const { keys, sign } = await setup();
    await expect(verifyAccessJwt(await sign({}), keys, ISS, AUD)).rejects.toThrow("no email");
  });
  it("rejects a token signed with a non-RS256 algorithm", async () => {
    const { publicKey, privateKey } = await generateKeyPair("ES256");
    const jwk = { ...(await exportJWK(publicKey)), kid: "e1", alg: "ES256" };
    const keys = createLocalJWKSet({ keys: [jwk] });
    const token = await new SignJWT({ email: "a@b.co" }).setProtectedHeader({ alg: "ES256", kid: "e1" })
      .setIssuer(ISS).setAudience(AUD).setIssuedAt().setExpirationTime("5m").sign(privateKey);
    await expect(verifyAccessJwt(token, keys, ISS, AUD)).rejects.toThrow();
  });
});

describe("normalizeTeamDomain", () => {
  it("strips trailing slashes and whitespace", () => {
    expect(normalizeTeamDomain("https://team.cloudflareaccess.com/")).toBe(ISS);
    expect(normalizeTeamDomain(" https://team.cloudflareaccess.com// ")).toBe(ISS);
    expect(normalizeTeamDomain(ISS)).toBe(ISS);
  });
});
