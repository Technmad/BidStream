"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { ApiError } from "@/src/api/client";
import { useAuth } from "@/src/features/auth/useAuth";

/**
 * §12.5 register form. Calls `POST /auth/register` then immediately `POST /auth/login`
 * with the same credentials (§4.1: register returns no tokens) — both are wrapped
 * together in `useAuth().register`.
 */
export default function RegisterPage() {
  const { register } = useAuth();
  const router = useRouter();

  const [username, setUsername] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    setFormError(null);
    setFieldErrors({});
    setSubmitting(true);
    try {
      await register(username, email, password);
      router.push("/");
    } catch (err) {
      if (err instanceof ApiError) {
        // §4.1/§13: a validation 400 carries `errors: {field: message}` — map
        // straight onto form fields rather than showing one generic message.
        if (err.status === 400 && err.problem.errors) {
          setFieldErrors(err.problem.errors);
        } else {
          setFormError(err.problem.detail ?? err.problem.title ?? "Registration failed.");
        }
      } else {
        setFormError("Registration failed. Please try again.");
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="mx-auto flex w-full max-w-sm flex-1 flex-col justify-center gap-6 px-4 py-16">
      <h1 className="text-2xl font-semibold">Register</h1>
      <form onSubmit={handleSubmit} className="flex flex-col gap-4" noValidate>
        <label className="flex flex-col gap-1 text-sm">
          Username
          <input
            className="rounded border border-gray-300 px-3 py-2 dark:border-gray-700 dark:bg-transparent"
            name="username"
            autoComplete="username"
            required
            value={username}
            onChange={(e) => setUsername(e.target.value)}
          />
          {fieldErrors.username && (
            <span className="text-xs text-red-600 dark:text-red-400">{fieldErrors.username}</span>
          )}
        </label>
        <label className="flex flex-col gap-1 text-sm">
          Email
          <input
            className="rounded border border-gray-300 px-3 py-2 dark:border-gray-700 dark:bg-transparent"
            name="email"
            type="email"
            autoComplete="email"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
          {fieldErrors.email && (
            <span className="text-xs text-red-600 dark:text-red-400">{fieldErrors.email}</span>
          )}
        </label>
        <label className="flex flex-col gap-1 text-sm">
          Password
          <input
            className="rounded border border-gray-300 px-3 py-2 dark:border-gray-700 dark:bg-transparent"
            name="password"
            type="password"
            autoComplete="new-password"
            required
            minLength={8}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
          {fieldErrors.password && (
            <span className="text-xs text-red-600 dark:text-red-400">{fieldErrors.password}</span>
          )}
        </label>
        {formError && (
          <p role="alert" className="text-sm text-red-600 dark:text-red-400">
            {formError}
          </p>
        )}
        <button
          type="submit"
          disabled={submitting}
          className="rounded bg-foreground px-4 py-2 text-sm font-medium text-background disabled:opacity-50"
        >
          {submitting ? "Creating account…" : "Register"}
        </button>
      </form>
      <p className="text-sm text-gray-500">
        Already have an account?{" "}
        <Link href="/login" className="underline">
          Log in
        </Link>
      </p>
    </main>
  );
}
