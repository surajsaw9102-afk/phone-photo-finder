import { assertEquals } from 'jsr:@std/assert@1'
import { hasResourceAudience } from './mcp-auth.ts'

const RESOURCE = 'https://example.test/mcp'

function base64Url(value: string): string {
  return btoa(value).replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_')
}

function tokenWithClaims(claims: Record<string, unknown>): string {
  return [
    base64Url(JSON.stringify({ alg: 'none', typ: 'JWT' })),
    base64Url(JSON.stringify(claims)),
    'test-signature',
  ].join('.')
}

Deno.test('accepts an MCP resource in an audience array', () => {
  assertEquals(
    hasResourceAudience('Bearer ' + tokenWithClaims({ aud: [RESOURCE, 'authenticated'] }), RESOURCE),
    true,
  )
})

Deno.test('accepts an exact scalar audience', () => {
  assertEquals(hasResourceAudience('Bearer ' + tokenWithClaims({ aud: RESOURCE }), RESOURCE), true)
})

Deno.test('rejects a different audience', () => {
  assertEquals(
    hasResourceAudience('Bearer ' + tokenWithClaims({ aud: 'authenticated' }), RESOURCE),
    false,
  )
})

Deno.test('rejects missing and malformed authorization headers', () => {
  assertEquals(hasResourceAudience(null, RESOURCE), false)
  assertEquals(hasResourceAudience('Basic abc', RESOURCE), false)
  assertEquals(hasResourceAudience('Bearer not-a-jwt', RESOURCE), false)
  assertEquals(hasResourceAudience('Bearer a.%%%.c', RESOURCE), false)
})

Deno.test('accepts case-insensitive bearer scheme and multiple spaces', () => {
  assertEquals(
    hasResourceAudience('bEaReR   ' + tokenWithClaims({ aud: RESOURCE }), RESOURCE),
    true,
  )
})
