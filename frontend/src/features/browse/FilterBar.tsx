"use client";

import { useQuery } from "@tanstack/react-query";
import { useRouter, useSearchParams, usePathname } from "next/navigation";
import { useState } from "react";
import type { AuctionStatus } from "@/src/api/auctions";
import { categoriesQueryKey, getCategories } from "@/src/api/categories";

const STATUS_OPTIONS: AuctionStatus[] = [
  "OPEN",
  "EXTENDED",
  "CLOSING",
  "SCHEDULED",
  "SOLD",
  "UNSOLD",
  "CANCELLED",
];

const SORT_OPTIONS: { value: string; label: string }[] = [
  { value: "endTime,asc", label: "Ending soon" },
  { value: "endTime,desc", label: "Ending latest" },
  { value: "currentPrice,asc", label: "Price: low to high" },
  { value: "currentPrice,desc", label: "Price: high to low" },
];

/**
 * §12.2's filter/sort/search controls. Every change here updates the URL's
 * search params (via `router.push`, not local-only state) so a filtered Browse
 * view stays shareable — the PDR's explicit reasoning for this screen. Plain
 * semantic `<select>`/`<input>`/`<button>` throughout (§15 — fully keyboard
 * operable, no mouse-only custom widgets).
 */
export function FilterBar() {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();

  const { data: categories } = useQuery({
    queryKey: categoriesQueryKey,
    queryFn: getCategories,
  });

  const [q, setQ] = useState(searchParams.get("q") ?? "");

  function updateParams(updates: Record<string, string | null>) {
    const next = new URLSearchParams(searchParams.toString());
    for (const [key, value] of Object.entries(updates)) {
      if (value === null || value === "") {
        next.delete(key);
      } else {
        next.set(key, value);
      }
    }
    // Any filter/sort/search change resets pagination — a page number from a
    // previous, differently-filtered result set is meaningless here.
    next.delete("page");
    router.push(`${pathname}?${next.toString()}`);
  }

  function handleSearchSubmit(event: React.FormEvent) {
    event.preventDefault();
    updateParams({ q: q || null });
  }

  const currentStatus = searchParams.get("status") ?? "";
  const currentCategory = searchParams.get("category") ?? "";
  const currentSort = searchParams.get("sort") ?? "";

  return (
    <div className="flex flex-col gap-4">
      <form onSubmit={handleSearchSubmit} className="flex gap-2" role="search">
        <label className="sr-only" htmlFor="browse-q">
          Search auctions
        </label>
        <input
          id="browse-q"
          type="search"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder="Search title or description…"
          className="flex-1 rounded border border-gray-300 px-3 py-2 text-sm dark:border-gray-700 dark:bg-transparent"
        />
        <button
          type="submit"
          className="rounded bg-foreground px-4 py-2 text-sm font-medium text-background"
        >
          Search
        </button>
      </form>

      <div className="flex flex-wrap items-center gap-4">
        <label className="flex flex-col gap-1 text-sm">
          Status
          <select
            value={currentStatus}
            onChange={(e) => updateParams({ status: e.target.value || null })}
            className="rounded border border-gray-300 px-2 py-1.5 text-sm dark:border-gray-700 dark:bg-transparent"
          >
            <option value="">All</option>
            {STATUS_OPTIONS.map((status) => (
              <option key={status} value={status}>
                {status}
              </option>
            ))}
          </select>
        </label>

        <label className="flex flex-col gap-1 text-sm">
          Sort
          <select
            value={currentSort}
            onChange={(e) => updateParams({ sort: e.target.value || null })}
            className="rounded border border-gray-300 px-2 py-1.5 text-sm dark:border-gray-700 dark:bg-transparent"
          >
            <option value="">Default</option>
            {SORT_OPTIONS.map((opt) => (
              <option key={opt.value} value={opt.value}>
                {opt.label}
              </option>
            ))}
          </select>
        </label>
      </div>

      {categories && categories.length > 0 && (
        <div className="flex flex-wrap gap-2" role="group" aria-label="Filter by category">
          <button
            type="button"
            onClick={() => updateParams({ category: null })}
            aria-pressed={currentCategory === ""}
            className={`rounded-full px-3 py-1 text-xs font-medium ${
              currentCategory === ""
                ? "bg-foreground text-background"
                : "border border-gray-300 text-gray-600 dark:border-gray-700 dark:text-gray-300"
            }`}
          >
            All categories
          </button>
          {categories.map((category) => (
            <button
              key={category.id}
              type="button"
              onClick={() => updateParams({ category: category.id })}
              aria-pressed={currentCategory === category.id}
              className={`rounded-full px-3 py-1 text-xs font-medium ${
                currentCategory === category.id
                  ? "bg-foreground text-background"
                  : "border border-gray-300 text-gray-600 dark:border-gray-700 dark:text-gray-300"
              }`}
            >
              {category.name}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
