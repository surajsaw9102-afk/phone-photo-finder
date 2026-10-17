import { assertEquals } from 'jsr:@std/assert@1'
import { tokenizeQuery } from './mcp-search.ts'

Deno.test('tokenizes English and Hinglish words', () => {
  assertEquals(tokenizeQuery('Find my beach photos!'), ['find', 'my', 'beach', 'photos'])
})

Deno.test('preserves Devanagari words as whole search terms', () => {
  assertEquals(tokenizeQuery('समुंदर वाली फोटो'), ['समुंदर', 'वाली', 'फोटो'])
})

Deno.test('normalizes mixed punctuation and case', () => {
  assertEquals(tokenizeQuery('DOG, Kutta & BEACH'), ['dog', 'kutta', 'beach'])
})
