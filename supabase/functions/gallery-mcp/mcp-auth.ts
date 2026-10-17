// This helper is deliberately only an audience check. Call it after the OAuth
// middleware has cryptographically validated the access token.
export function hasResourceAudience(
  authorization: string | null,
  resourceUri: string,
): boolean {
  const token = authorization?.match(/^Bearer\s+(.+)$/i)?.[1]
  if (!token) return false

  try {
    const payloadSegment = token.split('.')[1]
    if (!payloadSegment) return false
    const normalized = payloadSegment.replace(/-/g, '+').replace(/_/g, '/')
    const payload = JSON.parse(
      atob(normalized.padEnd(Math.ceil(normalized.length / 4) * 4, '=')),
    )
    const aud = payload.aud
    return Array.isArray(aud) ? aud.includes(resourceUri) : aud === resourceUri
  } catch {
    return false
  }
}
