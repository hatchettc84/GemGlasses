const ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

/** ULID: 10 chars of millisecond time + 16 random chars, Crockford base32. */
export function ulid(now: number = Date.now()): string {
  let time = "";
  let t = now;
  for (let i = 0; i < 10; i++) {
    time = ALPHABET[t % 32] + time;
    t = Math.floor(t / 32);
  }
  const rand = crypto.getRandomValues(new Uint8Array(16));
  let tail = "";
  for (const b of rand) tail += ALPHABET[b % 32]; // 256 is a multiple of 32: no bias
  return time + tail;
}

export function nowIso(): string {
  return new Date().toISOString();
}
