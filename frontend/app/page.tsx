import Link from "next/link";

/**
 * Placeholder home page — Phase 0 has no real landing screen yet (Browse is
 * Phase 3, §18). This just links to what Phase 0 actually built so it's easy
 * to manually spot-check.
 */
export default function Home() {
  return (
    <main className="mx-auto flex w-full max-w-md flex-1 flex-col justify-center gap-4 px-4 py-16">
      <h1 className="text-2xl font-semibold">BidStream</h1>
      <p className="text-sm text-gray-500">
        Phase 0 scaffold — auth flow and the SSR/hydration proof-of-pattern page.
      </p>
      <nav className="flex flex-col gap-2 text-sm underline">
        <Link href="/auctions">Browse auctions</Link>
        <Link href="/login">Log in</Link>
        <Link href="/register">Register</Link>
        <Link href="/categories-ssr-check">SSR + HydrationBoundary check</Link>
      </nav>
    </main>
  );
}
