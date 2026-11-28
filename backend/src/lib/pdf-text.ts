import { extractText, getDocumentProxy } from "unpdf";

export interface PageText { number: number; heading: string; body: string }

export async function extractPdfPages(bytes: Uint8Array): Promise<PageText[]> {
  const pdf = await getDocumentProxy(bytes);
  const { text } = await extractText(pdf, { mergePages: false });
  return (text as string[])
    .map((body, i) => ({ number: i + 1, body: body.replace(/\s+/g, " ").trim() }))
    .filter((p) => p.body.length > 0)
    .map((p) => ({ ...p, heading: p.body.slice(0, 60) }));
}
