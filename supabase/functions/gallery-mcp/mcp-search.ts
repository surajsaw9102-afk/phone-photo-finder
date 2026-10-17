export function tokenizeQuery(query: string): string[] {
  return query.toLocaleLowerCase().split(/[^\p{L}\p{N}]+/u).filter(Boolean)
}
