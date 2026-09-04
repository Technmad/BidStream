"use client";

import { useRouter, useSearchParams, usePathname } from "next/navigation";

/**
 * Plain prev/next pagination control (§12.2) — updates the URL's `page` search
 * param. Zero-based to match Spring's `Pageable` `page` param directly.
 * Semantic `<button>`s only (§15 — fully keyboard operable).
 */
export function Pagination({ page, totalPages }: { page: number; totalPages: number }) {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();

  function goToPage(nextPage: number) {
    const next = new URLSearchParams(searchParams.toString());
    if (nextPage <= 0) {
      next.delete("page");
    } else {
      next.set("page", String(nextPage));
    }
    router.push(`${pathname}?${next.toString()}`);
  }

  if (totalPages <= 1) return null;

  return (
    <nav className="flex items-center justify-center gap-4" aria-label="Pagination">
      <button
        type="button"
        onClick={() => goToPage(page - 1)}
        disabled={page <= 0}
        className="rounded border border-gray-300 px-3 py-1.5 text-sm disabled:opacity-40 dark:border-gray-700"
      >
        Previous
      </button>
      <span className="text-sm text-gray-500">
        Page {page + 1} of {totalPages}
      </span>
      <button
        type="button"
        onClick={() => goToPage(page + 1)}
        disabled={page >= totalPages - 1}
        className="rounded border border-gray-300 px-3 py-1.5 text-sm disabled:opacity-40 dark:border-gray-700"
      >
        Next
      </button>
    </nav>
  );
}
