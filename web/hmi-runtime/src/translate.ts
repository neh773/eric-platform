/**
 * Term translation for HMI text (HMP-125, doc source: FEATURES HMP-125). A lookup tries the exact locale tag, then its
 * language (`es-MX` then `es`), and gives the term itself back when nothing matches, so untranslated text shows its key.
 */
export type TranslationCatalog = Readonly<Record<string, Readonly<Record<string, string>>>>;

export function translateTerm(catalog: TranslationCatalog | undefined, locale: string, term: string): string {
  if (!catalog) return term;
  const wanted = locale.toLowerCase();
  const language = wanted.split("-")[0] ?? wanted;
  const find = (tag: string): Readonly<Record<string, string>> | undefined => {
    for (const [key, terms] of Object.entries(catalog)) if (key.toLowerCase() === tag) return terms;
    return undefined;
  };
  for (const tag of [wanted, language]) {
    const found = find(tag)?.[term];
    if (typeof found === "string") return found;
  }
  return term;
}
