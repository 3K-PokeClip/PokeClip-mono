/** Build a CSS `var()` reference for a PokeClip token custom property. */
export function cssVar(name: `--pc-${string}`): string {
  return `var(${name})`;
}
